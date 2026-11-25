# Roadmap

Priorità consigliata: **(1)** repository git + prima esecuzione CI → **(2)** brand coerente con le
icone scelte → **(3)** eliminazione con "Annulla" e modifica dei link → **(4)** test di integrazione
automatici sul Gokapi di test.

## 0.1 — fondamenta (fatto)
- [x] Brand: cesta geometrica, varianti, icone Android/macOS dall'illustrazione, palette, linee guida
- [x] Client Gokapi verificato (chunk, base64, errori tipizzati, retry, redirect bloccati)
- [x] Android: multi-server, Keystore, Share Sheet, coda in foreground service, notifiche, cronologia, file sul server, download
- [x] macOS: multi-server, Portachiavi, drag & drop (finestra, Dock, barra dei menu), Servizi Finder, cronologia, file sul server, download
- [x] Sincronizzazione della cronologia con il server (Android e macOS)
- [x] Test unitari e con server simulato su entrambe le piattaforme; workflow CI

## Progetto e distribuzione
- [ ] Inizializzare il repository git, pubblicarlo su GitHub ed eseguire la CI per la prima volta
- [ ] Dependabot (Gradle, GitHub Actions) per aggiornamenti e vulnerabilità delle dipendenze
- [ ] Release firmate: APK/AAB (keystore in CI), `Panaru.app` con Developer ID + notarizzazione
- [ ] Pubblicazione su F-Droid
- [ ] Screenshot nel README e sito di presentazione
- [ ] Aggiornare il README: sincronizzazione, nuove icone, funzioni recenti

## Brand
- [ ] Rendere coerenti logo, banner del README e palette con l'illustrazione scelta per le icone
      (decidere: logo ridisegnato dall'illustrazione; palette allineata all'arancio dell'icona)
- [ ] Ricostruzione vettoriale dell'illustrazione (oggi raster 1254 px) per stampa e grandi formati

## Test e qualità
- [ ] Test strumentati Android su emulatore in CI: Room (migrazioni), Keystore, Share Sheet
- [ ] Test UI: flussi Compose (Android) e drag & drop (macOS)
- [ ] Test di integrazione automatici contro un Gokapi reale (Docker o server di test dedicato)
- [ ] Matrice di compatibilità: Android 8–10, 13, 14, 15+; macOS 14 e successivi
- [ ] Misure di prestazioni: memoria con file da più GB, tempo di avvio, batteria in background

## Funzioni con l'API Gokapi (verificare ogni endpoint prima di implementare)
- [ ] **Eliminazione con "Annulla"**: `/files/delete` con `delay` + `/files/restore` (snackbar/notifica di alcuni secondi)
- [ ] **Modifica di un link esistente**: scadenza, download, password, prolunga (`/files/modify`, permesso EDIT)
- [ ] **Sostituire il file mantenendo il link** (`/files/replace`)
- [ ] **Duplicare un link con regole diverse** (`/files/duplicate`)
- [ ] **Link diretto alle immagini** (hotlink, `UrlHotlink`) da copiare per forum e documenti
- [ ] **Richieste di file (file request) di Gokapi** — non implementate in nessun client. Endpoint
      (permesso `MANAGE_FILE_REQUESTS`): `GET /uploadrequest/list`, `GET /uploadrequest/list/{id}`,
      `POST /uploadrequest/save` (crea/modifica), `DELETE /uploadrequest/delete`.
      Da fare: elenco, creazione/modifica/eliminazione, copia/condivisione del link pubblico,
      file ricevuti con download. La sincronizzazione oggi **esclude** i file arrivati tramite
      richieste (`/files/list` senza `showFileRequests: true`): mostrarli in una sezione dedicata.

## Funzioni client (entrambe le piattaforme)
- [ ] **Icona del tipo di file nella cronologia** (e nell'elenco "File sul server"), per capire a
      colpo d'occhio cos'è: immagine, video, PDF, archivio, documento, audio… ricavata dal MIME /
      estensione. Valutare l'anteprima (miniatura) per le immagini.
- [ ] **Aprire il link con un gesto rapido**: doppio clic su una voce su macOS apre il link nel
      browser; su Android tocco equivalente (es. doppio tocco) che apre il link nel browser.
      Attenzione su Android: oggi il tocco singolo copia il link e la pressione prolungata
      seleziona — definire i gesti senza conflitti.
- [ ] **"Svuota cronologia" deve conservare i file ancora sul server**: eliminare definitivamente
      tutte le voci (scadute, eliminate dal server, non riuscite, annullate) **tranne** quelle i cui
      file sono ancora presenti sul server, che restano visibili in cronologia. Oggi invece le voci
      ancora sul server vengono nascoste e quelle scadute restano nel database come nascoste.
      Prima di svuotare conviene sincronizzare, così lo stato "ancora sul server" è aggiornato;
      aggiornare il testo della conferma. Android (`UploadDao.clearHistory`) e macOS
      (`UploadQueue.clearHistory`). Già così su Linux (`History::clear`).
- [ ] **Download da link**: incollare un link Gokapi qualsiasi e scaricarlo nell'app
- [ ] **Condividere il file scaricato** dalla notifica / dall'elenco
- [ ] **Certificati self-signed**: opzione "fidati di questo certificato" per server (pinning dell'impronta)
- [ ] **QR code del link** per condividerlo di persona
- [ ] **Preset di condivisione** (es. "Lavoro: 7 giorni, 1 download, password")
- [ ] **Generatore di password** per l'upload, con copia separata della password
- [ ] **Avviso di scadenza** ("il link scade domani") con un tocco per prolungarlo
- [ ] **Ricerca e filtri** nella cronologia (server, attivi/scaduti, nome)
- [ ] **Blocco dell'app** con biometria (impronta / Touch ID)
- [ ] **Cartelle come archivio ZIP** (Gokapi non gestisce cartelle: un upload = un file).
      Alla scelta/drop di una cartella chiedere "Un solo ZIP" o "File separati" (con "Ricorda la
      scelta" e predefinito nelle impostazioni, proposta: ZIP). macOS: `NSFileCoordinator` con
      `.forUploading` (nessuna dipendenza); Android: `ACTION_OPEN_DOCUMENT_TREE` + `java.util.zip`.
      ZIP in cartella temporanea privata, stato "Compressione…", cancellato dopo l'upload; Riprova
      ricomprime dall'originale; errori chiari per spazio su disco e per ZIP oltre `MaxFilesize`.

## Android
- [ ] Riquadro nelle Impostazioni rapide (Quick Settings tile) per caricare al volo
- [ ] Widget con gli ultimi link
- [ ] Direct Share: condivisione diretta verso un server specifico dal menu Condividi
- [ ] Target SDK 36

## Linux
- [x] Prima versione: GTK 4 / libadwaita, funzioni principali, test, CI, `install.sh`
- [ ] Pacchetto **Flatpak** e pubblicazione su Flathub (manifest + sorgenti Cargo vendorizzate)
- [ ] Traduzioni con gettext (oggi solo italiano) — inglese come per Android
- [ ] Icona nell'area di notifica (StatusNotifierItem) con stato e link recenti, come la barra dei menu macOS
- [ ] Integrazione con il file manager: azione "Carica con Panaru" per Nautilus/Files, Dolphin, Thunar
- [ ] Provare su GNOME (Wayland) e KDE Plasma reali: appunti, notifiche, portachiavi, trascinamento

## macOS (sviluppo in pausa fino all'ok)
- [ ] Barra dei menu: trascinando un file sull'icona il riquadro deve aprirsi da solo per
      rilasciarci il file (oggi si apre solo con un clic)
- [ ] Coda di download multipli (oggi un file per clic)
- [ ] Scorciatoia globale e Quick Action
- [ ] Caricare dagli appunti (immagine o testo come file) e screenshot caricato al volo
- [ ] Share Extension (menu Condividi di sistema) e Finder Sync (richiedono progetto Xcode)

## Da valutare
- Upload cifrati end-to-end (richiede la logica di `gokapi-cli`: rivalutare un core condiviso)
- Kotlin Multiplatform, se la logica condivisa supera ~2000 righe (vedi ADR-1)
