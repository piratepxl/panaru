use serde::Deserialize;
use std::fmt;

/// Risposta di `GET /api/info/version`.
#[derive(Debug, Clone, Default, Deserialize, PartialEq)]
#[serde(default)]
pub struct VersionInfo {
    #[serde(rename = "Version")]
    pub version: String,
    #[serde(rename = "VersionInt")]
    pub version_int: i64,
}

impl VersionInfo {
    /// I nomi file in base64 per `/chunk/complete` sono supportati da Gokapi 2.0.1 (20001).
    pub fn supports_base64_file_names(&self) -> bool {
        self.version_int >= 20001
    }
}

/// Risposta di `GET /api/info/config` (dimensioni in megabyte).
#[derive(Debug, Clone, Default, Deserialize, PartialEq)]
#[serde(default)]
pub struct ServerConfig {
    #[serde(rename = "MaxFilesize")]
    pub max_file_size_mb: i64,
    #[serde(rename = "MaxChunksize")]
    pub max_chunk_size_mb: i64,
    #[serde(rename = "EndToEndEncryptionEnabled")]
    pub end_to_end_encryption_enabled: bool,
}

/// Oggetto `File` di Gokapi (solo i campi usati da Panaru).
#[derive(Debug, Clone, Default, Deserialize, PartialEq)]
#[serde(default)]
pub struct GokapiFile {
    #[serde(rename = "Id")]
    pub id: String,
    #[serde(rename = "Name")]
    pub name: String,
    #[serde(rename = "SizeBytes")]
    pub size_bytes: u64,
    #[serde(rename = "ContentType")]
    pub content_type: String,
    #[serde(rename = "UrlDownload")]
    pub url_download: String,
    #[serde(rename = "UrlHotlink")]
    pub url_hotlink: String,
    #[serde(rename = "UploadDate")]
    pub upload_date: i64,
    #[serde(rename = "ExpireAt")]
    pub expire_at: i64,
    #[serde(rename = "UnlimitedTime")]
    pub unlimited_time: bool,
    #[serde(rename = "UnlimitedDownloads")]
    pub unlimited_downloads: bool,
    #[serde(rename = "DownloadsRemaining")]
    pub downloads_remaining: i64,
    #[serde(rename = "IsPasswordProtected")]
    pub is_password_protected: bool,
    #[serde(rename = "IsEndToEndEncrypted")]
    pub is_end_to_end_encrypted: bool,
    #[serde(rename = "IsPendingDeletion")]
    pub is_pending_deletion: bool,
}

#[derive(Debug, Deserialize)]
pub(crate) struct UploadResult {
    #[serde(rename = "FileInfo")]
    pub file_info: Option<GokapiFile>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(default)]
pub(crate) struct ErrorBody {
    #[serde(rename = "ErrorMessage")]
    pub message: String,
    #[serde(rename = "ErrorCode")]
    pub code: Option<i64>,
}

/// Opzioni di condivisione passate a `/chunk/complete`: 0 = illimitato.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UploadOptions {
    pub expiry_days: u32,
    pub allowed_downloads: u32,
    /// Solo ASCII stampabile: Gokapi la riceve in un header HTTP.
    pub password: Option<String>,
}

impl Default for UploadOptions {
    fn default() -> Self {
        Self {
            expiry_days: 14,
            allowed_downloads: 1,
            password: None,
        }
    }
}

impl UploadOptions {
    pub fn is_valid_password(value: &str) -> bool {
        value.chars().all(|c| (' '..='~').contains(&c))
    }
}

/// Errori tipizzati del client: ognuno corrisponde a un messaggio comprensibile.
#[derive(Debug, Clone, PartialEq)]
pub enum GokapiError {
    /// 401: chiave errata, revocata o senza il permesso richiesto (Gokapi non li distingue).
    Unauthorized,
    /// 404: file inesistente o scaduto.
    NotFound,
    FileTooLarge(Option<i64>),
    RateLimited,
    Server {
        status: u16,
        code: Option<i64>,
        message: String,
    },
    Network(String),
    Tls(String),
    SourceUnavailable(String),
    Protocol(String),
    Cancelled,
}

impl GokapiError {
    pub fn is_retryable(&self) -> bool {
        matches!(self, GokapiError::Network(_))
            || matches!(self, GokapiError::Server { status, .. } if *status >= 500)
    }
}

impl fmt::Display for GokapiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            GokapiError::Unauthorized => write!(
                f,
                "Chiave API rifiutata, o senza il permesso necessario per questa operazione."
            ),
            GokapiError::NotFound => write!(
                f,
                "File non trovato sul server: potrebbe essere scaduto o eliminato."
            ),
            GokapiError::FileTooLarge(Some(mb)) => {
                write!(f, "Il file supera il limite del server ({mb} MB).")
            }
            GokapiError::FileTooLarge(None) => write!(f, "Il file supera il limite del server."),
            GokapiError::RateLimited => write!(f, "Troppe richieste: riprova tra qualche istante."),
            GokapiError::Server { message, .. } => write!(f, "Errore del server: {message}"),
            GokapiError::Network(m) => write!(f, "Server non raggiungibile ({m})."),
            GokapiError::Tls(m) => write!(
                f,
                "Connessione sicura non riuscita: certificato non valido ({m})."
            ),
            GokapiError::SourceUnavailable(m) => write!(f, "Impossibile leggere il file: {m}"),
            GokapiError::Protocol(m) => write!(
                f,
                "Risposta inattesa: l'indirizzo è davvero un server Gokapi? ({m})"
            ),
            GokapiError::Cancelled => write!(f, "Annullato."),
        }
    }
}

impl std::error::Error for GokapiError {}
