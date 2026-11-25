//! Validazione e normalizzazione dell'indirizzo di un server Gokapi (come Android/macOS):
//! - senza schema si assume `https://`;
//! - `http://` è accettato **solo** in rete locale (LAN, localhost, `.local`, `.internal`…);
//! - credenziali nell'URL rifiutate; query e frammento rimossi;
//! - i suffissi copiati dal browser (`/admin`, `/login`, `/api`, `/d`…) vengono rimossi.

use std::net::IpAddr;
use url::Url;

#[derive(Debug, Clone, PartialEq)]
pub struct ServerUrl {
    /// Indirizzo base con slash finale (es. `https://share.example.com/`).
    pub url: Url,
    pub is_insecure_local: bool,
}

impl ServerUrl {
    /// Forma canonica salvata, senza slash finale.
    pub fn canonical(&self) -> String {
        self.url.as_str().trim_end_matches('/').to_string()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UrlError {
    Empty,
    Malformed,
    UnsupportedScheme,
    CredentialsInUrl,
    InsecurePublicHost,
}

impl std::fmt::Display for UrlError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            UrlError::Empty => "Inserisci l'indirizzo del server.",
            UrlError::Malformed => "Indirizzo non valido.",
            UrlError::UnsupportedScheme => "Usa un indirizzo https://.",
            UrlError::CredentialsInUrl => "Non inserire nome utente o password nell'indirizzo.",
            UrlError::InsecurePublicHost => {
                "Su Internet serve https://: con http:// la chiave API viaggerebbe in chiaro. HTTP è ammesso solo in rete locale."
            }
        })
    }
}

const STRIP_SUFFIXES: &[&str] = &[
    "/api",
    "/admin",
    "/login",
    "/d",
    "/uploadRequest",
    "/apiKeys",
    "/users",
    "/logs",
];

pub fn parse_server_url(input: &str) -> Result<ServerUrl, UrlError> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err(UrlError::Empty);
    }
    let with_scheme = match trimmed.find("://") {
        None => format!("https://{trimmed}"),
        Some(i) => {
            let scheme = trimmed[..i].to_ascii_lowercase();
            if scheme != "http" && scheme != "https" {
                return Err(UrlError::UnsupportedScheme);
            }
            trimmed.to_string()
        }
    };
    let mut url = Url::parse(&with_scheme).map_err(|_| UrlError::Malformed)?;
    if !url.username().is_empty() || url.password().is_some() {
        return Err(UrlError::CredentialsInUrl);
    }
    let host = url.host_str().unwrap_or("").to_string();
    if host.is_empty() {
        return Err(UrlError::Malformed);
    }

    let mut path = url.path().trim_end_matches('/').to_string();
    loop {
        let mut changed = false;
        for suffix in STRIP_SUFFIXES {
            if path.len() >= suffix.len()
                && path[path.len() - suffix.len()..].eq_ignore_ascii_case(suffix)
            {
                path.truncate(path.len() - suffix.len());
                path = path.trim_end_matches('/').to_string();
                changed = true;
            }
        }
        if !changed {
            break;
        }
    }
    url.set_path(&format!("{path}/"));
    url.set_query(None);
    url.set_fragment(None);

    let insecure = url.scheme() != "https";
    if insecure && !is_local_host(&host) {
        return Err(UrlError::InsecurePublicHost);
    }
    Ok(ServerUrl {
        url,
        is_insecure_local: insecure,
    })
}

/// Host di reti private/locali (RFC 1918, loopback, link-local, CGNAT/Tailscale, ULA IPv6, mDNS).
pub fn is_local_host(host: &str) -> bool {
    let h = host
        .trim_matches(|c| c == '[' || c == ']')
        .to_ascii_lowercase();
    if h == "localhost"
        || [".localhost", ".local", ".lan", ".home.arpa", ".internal"]
            .iter()
            .any(|s| h.ends_with(s))
    {
        return true;
    }
    match h.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => {
            let [a, b, ..] = ip.octets();
            a == 10
                || a == 127
                || (a == 172 && (16..=31).contains(&b))
                || (a == 192 && b == 168)
                || (a == 169 && b == 254)
                || (a == 100 && (64..=127).contains(&b))
        }
        Ok(IpAddr::V6(ip)) => {
            let first = ip.segments()[0];
            ip.is_loopback() || (first & 0xfe00) == 0xfc00 || (first & 0xffc0) == 0xfe80
        }
        Err(_) => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn adds_https_and_strips_browser_suffixes() {
        let u = parse_server_url("share.example.com/admin?x=1#y").unwrap();
        assert_eq!(u.canonical(), "https://share.example.com");
        assert!(!u.is_insecure_local);
        let u = parse_server_url("https://example.com/gokapi/api/").unwrap();
        assert_eq!(u.canonical(), "https://example.com/gokapi");
        assert_eq!(
            parse_server_url("https://ex.com/d/login")
                .unwrap()
                .canonical(),
            "https://ex.com"
        );
    }

    #[test]
    fn http_only_on_local_networks() {
        assert_eq!(
            parse_server_url("http://example.com").unwrap_err(),
            UrlError::InsecurePublicHost
        );
        for ok in [
            "http://192.168.1.5:53842",
            "http://localhost",
            "http://nas.local",
            "http://gokapi.internal:53842",
            "http://10.0.0.2",
            "http://100.100.1.1",
            "http://[fd00::1]",
        ] {
            assert!(parse_server_url(ok).unwrap().is_insecure_local, "{ok}");
        }
        assert_eq!(
            parse_server_url("http://172.32.0.1").unwrap_err(),
            UrlError::InsecurePublicHost
        );
    }

    #[test]
    fn rejects_bad_inputs() {
        assert_eq!(parse_server_url("  ").unwrap_err(), UrlError::Empty);
        assert_eq!(
            parse_server_url("ftp://x.com").unwrap_err(),
            UrlError::UnsupportedScheme
        );
        assert_eq!(
            parse_server_url("https://u:p@x.com").unwrap_err(),
            UrlError::CredentialsInUrl
        );
        assert_eq!(
            parse_server_url("https://").unwrap_err(),
            UrlError::Malformed
        );
    }
}
