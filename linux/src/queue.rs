//! Coda di upload: concorrenza configurabile, annullamento, retry. Indipendente da GTK:
//! i thread di lavoro inviano [`WorkerEvent`] su un canale che l'interfaccia (o i test) consuma
//! chiamando [`Engine::handle`]. Le password restano solo in memoria.

use crate::gokapi::{Cancel, GokapiError, GokapiFile, UploadOptions};
use crate::history::{now_ms, History, UploadRecord, UploadState};
use crate::store::ServerStore;
use async_channel::Sender;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use uuid::Uuid;

#[derive(Debug)]
pub enum WorkerEvent {
    Progress {
        id: Uuid,
        sent: u64,
        total: u64,
    },
    Finished {
        id: Uuid,
        result: Result<GokapiFile, GokapiError>,
    },
}

/// Esito di un evento, per l'interfaccia (copia link, notifiche, aggiornamento righe).
#[derive(Debug, Clone, PartialEq)]
pub enum Outcome {
    Progress(Uuid),
    Completed(UploadRecord),
    Failed(UploadRecord),
    Cancelled(Uuid),
}

pub struct Engine {
    pub history: History,
    pub servers: ServerStore,
    pub live: HashMap<Uuid, (u64, u64)>,
    pub max_concurrent: usize,
    passwords: HashMap<Uuid, String>,
    running: HashMap<Uuid, Cancel>,
    user_cancelled: HashSet<Uuid>,
    tx: Sender<WorkerEvent>,
}

impl Engine {
    pub fn new(history: History, servers: ServerStore, tx: Sender<WorkerEvent>) -> Self {
        Self {
            history,
            servers,
            live: HashMap::new(),
            max_concurrent: 2,
            passwords: HashMap::new(),
            running: HashMap::new(),
            user_cancelled: HashSet::new(),
            tx,
        }
    }

    /// Espande le cartelle nei file contenuti (Gokapi non ha upload di cartelle: un link per file).
    pub fn expand(paths: &[PathBuf]) -> Vec<PathBuf> {
        fn walk(p: &Path, out: &mut Vec<PathBuf>) {
            let Ok(meta) = std::fs::metadata(p) else {
                return;
            };
            if meta.is_file() {
                out.push(p.to_path_buf());
            } else if meta.is_dir() {
                let Ok(entries) = std::fs::read_dir(p) else {
                    return;
                };
                let mut children: Vec<PathBuf> = entries
                    .flatten()
                    .map(|e| e.path())
                    .filter(|c| {
                        !c.file_name()
                            .and_then(|n| n.to_str())
                            .is_some_and(|n| n.starts_with('.'))
                    })
                    .collect();
                children.sort();
                for c in children {
                    walk(&c, out);
                }
            }
        }
        let mut out = Vec::new();
        for p in paths {
            walk(p, &mut out);
        }
        out
    }

    pub fn enqueue(
        &mut self,
        paths: &[PathBuf],
        server_id: Uuid,
        options: &UploadOptions,
    ) -> Vec<Uuid> {
        let Some(server) = self.servers.get(server_id) else {
            return Vec::new();
        };
        let now = now_ms();
        let mut ids = Vec::new();
        for (i, path) in Self::expand(paths).into_iter().enumerate() {
            let size = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
            let name = path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_else(|| "file".into());
            let mime = mime_guess::from_path(&path)
                .first()
                .map(|m| m.essence_str().to_string());
            let r = UploadRecord {
                id: Uuid::new_v4(),
                server_id,
                server_name: server.name.clone(),
                source_path: path.to_string_lossy().into_owned(),
                file_name: name,
                size_bytes: size,
                mime_type: mime,
                expiry_days: options.expiry_days,
                allowed_downloads: options.allowed_downloads,
                has_password: options.password.is_some(),
                state: UploadState::Queued,
                error_message: None,
                file_id: None,
                url: None,
                expires_at: None,
                unlimited_time: false,
                unlimited_downloads: false,
                downloads_remaining: None,
                removed_from_server: false,
                hidden: false,
                created_at: now + i as i64,
                completed_at: None,
            };
            if let Some(pw) = &options.password {
                self.passwords.insert(r.id, pw.clone());
            }
            ids.push(r.id);
            self.history.records.push(r);
        }
        self.history.save();
        self.pump();
        ids
    }

    pub fn cancel(&mut self, id: Uuid) {
        let Some(r) = self.history.get_mut(id) else {
            return;
        };
        match r.state {
            UploadState::Queued => {
                r.state = UploadState::Cancelled;
                self.passwords.remove(&id);
                self.history.save();
            }
            UploadState::Uploading => {
                if let Some(c) = self.running.get(&id) {
                    self.user_cancelled.insert(id);
                    c.store(true, Ordering::Relaxed);
                }
            }
            _ => {}
        }
    }

    /// `false` se l'upload era protetto da password e la password non è più disponibile.
    pub fn retry(&mut self, id: Uuid) -> bool {
        let has_pw = self.passwords.contains_key(&id);
        let Some(r) = self.history.get_mut(id) else {
            return false;
        };
        if !matches!(r.state, UploadState::Failed | UploadState::Cancelled)
            || (r.has_password && !has_pw)
        {
            return false;
        }
        r.state = UploadState::Queued;
        r.error_message = None;
        self.history.save();
        self.pump();
        true
    }

    pub fn overall(&self) -> (u64, u64) {
        self.history.active().iter().fold((0, 0), |(s, t), r| {
            let (sent, total) = self.live.get(&r.id).copied().unwrap_or((0, r.size_bytes));
            (s + sent, t + total)
        })
    }

    pub fn is_idle(&self) -> bool {
        self.history.active().is_empty()
    }

    /// Avvia i prossimi upload in coda fino al limite di concorrenza.
    pub fn pump(&mut self) {
        while self.running.len() < self.max_concurrent.max(1) {
            let next = self
                .history
                .records
                .iter()
                .filter(|r| r.state == UploadState::Queued)
                .min_by_key(|r| r.created_at)
                .map(|r| r.id);
            let Some(id) = next else { break };
            let record = {
                let r = self.history.get_mut(id).unwrap();
                r.state = UploadState::Uploading;
                r.clone()
            };
            let cancel: Cancel = Arc::new(AtomicBool::new(false));
            self.running.insert(id, cancel.clone());
            self.live.insert(id, (0, record.size_bytes));
            self.spawn(record, self.passwords.get(&id).cloned(), cancel);
        }
        self.history.save();
    }

    fn spawn(&self, r: UploadRecord, password: Option<String>, cancel: Cancel) {
        let tx = self.tx.clone();
        let servers = self.servers.clone();
        std::thread::Builder::new()
            .name(format!("upload-{}", r.id))
            .spawn(move || {
                let id = r.id;
                let result = (|| {
                    let client = servers
                        .client(r.server_id)
                        .ok_or_else(|| GokapiError::Protocol("server o chiave API non disponibili".into()))?;
                    if r.has_password && password.is_none() {
                        return Err(GokapiError::SourceUnavailable(
                            "la password non viene conservata dopo un riavvio: carica di nuovo il file".into(),
                        ));
                    }
                    let config = servers.config(r.server_id, &client)?;
                    let options = UploadOptions {
                        expiry_days: r.expiry_days,
                        allowed_downloads: r.allowed_downloads,
                        password,
                    };
                    let mut last = Instant::now() - Duration::from_secs(1);
                    client.upload(
                        Path::new(&r.source_path),
                        &r.file_name,
                        r.mime_type.as_deref(),
                        &options,
                        Some(&config),
                        &cancel,
                        &mut |sent, total| {
                            if last.elapsed() >= Duration::from_millis(100) || sent == total {
                                last = Instant::now();
                                let _ = tx.send_blocking(WorkerEvent::Progress { id, sent, total });
                            }
                        },
                    )
                })();
                let _ = tx.send_blocking(WorkerEvent::Finished { id, result });
            })
            .expect("thread di upload");
    }

    /// Applica un evento dei thread di lavoro e restituisce l'esito per l'interfaccia.
    pub fn handle(&mut self, ev: WorkerEvent) -> Option<Outcome> {
        match ev {
            WorkerEvent::Progress { id, sent, total } => {
                if !self.running.contains_key(&id) {
                    return None;
                }
                self.live.insert(id, (sent, total));
                Some(Outcome::Progress(id))
            }
            WorkerEvent::Finished { id, result } => {
                self.running.remove(&id);
                self.live.remove(&id);
                let was_user = self.user_cancelled.remove(&id);
                let outcome = match result {
                    Ok(file) => {
                        self.passwords.remove(&id);
                        self.history.get_mut(id).map(|r| {
                            r.state = UploadState::Completed;
                            r.file_id = Some(file.id.clone());
                            r.url = Some(file.url_download.clone());
                            r.size_bytes = r.size_bytes.max(file.size_bytes);
                            r.expires_at = Some(file.expire_at).filter(|&e| e > 0);
                            r.unlimited_time = file.unlimited_time;
                            r.unlimited_downloads = file.unlimited_downloads;
                            r.downloads_remaining = Some(file.downloads_remaining);
                            r.completed_at = Some(now_ms());
                            Outcome::Completed(r.clone())
                        })
                    }
                    Err(GokapiError::Cancelled) if was_user => {
                        self.passwords.remove(&id);
                        if let Some(r) = self.history.get_mut(id) {
                            r.state = UploadState::Cancelled;
                        }
                        Some(Outcome::Cancelled(id))
                    }
                    Err(e) => self.history.get_mut(id).map(|r| {
                        r.state = UploadState::Failed;
                        r.error_message = Some(e.to_string());
                        Outcome::Failed(r.clone())
                    }),
                };
                self.history.save();
                self.pump();
                outcome
            }
        }
    }
}
