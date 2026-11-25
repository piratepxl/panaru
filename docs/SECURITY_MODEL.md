# Sicurezza e privacy

## Principi

1. Panaru comunica **solo** con i server Gokapi configurati dall'utente.
2. Nessuna analitica, telemetria, crash reporting remoto o SDK di terze parti.
3. Le credenziali non sono mai salvate in chiaro né scritte nei log.

## Credenziali

| | Android | macOS |
|---|---|---|
| API key | AES-256-GCM con chiave generata nell'**Android Keystore** (non esportabile, hardware-backed dove disponibile); nel database Room solo ciphertext + IV | **Portachiavi** (`kSecClassGenericPassword`, `AfterFirstUnlock`) |
| Password dei file | Solo in memoria finché l'upload termina; mai su disco | Idem |
| Backup | Disattivato (`allowBackup=false`, regole di estrazione che escludono tutto): la chiave del Keystore non sarebbe comunque ripristinabile | Il Portachiavi segue le regole di macOS |

Una password di upload non viene conservata dopo un riavvio: se un upload protetto viene
interrotto, il retry è rifiutato e l'utente deve ricaricare il file (comportamento voluto).

## Rete

- **HTTPS obbligatorio** per indirizzi pubblici. `http://` è accettato solo per host di rete locale
  (RFC 1918, loopback, link-local, CGNAT/Tailscale `100.64/10`, ULA IPv6, `.local`, `.lan`,
  `.home.arpa`, `.internal`). Validazione in `ServerUrl.kt` / `ServerURL.swift`, coperta da test.
- **Redirect non seguiti**: un redirect potrebbe inoltrare l'header `apikey` a un altro host.
  Una risposta 3xx viene mostrata come errore.
- **Certificati**: si usano le CA di sistema. Su Android sono accettate anche le CA installate
  dall'utente, per supportare i server con CA privata. Nessun "accetta qualsiasi certificato".
- URL con credenziali incorporate (`https://user:pass@…`) vengono rifiutati.
- Sessioni HTTP effimere su macOS (nessuna cache o cookie su disco).

## Sorgenti dei file

- **Android:** lo Share Sheet accetta solo URI `content://`. Gli URI `file://` sono rifiutati
  perché potrebbero puntare ai dati privati di Panaru (database, preferenze) e farli caricare.
  I nomi forniti da altre app vengono ripuliti (separatori di percorso, caratteri di controllo).
- I file non vengono copiati: si leggono in streaming dalla sorgente. Unica eccezione: i
  provider che non dichiarano la dimensione, copiati nella cache privata ed eliminati a fine upload.
- **macOS:** l'app non è sandboxed (distribuzione fuori dal Mac App Store); legge solo i file
  che l'utente rilascia, sceglie o invia tramite Servizi. I chunk temporanei sono creati con
  permessi `0600` ed eliminati subito dopo l'invio.

## Log

Nessun log applicativo contiene URL con parametri, API key, password o contenuti dei file.

## Rischi residui (documentati)

| Rischio | Mitigazione / nota |
|---------|--------------------|
| Gokapi con cifratura end-to-end: gli upload via API sono salvati **in chiaro** (limite dell'API di Gokapi) | Avviso esplicito nella verifica del server |
| CA utente accettate su Android | Necessarie per i server con CA privata; un dispositivo compromesso con CA malevola potrebbe intercettare il traffico |
| HTTP in rete locale | Consentito solo per host locali e segnalato in UI; sconsigliato su reti non fidate |
| Password di upload in header HTTP | Limitazione dell'API Gokapi; il canale è comunque HTTPS |
| Link condivisi | Chiunque abbia il link può scaricare (entro scadenza/limite): usare password e limiti bassi per contenuti sensibili |
| App macOS firmata ad-hoc nelle build locali | Le release pubbliche vanno firmate con Developer ID e notarizzate |
| Appunti | Con "Copia automaticamente" il link finisce negli appunti di sistema (disattivabile) |

## Dipendenze

Android: OkHttp, kotlinx.serialization, kotlinx.coroutines, AndroidX (Compose, Room, Lifecycle,
Core). macOS: solo framework di sistema. Nessuna dipendenza di rete oltre a quelle elencate.
