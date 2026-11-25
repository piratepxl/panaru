//! Client e coda contro un server Gokapi simulato che riproduce il formato reale di richieste e
//! risposte (docs/GOKAPI_API.md). Nessuna rete esterna né display.

use panaru::gokapi::*;
use panaru::history::{History, UploadState};
use panaru::queue::{Engine, Outcome};
use panaru::store::{MemorySecrets, Paths, Server, ServerStore};
use std::collections::BTreeMap;
use std::io::Write;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use url::Url;

const KEY: &str = "test-key-1234567890";

#[derive(Default)]
struct Seen {
    chunks: BTreeMap<u64, Vec<u8>>,
    complete_headers: Vec<(String, String)>,
    add_calls: usize,
    paths: Vec<String>,
}

/// Server simulato: `handler(metodo, percorso, header, corpo) -> (status, corpo, header extra)`.
struct Mock {
    url: Url,
    seen: Arc<Mutex<Seen>>,
}

type Handler =
    dyn Fn(&str, &str, &[(String, String)], &[u8], &Mutex<Seen>) -> (u16, String) + Send + Sync;

fn mock(handler: Box<Handler>) -> Mock {
    let server = tiny_http::Server::http("127.0.0.1:0").unwrap();
    let port = server.server_addr().to_ip().unwrap().port();
    let seen = Arc::new(Mutex::new(Seen::default()));
    let s2 = seen.clone();
    std::thread::spawn(move || {
        for mut req in server.incoming_requests() {
            let headers: Vec<(String, String)> = req
                .headers()
                .iter()
                .map(|h| {
                    (
                        h.field.as_str().as_str().to_ascii_lowercase(),
                        h.value.as_str().to_string(),
                    )
                })
                .collect();
            let mut body = Vec::new();
            req.as_reader().read_to_end(&mut body).unwrap();
            let method = req.method().as_str().to_string();
            let path = req.url().to_string();
            s2.lock().unwrap().paths.push(format!("{method} {path}"));
            let (status, text) = if headers.iter().any(|(k, v)| k == "apikey" && v == KEY) {
                handler(&method, &path, &headers, &body, &s2)
            } else {
                (
                    401,
                    r#"{"Result":"error","ErrorMessage":"Unauthorized","ErrorCode":2}"#.into(),
                )
            };
            let mut resp = tiny_http::Response::from_string(text).with_status_code(status);
            if status == 302 {
                resp.add_header(
                    tiny_http::Header::from_bytes("Location", "https://evil.example/").unwrap(),
                );
            }
            let _ = req.respond(resp);
        }
    });
    Mock {
        url: Url::parse(&format!("http://127.0.0.1:{port}/")).unwrap(),
        seen,
    }
}

fn header<'a>(h: &'a [(String, String)], k: &str) -> Option<&'a str> {
    h.iter().find(|(n, _)| n == k).map(|(_, v)| v.as_str())
}

/// Estrae offset e byte del campo `file` da un corpo multipart.
fn parse_chunk(headers: &[(String, String)], body: &[u8]) -> (u64, Vec<u8>) {
    let ct = header(headers, "content-type").unwrap();
    let boundary = ct.split("boundary=").nth(1).unwrap();
    let text = String::from_utf8_lossy(body);
    let off_marker = "name=\"offset\"\r\n\r\n";
    let i = text.find(off_marker).unwrap() + off_marker.len();
    let offset: u64 = text[i..].split("\r\n").next().unwrap().parse().unwrap();
    let file_marker = b"Content-Type: application/octet-stream\r\n\r\n";
    let start = body
        .windows(file_marker.len())
        .position(|w| w == file_marker)
        .unwrap()
        + file_marker.len();
    let end_marker = format!("\r\n--{boundary}--\r\n");
    let end = body.len() - end_marker.len();
    assert_eq!(&body[end..], end_marker.as_bytes());
    assert_eq!(
        header(headers, "content-length")
            .unwrap()
            .parse::<usize>()
            .unwrap(),
        body.len()
    );
    (offset, body[start..end].to_vec())
}

fn file_json(name: &str, size: usize) -> String {
    format!(
        r#"{{"Result":"OK","FileInfo":{{"Id":"abc123","Name":"{name}","SizeBytes":{size},"UrlDownload":"https://share.example/d?id=abc123","ExpireAt":4000000000,"DownloadsRemaining":1}},"IncludeFilename":true}}"#
    )
}

fn gokapi_handler(fail_first_chunks: usize) -> Box<Handler> {
    let failures = AtomicUsize::new(fail_first_chunks);
    Box::new(
        move |method, path, headers, body, seen| match (method, path) {
            ("GET", "/api/info/version") => {
                (200, r#"{"Version":"2.2.5","VersionInt":20205}"#.into())
            }
            ("GET", "/api/info/config") => (
                200,
                r#"{"MaxFilesize":100,"MaxChunksize":5,"EndToEndEncryptionEnabled":false}"#.into(),
            ),
            ("GET", "/api/files/list") => (200, "null".into()),
            ("POST", "/api/chunk/add") => {
                seen.lock().unwrap().add_calls += 1;
                if failures.load(Ordering::SeqCst) > 0 {
                    failures.fetch_sub(1, Ordering::SeqCst);
                    return (
                        503,
                        r#"{"Result":"error","ErrorMessage":"busy","ErrorCode":0}"#.into(),
                    );
                }
                let (offset, bytes) = parse_chunk(headers, body);
                seen.lock().unwrap().chunks.insert(offset, bytes);
                (200, r#"{"result":"OK"}"#.into())
            }
            ("POST", "/api/chunk/complete") => {
                seen.lock().unwrap().complete_headers = headers.to_vec();
                let size: usize = header(headers, "filesize").unwrap().parse().unwrap();
                (200, file_json("x", size))
            }
            _ => (
                404,
                r#"{"Result":"error","ErrorMessage":"not found","ErrorCode":4}"#.into(),
            ),
        },
    )
}

fn temp_file(size: usize) -> (tempfile::NamedTempFile, Vec<u8>) {
    let data: Vec<u8> = (0..size).map(|i| (i * 31 % 251) as u8).collect();
    let mut f = tempfile::NamedTempFile::new().unwrap();
    f.write_all(&data).unwrap();
    (f, data)
}

fn fast(client: GokapiClient) -> GokapiClient {
    client.with_retry(RetryPolicy {
        max_attempts: 3,
        initial_delay: Duration::from_millis(10),
    })
}

#[test]
fn version_and_unauthorized() {
    let m = mock(gokapi_handler(0));
    let ok = GokapiClient::new(&m.url, KEY);
    assert_eq!(ok.version().unwrap().version_int, 20205);
    let bad = GokapiClient::new(&m.url, "wrong");
    assert_eq!(bad.version().unwrap_err(), GokapiError::Unauthorized);
    assert_eq!(ok.list_files().unwrap(), vec![], "null = nessun file");
}

#[test]
fn multi_chunk_upload_sends_exact_bytes_and_headers() {
    let m = mock(gokapi_handler(0));
    let client = fast(GokapiClient::new(&m.url, KEY));
    let config = client.config().unwrap();
    let size = 12 * 1024 * 1024 + 123; // 3 chunk da 5 MB
    let (file, data) = temp_file(size);
    let cancel = Arc::new(AtomicBool::new(false));
    let mut last = (0, 0);
    let opts = UploadOptions {
        expiry_days: 7,
        allowed_downloads: 0,
        password: Some("s3cret!".into()),
    };
    let result = client
        .upload(
            file.path(),
            "Città è bella.pdf",
            Some("application/pdf"),
            &opts,
            Some(&config),
            &cancel,
            &mut |s, t| last = (s, t),
        )
        .unwrap();
    assert_eq!(result.id, "abc123");
    assert_eq!(last, (size as u64, size as u64));

    let seen = m.seen.lock().unwrap();
    assert_eq!(seen.chunks.len(), 3);
    let joined: Vec<u8> = seen.chunks.values().flatten().copied().collect();
    assert_eq!(joined, data);
    let h = &seen.complete_headers;
    assert_eq!(
        header(h, "filename").unwrap(),
        encode_file_name("Città è bella.pdf")
    );
    assert!(header(h, "filename").unwrap().starts_with("base64:"));
    assert_eq!(header(h, "filesize").unwrap(), size.to_string());
    assert_eq!(header(h, "expirydays").unwrap(), "7");
    assert_eq!(header(h, "alloweddownloads").unwrap(), "0");
    assert_eq!(header(h, "password").unwrap(), "s3cret!");
    assert_eq!(header(h, "contenttype").unwrap(), "application/pdf");
}

#[test]
fn retries_server_errors_but_not_client_errors() {
    let m = mock(gokapi_handler(2));
    let client = fast(GokapiClient::new(&m.url, KEY));
    let (file, _) = temp_file(1000);
    let cancel = Arc::new(AtomicBool::new(false));
    client
        .upload(
            file.path(),
            "a.txt",
            None,
            &UploadOptions::default(),
            None,
            &cancel,
            &mut |_, _| {},
        )
        .unwrap();
    assert_eq!(
        m.seen.lock().unwrap().add_calls,
        3,
        "due 503 ritentati, poi successo"
    );

    let m = mock(Box::new(|_, _, _, _, _| {
        (
            400,
            r#"{"Result":"error","ErrorMessage":"bad","ErrorCode":1}"#.into(),
        )
    }));
    let client = fast(GokapiClient::new(&m.url, KEY));
    let err = client
        .upload(
            file.path(),
            "a.txt",
            None,
            &UploadOptions::default(),
            None,
            &cancel,
            &mut |_, _| {},
        )
        .unwrap_err();
    assert!(
        matches!(err, GokapiError::Server { status: 400, .. }),
        "{err:?}"
    );
    assert_eq!(
        m.seen.lock().unwrap().paths.len(),
        1,
        "nessun retry sui 4xx"
    );
}

#[test]
fn redirects_are_not_followed() {
    let m = mock(Box::new(|_, _, _, _, _| (302, String::new())));
    let err = GokapiClient::new(&m.url, KEY).version().unwrap_err();
    assert!(matches!(err, GokapiError::Protocol(_)), "{err:?}");
}

#[test]
fn file_too_large_is_rejected_before_sending() {
    let m = mock(gokapi_handler(0));
    let client = GokapiClient::new(&m.url, KEY);
    let (file, _) = temp_file(2 * 1024 * 1024);
    let config = ServerConfig {
        max_file_size_mb: 1,
        max_chunk_size_mb: 5,
        end_to_end_encryption_enabled: false,
    };
    let cancel = Arc::new(AtomicBool::new(false));
    let err = client
        .upload(
            file.path(),
            "a",
            None,
            &UploadOptions::default(),
            Some(&config),
            &cancel,
            &mut |_, _| {},
        )
        .unwrap_err();
    assert_eq!(err, GokapiError::FileTooLarge(Some(1)));
    assert!(m.seen.lock().unwrap().paths.is_empty());
}

#[test]
fn chunk_planning() {
    assert_eq!(
        chunks::plan(0, 10),
        vec![chunks::Chunk {
            offset: 0,
            length: 0
        }]
    );
    assert_eq!(chunks::plan(25, 10).len(), 3);
    let c = ServerConfig {
        max_file_size_mb: 3,
        max_chunk_size_mb: 50,
        end_to_end_encryption_enabled: false,
    };
    assert_eq!(
        chunks::chunk_size(Some(&c)),
        chunks::MIN_CHUNK,
        "mai sotto i 5 MB"
    );
    assert_eq!(chunks::chunk_size(None), 45 * MB);
    assert_eq!(encode_file_name("plain.txt"), "plain.txt");
    assert!(encode_file_name("base64:x").starts_with("base64:"));
    assert_eq!(encode_file_name("a\nb"), "a b");
}

#[test]
fn queue_uploads_and_records_history() {
    let m = mock(gokapi_handler(0));
    let dir = tempfile::tempdir().unwrap();
    let paths = Paths::at(dir.path());
    let servers = ServerStore::new(&paths, Arc::new(MemorySecrets::default()));
    let id = uuid::Uuid::new_v4();
    let server_url = m.url.as_str().trim_end_matches('/').to_string();
    servers
        .save(
            Server {
                id,
                name: "Test".into(),
                url: server_url,
                is_default: true,
            },
            Some(KEY),
        )
        .unwrap();
    let (tx, rx) = async_channel::unbounded();
    let mut engine = Engine::new(History::load(&paths), servers, tx);

    let folder = tempfile::tempdir().unwrap();
    std::fs::write(folder.path().join("a.txt"), b"hello").unwrap();
    std::fs::create_dir(folder.path().join("sub")).unwrap();
    std::fs::write(folder.path().join("sub/b.txt"), b"world").unwrap();
    std::fs::write(folder.path().join(".hidden"), b"x").unwrap();
    let ids = engine.enqueue(
        &[folder.path().to_path_buf()],
        id,
        &UploadOptions::default(),
    );
    assert_eq!(
        ids.len(),
        2,
        "la cartella diventa un upload per file, senza file nascosti"
    );

    let mut completed = 0;
    while !engine.is_idle() {
        let ev = rx.recv_blocking().unwrap();
        if let Some(Outcome::Completed(r)) = engine.handle(ev) {
            assert_eq!(r.url.as_deref(), Some("https://share.example/d?id=abc123"));
            completed += 1;
        }
    }
    assert_eq!(completed, 2);
    let reloaded = History::load(&paths);
    assert_eq!(
        reloaded
            .records
            .iter()
            .filter(|r| r.state == UploadState::Completed)
            .count(),
        2
    );
}
