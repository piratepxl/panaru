# Gokapi API — note verificate

Questo documento raccoglie **solo** il comportamento dell'API Gokapi che Panaru utilizza,
verificato su `openapi.json` e sul codice sorgente di Gokapi
(`internal/webserver/api/*.go`, `internal/storage/chunking/Chunking.go`,
`internal/webserver/fileupload/FileUpload.go`), versione analizzata `2.2.5-dev`
(commit `2173a44`, 19/11/2026).

Ogni modifica al client che tocca l'API deve essere riverificata qui.

## Base e autenticazione

- Tutti gli endpoint sono sotto `<server>/api/` (`mux.HandleFunc("/api/", processApi)`).
- Autenticazione: header `apikey: <chiave>`. Le chiavi vengono generate dall'interfaccia web di
  Gokapi (menu *API*) e hanno permessi granulari.
- Tentativi con chiavi errate sono rallentati da un rate limiter lato server
  (`ratelimiter.WaitOnApiAuthentication`): il client non deve fare retry aggressivi sui 401.
- Errori: corpo JSON `{"Result":"error","ErrorMessage":"…","ErrorCode":<int>}` con status HTTP
  coerente (400, 401, 404, …). Un 401 può anche avere corpo vuoto.

## Permessi delle API key usati da Panaru

| Permesso   | Serve per                                   |
|------------|---------------------------------------------|
| `UPLOAD`   | `/info/config`, `/chunk/add`, `/chunk/complete` (obbligatorio) |
| `VIEW`     | `/files/list`, `/files/list/{id}` (opzionale: stato file sul server) |
| `DELETE`   | `/files/delete` (opzionale: eliminazione)   |
| `DOWNLOAD` | `/files/download/{id}` (opzionale: download via API) |

Panaru rileva i permessi mancanti dai 401 e disattiva la funzione relativa spiegandolo all'utente,
invece di mostrare un errore generico.

## Endpoint usati

### `GET /api/info/version`
Qualsiasi chiave valida. Risposta: `{"Version":"2.2.5-dev","VersionInt":20205}`.
Usato da "Verifica connessione".

### `GET /api/info/config` — permesso `UPLOAD`
Risposta: `{"MaxFilesize":<MB>,"MaxChunksize":<MB>,"EndToEndEncryptionEnabled":<bool>}`.
- `MaxFilesize` e `MaxChunksize` sono in **megabyte**.
- Se `EndToEndEncryptionEnabled` è `true`, la documentazione avverte che gli upload via API
  **non** sono cifrati end-to-end e vengono salvati in chiaro: Panaru lo mostra in modo esplicito
  nella scheda del server.

### `POST /api/chunk/add` — permesso `UPLOAD`
`multipart/form-data` con i campi:
- `uuid` — stesso valore per tutti i chunk di un file, **almeno 10 caratteri**; il server rimuove
  i caratteri fuori da `[a-zA-Z0-9-]`.
- `filesize` — dimensione totale del file in byte.
- `offset` — offset del chunk in byte.
- `file` — i byte del chunk.

Vincoli lato server:
- Ogni chunk deve essere **≥ 5 MB** (≥ 1 MB se `ChunkSize` del server < 5), salvo l'ultimo.
- La `Content-Length` della richiesta non può superare `MaxFilesize`.
- Il caricamento parallelo dei chunk è supportato.

Risposta: `{"result":"OK"}`.

### `POST /api/chunk/complete` — permesso `UPLOAD`
Parametri come **header**:
- `uuid` (obbligatorio), `filesize` (obbligatorio, byte),
- `filename` (obbligatorio). Per nomi non ASCII: `base64:<base64 del nome UTF-8>`
  (supportato da Gokapi **v2.0.1**; Panaru lo usa sempre per i nomi non ASCII),
- `contenttype` (opzionale, default `application/octet-stream`),
- `allowedDownloads` (opzionale): assente → 1, `0` → illimitati,
- `expiryDays` (opzionale): assente → 14, `0` → illimitato,
- `password` (opzionale),
- `nonblocking` (opzionale): Panaru **non** lo usa, perché in modalità non bloccante la risposta
  è solo `{"result":"OK"}` e non contiene il link.

Risposta: `UploadResult` = `{"Result":"OK","FileInfo":{File},"IncludeFilename":bool}`.
In caso di errore il server cancella i chunk caricati: il retry riparte da zero con un nuovo uuid.

> Gli upload **non sono riprendibili**: non esiste un endpoint per interrogare i chunk già ricevuti.
> Panaru riprova un singolo chunk fallito per errori di rete, ma dopo un'interruzione del processo
> l'upload riparte dall'inizio.

### `GET /api/files/list` — permesso `VIEW`
Elenco dei file non scaduti dell'utente; restituisce `null` se non ci sono file.
Panaru lo usa per **sincronizzare la cronologia**: i file assenti in locale (caricati da altri
client o dal web) vengono importati, quelli locali non più elencati sono segnati come scaduti o
eliminati. I file con `IsPendingDeletion` vengono ignorati. Senza permesso VIEW la
sincronizzazione non è disponibile (401) e l'app lo spiega all'utente.

### `GET /api/files/list/{id}` — permesso `VIEW`
Metadati di un file; 404 se l'ID non esiste o il file è scaduto. Panaru lo usa per aggiornare lo
stato delle voci di cronologia (download rimanenti, scaduto).

### `DELETE /api/files/delete` — permesso `DELETE`
Header `id` (obbligatorio), `delay` (secondi, opzionale). Panaru elimina immediatamente
(nessun `delay`), dopo conferma dell'utente.

### `GET /api/files/download/{id}` — permesso `DOWNLOAD`
Scarica il file senza incrementare il contatore (salvo header `increaseCounter: true`).
I file cifrati end-to-end o cifrati su storage cloud non sono scaricabili via API (400).

## Oggetto `File` (campi usati)

`Id`, `Name`, `SizeBytes`, `ContentType`, `UrlDownload`, `UrlHotlink`, `HotlinkId`, `UploadDate`
(unix s), `ExpireAt` (unix s), `UnlimitedTime`, `UnlimitedDownloads`, `DownloadsRemaining`,
`DownloadCount`, `IsPasswordProtected`, `IsEndToEndEncrypted`, `IsPendingDeletion`.

## Funzioni non disponibili via API (quindi non promesse da Panaru)

- Upload di **cartelle**: non esiste. Panaru (macOS) può caricare i file contenuti in una cartella
  uno per uno — non crea un archivio lato server.
- **Upload riprendibili** dopo interruzione.
- Upload **cifrati end-to-end** via API (richiedono la logica di `gokapi-cli`; fuori ambito per ora).
