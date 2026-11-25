//! Client HTTP bloccante (da usare su thread di lavoro). Ogni operazione lunga accetta un flag
//! [`Cancel`]: impostarlo interrompe l'invio del chunk in corso.

use super::chunks;
use super::models::*;
use base64::Engine;
use serde::de::DeserializeOwned;
use std::fs::File;
use std::io::{self, Cursor, Read, Seek, SeekFrom, Write};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use ureq::{Agent, AgentBuilder, Request, Response};
use url::Url;

/// Flag di annullamento condiviso tra interfaccia e thread di upload.
pub type Cancel = Arc<AtomicBool>;

#[derive(Debug, Clone, Copy)]
pub struct RetryPolicy {
    pub max_attempts: u32,
    pub initial_delay: Duration,
}

impl Default for RetryPolicy {
    fn default() -> Self {
        Self {
            max_attempts: 3,
            initial_delay: Duration::from_secs(1),
        }
    }
}

const BUFFER: usize = 64 * 1024;
const ERROR_FILE_TOO_LARGE: i64 = 9;

#[derive(Clone)]
pub struct GokapiClient {
    api: Url,
    api_key: String,
    agent: Agent,
    /// La finalizzazione di file grandi può richiedere minuti (hash, copia su storage).
    slow: Agent,
    retry: RetryPolicy,
}

fn agent(read_timeout: Duration) -> Agent {
    AgentBuilder::new()
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(read_timeout)
        .timeout_write(Duration::from_secs(120))
        // Un redirect potrebbe inoltrare l'header `apikey` a un altro host.
        .redirects(0)
        .user_agent(concat!("Panaru-Linux/", env!("CARGO_PKG_VERSION")))
        .build()
}

impl GokapiClient {
    pub fn new(base: &Url, api_key: &str) -> Self {
        let mut base = base.clone();
        if !base.path().ends_with('/') {
            let p = format!("{}/", base.path());
            base.set_path(&p);
        }
        Self {
            api: base.join("api/").expect("URL base valido"),
            api_key: api_key.to_string(),
            agent: agent(Duration::from_secs(120)),
            slow: agent(Duration::from_secs(15 * 60)),
            retry: RetryPolicy::default(),
        }
    }

    pub fn with_retry(mut self, retry: RetryPolicy) -> Self {
        self.retry = retry;
        self
    }

    // ------------------------------------------------------------------ info

    pub fn version(&self) -> Result<VersionInfo, GokapiError> {
        self.get_json("info/version")
    }

    pub fn config(&self) -> Result<ServerConfig, GokapiError> {
        self.get_json("info/config")
    }

    // ----------------------------------------------------------------- files

    /// File non scaduti (Gokapi restituisce `null` se non ce ne sono).
    pub fn list_files(&self) -> Result<Vec<GokapiFile>, GokapiError> {
        let body = self
            .call(self.req("GET", "files/list"), None)?
            .into_string()
            .map_err(net)?;
        let t = body.trim();
        if t.is_empty() || t == "null" {
            return Ok(Vec::new());
        }
        decode(t)
    }

    pub fn file(&self, id: &str) -> Result<GokapiFile, GokapiError> {
        self.get_json(&format!("files/list/{}", segment(id)?))
    }

    pub fn delete(&self, id: &str) -> Result<(), GokapiError> {
        self.call(self.req("DELETE", "files/delete").set("id", id), None)?;
        Ok(())
    }

    /// Scarica un file in `dest` senza incrementare il contatore dei download.
    pub fn download(
        &self,
        id: &str,
        dest: &Path,
        cancel: &Cancel,
        progress: &mut dyn FnMut(u64, u64),
    ) -> Result<(), GokapiError> {
        let path = format!("files/download/{}", segment(id)?);
        let resp = self.call_with(&self.slow, self.req_on(&self.slow, "GET", &path), None)?;
        let total = resp
            .header("Content-Length")
            .and_then(|v| v.parse().ok())
            .unwrap_or(0);
        let mut input = resp.into_reader();
        let mut out =
            File::create(dest).map_err(|e| GokapiError::SourceUnavailable(e.to_string()))?;
        let mut buf = vec![0u8; BUFFER];
        let mut done = 0u64;
        let result = (|| loop {
            if cancel.load(Ordering::Relaxed) {
                return Err(GokapiError::Cancelled);
            }
            let n = input
                .read(&mut buf)
                .map_err(|e| GokapiError::Network(e.to_string()))?;
            if n == 0 {
                return Ok(());
            }
            out.write_all(&buf[..n])
                .map_err(|e| GokapiError::SourceUnavailable(e.to_string()))?;
            done += n as u64;
            progress(done, total);
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(dest);
        }
        result
    }

    // ---------------------------------------------------------------- upload

    /// Carica il file con `/chunk/add` + `/chunk/complete` e restituisce il file creato.
    /// I chunk falliti per errori di rete o 5xx vengono ritentati con backoff esponenziale.
    #[allow(clippy::too_many_arguments)]
    pub fn upload(
        &self,
        path: &Path,
        file_name: &str,
        mime: Option<&str>,
        options: &UploadOptions,
        config: Option<&ServerConfig>,
        cancel: &Cancel,
        progress: &mut dyn FnMut(u64, u64),
    ) -> Result<GokapiFile, GokapiError> {
        let size = std::fs::metadata(path)
            .map_err(|e| GokapiError::SourceUnavailable(e.to_string()))?
            .len();
        if chunks::exceeds_limit(size, config) {
            return Err(GokapiError::FileTooLarge(
                config.map(|c| c.max_file_size_mb),
            ));
        }
        let uuid = uuid::Uuid::new_v4().to_string();
        let mut confirmed = 0u64;
        progress(0, size);
        for chunk in chunks::plan(size, chunks::chunk_size(config)) {
            self.retrying(cancel, || {
                self.send_chunk(path, &uuid, size, chunk, cancel, &mut |sent| {
                    progress(confirmed + sent, size)
                })
            })?;
            confirmed += chunk.length;
            progress(confirmed, size);
        }
        if cancel.load(Ordering::Relaxed) {
            return Err(GokapiError::Cancelled);
        }
        let mut req = self
            .req_on(&self.slow, "POST", "chunk/complete")
            .set("uuid", &uuid)
            .set("filename", &encode_file_name(file_name))
            .set("filesize", &size.to_string())
            .set(
                "contenttype",
                mime.filter(|m| !m.is_empty())
                    .unwrap_or("application/octet-stream"),
            )
            .set("allowedDownloads", &options.allowed_downloads.to_string())
            .set("expiryDays", &options.expiry_days.to_string());
        if let Some(pw) = options.password.as_deref().filter(|p| !p.is_empty()) {
            req = req.set("password", pw);
        }
        let body = self
            .call_with(&self.slow, req, Some(&[]))?
            .into_string()
            .map_err(net)?;
        let result: UploadResult = decode(&body)?;
        result
            .file_info
            .ok_or_else(|| GokapiError::Protocol("FileInfo mancante".into()))
    }

    fn send_chunk(
        &self,
        path: &Path,
        uuid: &str,
        size: u64,
        chunk: chunks::Chunk,
        cancel: &Cancel,
        on_sent: &mut dyn FnMut(u64),
    ) -> Result<(), GokapiError> {
        let mut file =
            File::open(path).map_err(|e| GokapiError::SourceUnavailable(e.to_string()))?;
        file.seek(SeekFrom::Start(chunk.offset))
            .map_err(|e| GokapiError::SourceUnavailable(e.to_string()))?;
        let boundary = format!("panaru-{}", uuid::Uuid::new_v4().simple());
        let mut head = String::new();
        for (name, value) in [
            ("uuid", uuid.to_string()),
            ("filesize", size.to_string()),
            ("offset", chunk.offset.to_string()),
        ] {
            head.push_str(&format!("--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n{value}\r\n"));
        }
        head.push_str(&format!(
            "--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        ));
        let tail = format!("\r\n--{boundary}--\r\n");
        let total_len = head.len() as u64 + chunk.length + tail.len() as u64;

        let source_error = Arc::new(Mutex::new(None::<String>));
        let body = ChunkReader {
            file: file.take(chunk.length),
            remaining: chunk.length,
            sent: 0,
            cancel: cancel.clone(),
            error: source_error.clone(),
            on_sent,
        };
        let reader = Cursor::new(head.into_bytes())
            .chain(body)
            .chain(Cursor::new(tail.into_bytes()));
        let req = self
            .req("POST", "chunk/add")
            .set(
                "Content-Type",
                &format!("multipart/form-data; boundary={boundary}"),
            )
            .set("Content-Length", &total_len.to_string());
        let result = map_response(req.send(reader));
        if cancel.load(Ordering::Relaxed) {
            return Err(GokapiError::Cancelled);
        }
        if let Some(msg) = source_error.lock().unwrap().take() {
            return Err(GokapiError::SourceUnavailable(msg));
        }
        result.map(|_| ())
    }

    // ------------------------------------------------------------- internals

    fn retrying<T>(
        &self,
        cancel: &Cancel,
        mut block: impl FnMut() -> Result<T, GokapiError>,
    ) -> Result<T, GokapiError> {
        let mut attempt = 1;
        let mut wait = self.retry.initial_delay;
        loop {
            match block() {
                Ok(v) => return Ok(v),
                Err(e) if e.is_retryable() && attempt < self.retry.max_attempts => {
                    let deadline = std::time::Instant::now() + wait;
                    while std::time::Instant::now() < deadline {
                        if cancel.load(Ordering::Relaxed) {
                            return Err(GokapiError::Cancelled);
                        }
                        std::thread::sleep(Duration::from_millis(50).min(wait));
                    }
                    wait *= 2;
                    attempt += 1;
                }
                Err(e) => return Err(e),
            }
        }
    }

    fn req(&self, method: &str, path: &str) -> Request {
        self.req_on(&self.agent, method, path)
    }

    fn req_on(&self, agent: &Agent, method: &str, path: &str) -> Request {
        let url = self.api.join(path).expect("percorso API valido");
        agent
            .request_url(method, &url)
            .set("apikey", &self.api_key)
            .set("Accept", "application/json")
    }

    fn get_json<T: DeserializeOwned>(&self, path: &str) -> Result<T, GokapiError> {
        let body = self
            .call(self.req("GET", path), None)?
            .into_string()
            .map_err(net)?;
        decode(&body)
    }

    fn call(&self, req: Request, body: Option<&[u8]>) -> Result<Response, GokapiError> {
        self.call_with(&self.agent, req, body)
    }

    fn call_with(
        &self,
        _agent: &Agent,
        req: Request,
        body: Option<&[u8]>,
    ) -> Result<Response, GokapiError> {
        map_response(match body {
            Some(b) => req.send_bytes(b),
            None => req.call(),
        })
    }
}

/// Corpo del chunk letto in streaming dal file: mai il file intero in memoria.
struct ChunkReader<'a> {
    file: io::Take<File>,
    remaining: u64,
    sent: u64,
    cancel: Cancel,
    error: Arc<Mutex<Option<String>>>,
    on_sent: &'a mut dyn FnMut(u64),
}

impl Read for ChunkReader<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.cancel.load(Ordering::Relaxed) {
            return Err(io::Error::new(io::ErrorKind::Interrupted, "annullato"));
        }
        if self.remaining == 0 {
            return Ok(0);
        }
        let max = buf.len().min(BUFFER).min(self.remaining as usize);
        let n = match self.file.read(&mut buf[..max]) {
            Ok(n) => n,
            Err(e) => {
                *self.error.lock().unwrap() = Some(e.to_string());
                return Err(e);
            }
        };
        if n == 0 {
            let msg = "il file è cambiato durante il caricamento".to_string();
            *self.error.lock().unwrap() = Some(msg.clone());
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, msg));
        }
        self.remaining -= n as u64;
        self.sent += n as u64;
        (self.on_sent)(self.sent);
        Ok(n)
    }
}

fn map_response(result: Result<Response, ureq::Error>) -> Result<Response, GokapiError> {
    match result {
        Ok(resp) if (200..300).contains(&resp.status()) => Ok(resp),
        Ok(resp) => Err(GokapiError::Protocol(format!(
            "il server ha risposto {} (reindirizzamento non seguito per sicurezza)",
            resp.status()
        ))),
        Err(ureq::Error::Status(status, resp)) => {
            let raw = resp.into_string().unwrap_or_default();
            let err: ErrorBody = serde_json::from_str(&raw).unwrap_or_default();
            Err(match status {
                401 => GokapiError::Unauthorized,
                404 => GokapiError::NotFound,
                429 => GokapiError::RateLimited,
                _ if err.code == Some(ERROR_FILE_TOO_LARGE) => GokapiError::FileTooLarge(None),
                _ => GokapiError::Server {
                    status,
                    code: err.code,
                    message: if err.message.trim().is_empty() {
                        format!("HTTP {status}")
                    } else {
                        err.message
                    },
                },
            })
        }
        Err(ureq::Error::Transport(t)) => {
            let msg = t.to_string();
            let lower = msg.to_ascii_lowercase();
            if lower.contains("certificate") || lower.contains("tls") || lower.contains("handshake")
            {
                Err(GokapiError::Tls(msg))
            } else {
                Err(GokapiError::Network(msg))
            }
        }
    }
}

fn net(e: io::Error) -> GokapiError {
    GokapiError::Network(e.to_string())
}

fn decode<T: DeserializeOwned>(body: &str) -> Result<T, GokapiError> {
    serde_json::from_str(body).map_err(|e| GokapiError::Protocol(e.to_string()))
}

fn segment(id: &str) -> Result<&str, GokapiError> {
    if !id.is_empty()
        && id
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
    {
        Ok(id)
    } else {
        Err(GokapiError::Protocol("ID file non valido".into()))
    }
}

/// Gokapi riceve il nome file in un header HTTP: i nomi non ASCII (o che iniziano con `base64:`)
/// vengono inviati come `base64:<UTF-8 in base64 standard>`.
pub fn encode_file_name(name: &str) -> String {
    let clean = name.replace(['\r', '\n'], " ");
    let clean = if clean.trim().is_empty() {
        "file".to_string()
    } else {
        clean
    };
    let plain = clean.chars().all(|c| (' '..='~').contains(&c)) && !clean.starts_with("base64:");
    if plain {
        clean
    } else {
        format!(
            "base64:{}",
            base64::engine::general_purpose::STANDARD.encode(clean.as_bytes())
        )
    }
}
