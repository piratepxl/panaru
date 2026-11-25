//! Client per l'API di Gokapi. Comportamento verificato in `docs/GOKAPI_API.md`;
//! stesso contratto dei client Android (`:core:gokapi`) e macOS (`PanaruKit`).

mod client;
mod models;
mod server_url;

pub use client::{encode_file_name, Cancel, GokapiClient, RetryPolicy};
pub use models::*;
pub use server_url::{is_local_host, parse_server_url, ServerUrl, UrlError};

/// Megabyte (Gokapi esprime i limiti in MB binari).
pub const MB: u64 = 1024 * 1024;

/// Suddivisione di un file in chunk secondo i vincoli di Gokapi:
/// ogni chunk ≥ 5 MB salvo l'ultimo; la richiesta non può superare `MaxFilesize`;
/// la dimensione preferita è `MaxChunksize` del server.
pub mod chunks {
    use super::{ServerConfig, MB};

    pub const MIN_CHUNK: u64 = 5 * MB;
    pub const FALLBACK_CHUNK_MB: u64 = 45;
    /// Margine per l'overhead multipart (campi uuid/filesize/offset e boundary).
    const MULTIPART_OVERHEAD: u64 = 64 * 1024;

    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub struct Chunk {
        pub offset: u64,
        pub length: u64,
    }

    pub fn chunk_size(config: Option<&ServerConfig>) -> u64 {
        let preferred = config
            .map(|c| c.max_chunk_size_mb)
            .filter(|&v| v > 0)
            .map(|v| v as u64)
            .unwrap_or(FALLBACK_CHUNK_MB)
            * MB;
        let max_request = config
            .map(|c| c.max_file_size_mb)
            .filter(|&v| v > 0)
            .map(|v| (v as u64 * MB).saturating_sub(MULTIPART_OVERHEAD));
        let capped = max_request.map_or(preferred, |m| preferred.min(m));
        capped.max(MIN_CHUNK)
    }

    /// `true` se il file supera il limite del server.
    pub fn exceeds_limit(size: u64, config: Option<&ServerConfig>) -> bool {
        match config.map(|c| c.max_file_size_mb).filter(|&v| v > 0) {
            Some(max) => size > max as u64 * MB,
            None => false,
        }
    }

    pub fn plan(size: u64, chunk_size: u64) -> Vec<Chunk> {
        assert!(chunk_size > 0, "chunk_size non positivo");
        if size == 0 {
            return vec![Chunk {
                offset: 0,
                length: 0,
            }];
        }
        let mut out = Vec::with_capacity((size / chunk_size + 1) as usize);
        let mut offset = 0;
        while offset < size {
            let length = chunk_size.min(size - offset);
            out.push(Chunk { offset, length });
            offset += length;
        }
        out
    }
}
