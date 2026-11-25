//! Cronologia locale degli upload (`history.json` nella cartella dati, senza password) e
//! sincronizzazione con i file del server: stessa logica di Android e macOS.

use crate::gokapi::GokapiFile;
use crate::store::{load_json, save_json, Paths};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use uuid::Uuid;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum UploadState {
    Queued,
    Uploading,
    Completed,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct UploadRecord {
    pub id: Uuid,
    pub server_id: Uuid,
    pub server_name: String,
    /// Vuoto per i file importati dal server (caricati da un altro dispositivo).
    pub source_path: String,
    pub file_name: String,
    pub size_bytes: u64,
    pub mime_type: Option<String>,
    pub expiry_days: u32,
    pub allowed_downloads: u32,
    pub has_password: bool,
    pub state: UploadState,
    pub error_message: Option<String>,
    pub file_id: Option<String>,
    pub url: Option<String>,
    /// Unix secondi.
    pub expires_at: Option<i64>,
    #[serde(default)]
    pub unlimited_time: bool,
    #[serde(default)]
    pub unlimited_downloads: bool,
    pub downloads_remaining: Option<i64>,
    #[serde(default)]
    pub removed_from_server: bool,
    /// Rimossa dalla cronologia ma conservata, per non essere reimportata dalla sincronizzazione.
    #[serde(default)]
    pub hidden: bool,
    /// Unix millisecondi.
    pub created_at: i64,
    pub completed_at: Option<i64>,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

impl UploadRecord {
    pub fn is_active(&self) -> bool {
        matches!(self.state, UploadState::Queued | UploadState::Uploading)
    }

    pub fn from_other_device(&self) -> bool {
        self.source_path.is_empty()
    }

    /// Corrisponde a un file sul server: può essere sincronizzata.
    pub fn is_synced(&self) -> bool {
        self.state == UploadState::Completed && self.file_id.is_some()
    }

    pub fn is_expired(&self) -> bool {
        self.removed_from_server
            || (!self.unlimited_time && self.expires_at.is_some_and(|e| e * 1000 <= now_ms()))
    }

    pub fn has_usable_link(&self) -> bool {
        self.state == UploadState::Completed && self.url.is_some() && !self.is_expired()
    }

    fn apply_remote(&mut self, f: &GokapiFile) {
        self.removed_from_server = false;
        self.downloads_remaining = Some(f.downloads_remaining);
        self.unlimited_downloads = f.unlimited_downloads;
        self.unlimited_time = f.unlimited_time;
        self.has_password = f.is_password_protected;
        if f.expire_at > 0 {
            self.expires_at = Some(f.expire_at);
        }
    }
}

pub struct History {
    pub records: Vec<UploadRecord>,
    path: Option<PathBuf>,
}

impl History {
    /// Carica la cronologia; gli upload rimasti "in corso" da un'esecuzione precedente diventano
    /// falliti (Gokapi non riprende gli upload interrotti).
    pub fn load(paths: &Paths) -> Self {
        let path = paths.data.join("history.json");
        let mut records: Vec<UploadRecord> = load_json(&path).unwrap_or_default();
        let mut changed = false;
        for r in records.iter_mut().filter(|r| r.is_active()) {
            r.state = UploadState::Failed;
            r.error_message = Some("Caricamento interrotto. Usa Riprova per ricominciare.".into());
            changed = true;
        }
        let h = Self {
            records,
            path: Some(path),
        };
        if changed {
            h.save();
        }
        h
    }

    pub fn in_memory() -> Self {
        Self {
            records: Vec::new(),
            path: None,
        }
    }

    pub fn save(&self) {
        if let Some(p) = &self.path {
            save_json(p, &self.records);
        }
    }

    pub fn get(&self, id: Uuid) -> Option<&UploadRecord> {
        self.records.iter().find(|r| r.id == id)
    }

    pub fn get_mut(&mut self, id: Uuid) -> Option<&mut UploadRecord> {
        self.records.iter_mut().find(|r| r.id == id)
    }

    pub fn active(&self) -> Vec<&UploadRecord> {
        let mut v: Vec<_> = self.records.iter().filter(|r| r.is_active()).collect();
        v.sort_by_key(|r| r.created_at);
        v
    }

    /// Voci visibili in cronologia, dalla più recente.
    pub fn visible(&self) -> Vec<&UploadRecord> {
        let mut v: Vec<_> = self
            .records
            .iter()
            .filter(|r| !r.is_active() && !r.hidden)
            .collect();
        v.sort_by_key(|r| std::cmp::Reverse(r.created_at));
        v
    }

    /// Rimuove dalla cronologia: le voci sincronizzabili vengono nascoste, le altre eliminate.
    pub fn remove(&mut self, id: Uuid) {
        if let Some(r) = self.get_mut(id) {
            if r.is_synced() {
                r.hidden = true;
            }
        }
        self.records
            .retain(|r| r.id != id || r.is_active() || r.is_synced());
        self.save();
    }

    /// Svuota la cronologia conservando (visibili) le voci i cui file sono ancora sul server.
    pub fn clear(&mut self) {
        // Le voci scadute ma sincronizzabili restano salvate (nascoste) per non essere reimportate.
        for r in self
            .records
            .iter_mut()
            .filter(|r| r.is_synced() && r.is_expired())
        {
            r.hidden = true;
        }
        self.records.retain(|r| r.is_active() || r.is_synced());
        self.save();
    }

    /// Applica l'elenco dei file del server (`/files/list`): importa i file caricati altrove,
    /// aggiorna download rimasti e scadenze, segna i file non più presenti.
    /// Le voci nascoste restano nascoste. Restituisce il numero di file importati.
    pub fn apply_sync(
        &mut self,
        server_id: Uuid,
        server_name: &str,
        remote: &[GokapiFile],
    ) -> usize {
        let remote: Vec<&GokapiFile> = remote.iter().filter(|f| !f.is_pending_deletion).collect();
        let mut imported = 0;
        for f in &remote {
            match self
                .records
                .iter_mut()
                .find(|r| r.server_id == server_id && r.file_id.as_deref() == Some(&f.id))
            {
                Some(r) => r.apply_remote(f),
                None => {
                    let uploaded = if f.upload_date > 0 {
                        f.upload_date * 1000
                    } else {
                        now_ms()
                    };
                    self.records.push(UploadRecord {
                        id: Uuid::new_v4(),
                        server_id,
                        server_name: server_name.to_string(),
                        source_path: String::new(),
                        file_name: f.name.clone(),
                        size_bytes: f.size_bytes,
                        mime_type: Some(f.content_type.clone()).filter(|c| !c.is_empty()),
                        expiry_days: 0,
                        allowed_downloads: 0,
                        has_password: f.is_password_protected,
                        state: UploadState::Completed,
                        error_message: None,
                        file_id: Some(f.id.clone()),
                        url: Some(f.url_download.clone()),
                        expires_at: Some(f.expire_at).filter(|&e| e > 0),
                        unlimited_time: f.unlimited_time,
                        unlimited_downloads: f.unlimited_downloads,
                        downloads_remaining: Some(f.downloads_remaining),
                        removed_from_server: false,
                        hidden: false,
                        created_at: uploaded,
                        completed_at: Some(uploaded),
                    });
                    imported += 1;
                }
            }
        }
        for r in self
            .records
            .iter_mut()
            .filter(|r| r.server_id == server_id && r.is_synced())
        {
            if !remote
                .iter()
                .any(|f| Some(f.id.as_str()) == r.file_id.as_deref())
            {
                r.removed_from_server = true;
            }
        }
        self.save();
        imported
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn remote(id: &str, name: &str) -> GokapiFile {
        GokapiFile {
            id: id.into(),
            name: name.into(),
            size_bytes: 10,
            url_download: format!("https://x/d?id={id}"),
            upload_date: 1_700_000_000,
            expire_at: 4_000_000_000,
            downloads_remaining: 3,
            ..Default::default()
        }
    }

    fn local(server: Uuid, file_id: &str) -> UploadRecord {
        UploadRecord {
            id: Uuid::new_v4(),
            server_id: server,
            server_name: "S".into(),
            source_path: "/tmp/a".into(),
            file_name: "a".into(),
            size_bytes: 1,
            mime_type: None,
            expiry_days: 14,
            allowed_downloads: 1,
            has_password: false,
            state: UploadState::Completed,
            error_message: None,
            file_id: Some(file_id.into()),
            url: Some("u".into()),
            expires_at: Some(4_000_000_000),
            unlimited_time: false,
            unlimited_downloads: false,
            downloads_remaining: Some(1),
            removed_from_server: false,
            hidden: false,
            created_at: 1,
            completed_at: Some(1),
        }
    }

    #[test]
    fn sync_imports_updates_and_marks_removed() {
        let s = Uuid::new_v4();
        let mut h = History::in_memory();
        h.records.push(local(s, "kept"));
        h.records.push(local(s, "gone"));
        let n = h.apply_sync(
            s,
            "S",
            &[
                remote("kept", "a"),
                remote("new", "b"),
                GokapiFile {
                    is_pending_deletion: true,
                    ..remote("p", "c")
                },
            ],
        );
        assert_eq!(n, 1);
        assert_eq!(h.records.len(), 3);
        let kept = h
            .records
            .iter()
            .find(|r| r.file_id.as_deref() == Some("kept"))
            .unwrap();
        assert_eq!(kept.downloads_remaining, Some(3));
        assert!(
            h.records
                .iter()
                .find(|r| r.file_id.as_deref() == Some("gone"))
                .unwrap()
                .removed_from_server
        );
        assert!(h
            .records
            .iter()
            .find(|r| r.file_id.as_deref() == Some("new"))
            .unwrap()
            .from_other_device());
        // Seconda sincronizzazione: nessun duplicato.
        assert_eq!(
            h.apply_sync(s, "S", &[remote("kept", "a"), remote("new", "b")]),
            0
        );
    }

    #[test]
    fn hidden_entries_stay_hidden_and_clear_keeps_files_on_server() {
        let s = Uuid::new_v4();
        let mut h = History::in_memory();
        let on_server = local(s, "on");
        let mut failed = local(s, "x");
        failed.state = UploadState::Failed;
        failed.file_id = None;
        let mut expired = local(s, "old");
        expired.removed_from_server = true;
        let hidden_id = on_server.id;
        h.records.extend([on_server, failed, expired]);
        h.remove(hidden_id);
        assert!(h.visible().iter().all(|r| r.id != hidden_id));
        h.apply_sync(s, "S", &[remote("on", "a")]);
        assert!(
            h.get(hidden_id).unwrap().hidden,
            "la sincronizzazione non riporta le voci rimosse"
        );

        let mut h2 = History::in_memory();
        let keep = local(s, "on");
        let keep_id = keep.id;
        let mut gone = local(s, "old");
        gone.removed_from_server = true;
        let mut failed = local(s, "f");
        failed.state = UploadState::Failed;
        failed.file_id = None;
        h2.records.extend([keep, gone, failed]);
        h2.clear();
        let visible: Vec<_> = h2.visible().iter().map(|r| r.id).collect();
        assert_eq!(
            visible,
            vec![keep_id],
            "restano solo i file ancora sul server"
        );
        assert_eq!(
            h2.records.len(),
            2,
            "la voce scaduta resta nascosta per la sincronizzazione"
        );
    }
}
