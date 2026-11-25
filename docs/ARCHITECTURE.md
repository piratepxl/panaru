# Panaru — Documento di progettazione e architettura

> *Gokapi deve diventare invisibile. L'utente deve pensare soltanto a condividere il proprio file.*

## 1. Stato iniziale

La cartella di lavoro era vuota: nessun codice, risorsa o configurazione preesistente.
Il progetto parte quindi da zero; le uniche fonti di verità esterne sono il repository e la
documentazione di Gokapi (vedi [GOKAPI_API.md](GOKAPI_API.md)).

## 2. Decisioni architetturali (ADR sintetici)

### ADR-1 — Due app native, nessun framework multipiattaforma
**Decisione:** Android in Kotlin + Jetpack Compose; macOS in Swift + SwiftUI/AppKit.
Nessun Kotlin Multiplatform.

**Motivazione.** La parte condivisibile tra le due app è piccola: un client HTTP con 6 endpoint,
qualche modello JSON e l'algoritmo di chunking (~400 righe). Il valore di Panaru sta invece nelle
integrazioni native (Share Sheet, servizi in foreground, Keystore su Android; Finder, Dock,
Menu Bar, Servizi, Keychain su macOS), che non sono condivisibili. KMP richiederebbe Xcode +
Gradle + bridging Swift/Kotlin, toolchain più pesanti e build più fragili per risparmiare poche
centinaia di righe. La coerenza viene garantita da:
- un **contratto API unico** documentato e verificato ([GOKAPI_API.md](GOKAPI_API.md));
- **test speculari** sui due client (stessi casi: chunking, header, errori, base64 dei nomi);
- **design token** generati da un'unica fonte (`brand/colors/tokens.json`).

Rivalutare KMP se la logica condivisa supera ~2000 righe (es. cifratura end-to-end).

### ADR-2 — Upload sempre a chunk
Panaru usa sempre `/chunk/add` + `/chunk/complete`, anche per file piccoli: funziona dietro reverse
proxy con limiti di dimensione (Cloudflare, nginx), permette progresso preciso e retry per chunk.
Dimensione chunk = `MaxChunksize` del server (fallback 45 MB se `/info/config` non è disponibile),
mai sotto 5 MB tranne l'ultimo. Lettura in streaming: un chunk alla volta in memoria al massimo
come buffer di copia (64 KB), mai il file intero.

### ADR-3 — Cronologia locale
Nessun servizio cloud. Android: Room (SQLite). macOS: file JSON atomico in
`~/Library/Application Support/Panaru/` (volumi contenuti, nessuna dipendenza).

### ADR-4 — Credenziali
- Android: chiave AES-256-GCM generata nell'**Android Keystore** (non esportabile); in Room viene
  salvato solo il ciphertext dell'API key + IV.
- macOS: API key nel **Keychain** (`kSecClassGenericPassword`, `kSecAttrAccessibleAfterFirstUnlock`),
  mai in `UserDefaults` o nel JSON.

### ADR-5 — Android: Foreground Service invece di WorkManager per gli upload condivisi
Gli URI `content://` ricevuti dallo Share Sheet hanno un permesso di lettura **temporaneo** legato
al componente che li riceve. Passando gli URI al servizio con `FLAG_GRANT_READ_URI_PERMISSION`
(via `ClipData`), il permesso resta valido per tutta la vita del servizio: così il file **non va
copiato** in memoria locale. WorkManager non riceve grant di URI e obbligherebbe a copiare i file.
Il servizio è di tipo `dataSync` (Android 14+) e gestisce `onTimeout` (Android 15).
I file scelti dal selettore interno usano `ACTION_OPEN_DOCUMENT` + permesso persistente, quindi il
retry funziona anche dopo il riavvio dell'app.

### ADR-6 — macOS: Swift Package + bundle assemblato da script
L'app è un Swift Package (`PanaruKit` libreria + `Panaru` eseguibile) costruibile con i soli
Command Line Tools (`swift build`); lo script `macos/scripts/build-app.sh` assembla il bundle
`.app` (Info.plist, icona, firma ad-hoc). Questo rende la CI e la contribuzione più semplici.
Le integrazioni che richiedono **App Extension** (Finder Sync, Share Extension) richiedono un
progetto Xcode: sono pianificate (vedi Roadmap). L'integrazione Finder disponibile da subito è il
menu **Servizi** (clic destro → Servizi → "Carica con Panaru"), che non richiede estensioni.

## 3. Struttura del repository

```
panaru/
├── android/                 App Android (Gradle)
│   ├── core/gokapi/         Client API Gokapi — Kotlin/JVM puro, testabile senza Android
│   ├── core/data/           Room, Keystore, repository, motore della coda
│   └── app/                 UI Compose, Share Sheet, servizio upload, notifiche
├── macos/                   App macOS (Swift Package)
│   ├── Sources/PanaruKit/   Client API, coda, Keychain, cronologia
│   ├── Sources/Panaru/      UI SwiftUI, Menu Bar, Dock, Servizi
│   ├── Tests/PanaruKitTests
│   └── scripts/build-app.sh
├── brand/                   Identità visiva (logo, icone, colori, linee guida, export)
├── docs/                    Architettura, API, sicurezza, roadmap
├── testenv/                 Gokapi di test in Docker (mai dati reali)
└── .github/                 CI, template issue/PR
```

## 4. Moduli Android

| Modulo         | Tipo            | Responsabilità                                                  |
|----------------|-----------------|-----------------------------------------------------------------|
| `:core:gokapi` | Kotlin/JVM      | `GokapiClient` (OkHttp), modelli `kotlinx.serialization`, `ChunkPlanner`, errori tipizzati |
| `:core:data`   | Android library | `ServerRepository`, `SecretBox` (Keystore), Room (`servers`, `uploads`), `UploadQueue` |
| `:app`         | Application     | Activity principale, `ShareActivity`, `UploadService`, UI Compose, tema Panaru |

Flusso Share Sheet:
```
App esterna ─ACTION_SEND(_MULTIPLE)→ ShareActivity
  → risolve nome/dimensione/MIME (OpenableColumns)
  → inserisce N voci in coda (Room) sul server predefinito
  → startForegroundService(UploadService, ClipData + FLAG_GRANT_READ_URI_PERMISSION)
  → mostra il foglio di avanzamento; può essere chiuso: l'upload continua
UploadService → UploadQueue (max N concorrenti) → GokapiClient.upload(...)
  → notifica di avanzamento → notifica "Link pronto" (Copia / Condividi)
  → copia automatica negli appunti (preferenza)
```

## 5. Moduli macOS

| Target        | Responsabilità                                                       |
|---------------|----------------------------------------------------------------------|
| `PanaruKit`   | `GokapiClient` (URLSession, upload da file con `FileHandle`), `ChunkPlanner`, `UploadQueue` (actor), `KeychainStore`, `ServerStore`, `HistoryStore` |
| `Panaru`      | `App` SwiftUI, finestra principale con drop zone, `MenuBarExtra`, `AppDelegate` (drop sull'icona del Dock, menu Servizi), notifiche |

## 6. Modello dati (comune)

```
Server   { id, name, baseUrl, isDefault, createdAt }          + API key nel secure store
Upload   { id, serverId, fileName, sizeBytes, mimeType, source,
           state: queued|uploading|completed|failed|cancelled,
           bytesSent, error?, fileId?, url?, hotlinkUrl?, expiresAt?,
           unlimitedTime, downloadsRemaining?, unlimitedDownloads,
           passwordProtected, createdAt, completedAt? }
UploadOptions { expiryDays (0 = illimitato), allowedDownloads (0 = illimitati), password? }
```
Ogni upload è associato al server da cui è stato caricato (multi-server).

## 7. Gestione degli errori

Errori tipizzati in entrambi i client: `InvalidUrl`, `InsecureUrl`, `Unauthorized` (chiave errata
o permesso mancante), `NotFound`, `FileTooLarge(maxMb)`, `Server(code, message)`, `Network`,
`TlsFailure`, `Cancelled`, `SourceUnavailable` (Android: permesso URI perso).
Ogni errore ha un messaggio comprensibile in italiano e un'indicazione su cosa fare.
Retry automatico: 3 tentativi per chunk con backoff esponenziale solo per errori di rete/5xx;
mai per 4xx.

## 8. Design system

Token unici in `brand/colors/tokens.json` → tradotti in `Color.kt` (Compose) e
`PanaruColors.swift` (SwiftUI). Tipografia: Android usa la font di sistema (Roboto Flex/Google Sans
dove presente) con scala Material 3; macOS usa SF Pro. Il wordmark è disegnato a mano in vettoriale
e non dipende da font. Dettagli in [brand/guidelines/BRAND_GUIDELINES.md](../brand/guidelines/BRAND_GUIDELINES.md).

## 9. Versioni supportate

- Android 8.0 (API 26) → Android 15 (API 35, target).
- macOS 14 Sonoma o successivo (MenuBarExtra, `Observable`, SwiftUI moderno).
- Gokapi ≥ 2.0.1 (nomi file base64 in `/chunk/complete`). Versioni precedenti: avviso in
  "Verifica connessione".
