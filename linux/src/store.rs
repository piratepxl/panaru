//! Server configurati, preferenze e API key.
//!
//! - `servers.json` e `prefs.json` in `$XDG_CONFIG_HOME/panaru` (nessun segreto);
//! - le API key nel portachiavi di sistema (Secret Service), mai su file.

use crate::gokapi::{GokapiClient, ServerConfig};
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use url::Url;
use uuid::Uuid;

/// Cartelle dell'app. `PANARU_HOME` (usata nei test) sostituisce le cartelle XDG.
#[derive(Debug, Clone)]
pub struct Paths {
    pub config: PathBuf,
    pub data: PathBuf,
}

impl Paths {
    pub fn system() -> Self {
        if let Ok(home) = std::env::var("PANARU_HOME") {
            let p = PathBuf::from(home);
            return Self {
                config: p.join("config"),
                data: p.join("data"),
            };
        }
        let dirs = directories::ProjectDirs::from("app", "panaru", "panaru")
            .expect("cartella home disponibile");
        Self {
            config: dirs.config_dir().to_path_buf(),
            data: dirs.data_dir().to_path_buf(),
        }
    }

    pub fn at(root: &Path) -> Self {
        Self {
            config: root.join("config"),
            data: root.join("data"),
        }
    }
}

pub fn load_json<T: DeserializeOwned>(path: &Path) -> Option<T> {
    let bytes = fs::read(path).ok()?;
    match serde_json::from_slice(&bytes) {
        Ok(v) => Some(v),
        Err(e) => {
            eprintln!(
                "panaru: {} illeggibile ({e}), ne conservo una copia",
                path.display()
            );
            let _ = fs::copy(path, path.with_extension("json.bad"));
            None
        }
    }
}

/// Scrittura atomica (file temporaneo + rename), permessi 0600.
pub fn save_json<T: Serialize>(path: &Path, value: &T) {
    let write = || -> std::io::Result<()> {
        if let Some(dir) = path.parent() {
            fs::create_dir_all(dir)?;
        }
        let tmp = path.with_extension("json.tmp");
        fs::write(&tmp, serde_json::to_vec_pretty(value)?)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600))?;
        }
        fs::rename(&tmp, path)
    };
    if let Err(e) = write() {
        eprintln!("panaru: impossibile salvare {}: {e}", path.display());
    }
}

// --------------------------------------------------------------------------- segreti

pub trait SecretStore: Send + Sync {
    fn get(&self, id: Uuid) -> Option<String>;
    fn set(&self, id: Uuid, secret: &str) -> Result<(), String>;
    fn delete(&self, id: Uuid);
}

/// Portachiavi di sistema via Secret Service (GNOME Keyring, KWallet, KeePassXC…).
pub struct KeyringStore;

const KEYRING_SERVICE: &str = "app.panaru.Panaru";

impl SecretStore for KeyringStore {
    fn get(&self, id: Uuid) -> Option<String> {
        keyring::Entry::new(KEYRING_SERVICE, &id.to_string())
            .ok()?
            .get_password()
            .ok()
    }

    fn set(&self, id: Uuid, secret: &str) -> Result<(), String> {
        keyring::Entry::new(KEYRING_SERVICE, &id.to_string())
            .and_then(|e| e.set_password(secret))
            .map_err(|e| {
                format!("Impossibile salvare la chiave API nel portachiavi di sistema: {e}")
            })
    }

    fn delete(&self, id: Uuid) {
        if let Ok(e) = keyring::Entry::new(KEYRING_SERVICE, &id.to_string()) {
            let _ = e.delete_credential();
        }
    }
}

/// Segreti solo in memoria (test).
#[derive(Default)]
pub struct MemorySecrets(Mutex<HashMap<Uuid, String>>);

impl SecretStore for MemorySecrets {
    fn get(&self, id: Uuid) -> Option<String> {
        self.0.lock().unwrap().get(&id).cloned()
    }
    fn set(&self, id: Uuid, secret: &str) -> Result<(), String> {
        self.0.lock().unwrap().insert(id, secret.to_string());
        Ok(())
    }
    fn delete(&self, id: Uuid) {
        self.0.lock().unwrap().remove(&id);
    }
}

// --------------------------------------------------------------------------- server

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Server {
    pub id: Uuid,
    pub name: String,
    /// Forma canonica, senza slash finale.
    pub url: String,
    #[serde(default)]
    pub is_default: bool,
}

/// Server configurati (thread-safe: i thread di upload chiedono client e configurazione).
#[derive(Clone)]
pub struct ServerStore {
    inner: Arc<Mutex<Vec<Server>>>,
    configs: Arc<Mutex<HashMap<Uuid, ServerConfig>>>,
    secrets: Arc<dyn SecretStore>,
    path: PathBuf,
}

impl ServerStore {
    pub fn new(paths: &Paths, secrets: Arc<dyn SecretStore>) -> Self {
        let path = paths.config.join("servers.json");
        let servers: Vec<Server> = load_json(&path).unwrap_or_default();
        Self {
            inner: Arc::new(Mutex::new(servers)),
            configs: Default::default(),
            secrets,
            path,
        }
    }

    pub fn all(&self) -> Vec<Server> {
        self.inner.lock().unwrap().clone()
    }

    pub fn get(&self, id: Uuid) -> Option<Server> {
        self.inner
            .lock()
            .unwrap()
            .iter()
            .find(|s| s.id == id)
            .cloned()
    }

    pub fn default_server(&self) -> Option<Server> {
        let all = self.inner.lock().unwrap();
        all.iter()
            .find(|s| s.is_default)
            .or_else(|| all.first())
            .cloned()
    }

    pub fn is_empty(&self) -> bool {
        self.inner.lock().unwrap().is_empty()
    }

    /// Aggiunge o aggiorna un server. `api_key = None` mantiene la chiave salvata.
    pub fn save(&self, mut server: Server, api_key: Option<&str>) -> Result<(), String> {
        if let Some(key) = api_key {
            self.secrets.set(server.id, key.trim())?;
        }
        let mut all = self.inner.lock().unwrap();
        if all.is_empty() {
            server.is_default = true;
        }
        if server.is_default {
            all.iter_mut().for_each(|s| s.is_default = false);
        }
        match all.iter_mut().find(|s| s.id == server.id) {
            Some(existing) => *existing = server.clone(),
            None => all.push(server.clone()),
        }
        self.configs.lock().unwrap().remove(&server.id);
        save_json(&self.path, &*all);
        Ok(())
    }

    pub fn set_default(&self, id: Uuid) {
        let mut all = self.inner.lock().unwrap();
        all.iter_mut().for_each(|s| s.is_default = s.id == id);
        save_json(&self.path, &*all);
    }

    pub fn remove(&self, id: Uuid) {
        self.secrets.delete(id);
        let mut all = self.inner.lock().unwrap();
        all.retain(|s| s.id != id);
        if !all.iter().any(|s| s.is_default) {
            if let Some(first) = all.first_mut() {
                first.is_default = true;
            }
        }
        save_json(&self.path, &*all);
    }

    pub fn client(&self, id: Uuid) -> Option<GokapiClient> {
        let server = self.get(id)?;
        let key = self.secrets.get(id)?;
        let url = Url::parse(&format!("{}/", server.url)).ok()?;
        Some(GokapiClient::new(&url, &key))
    }

    pub fn api_key(&self, id: Uuid) -> Option<String> {
        self.secrets.get(id)
    }

    /// Configurazione del server (limiti di dimensione), memorizzata per la sessione.
    pub fn config(
        &self,
        id: Uuid,
        client: &GokapiClient,
    ) -> Result<ServerConfig, crate::gokapi::GokapiError> {
        if let Some(c) = self.configs.lock().unwrap().get(&id) {
            return Ok(c.clone());
        }
        let c = client.config()?;
        self.configs.lock().unwrap().insert(id, c.clone());
        Ok(c)
    }
}

// --------------------------------------------------------------------------- preferenze

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(default)]
pub struct Prefs {
    pub default_expiry_days: u32,
    pub default_downloads: u32,
    pub max_concurrent: u32,
    /// Chiede scadenza/download/password prima di ogni caricamento.
    pub ask_options: bool,
    pub copy_link: bool,
    pub notify: bool,
    pub selected_server: Option<Uuid>,
}

impl Default for Prefs {
    fn default() -> Self {
        Self {
            default_expiry_days: 14,
            default_downloads: 1,
            max_concurrent: 2,
            ask_options: false,
            copy_link: true,
            notify: true,
            selected_server: None,
        }
    }
}

impl Prefs {
    pub fn load(paths: &Paths) -> Self {
        load_json(&paths.config.join("prefs.json")).unwrap_or_default()
    }

    pub fn save(&self, paths: &Paths) {
        save_json(&paths.config.join("prefs.json"), self);
    }
}
