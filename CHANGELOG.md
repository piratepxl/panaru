# Changelog

Formato: [Keep a Changelog](https://keepachangelog.com/it/1.1.0/) · Versionamento: [SemVer](https://semver.org/lang/it/).

## [Non rilasciato]
### Aggiunto
- **Linux** (Rust, GTK 4 / libadwaita, `linux/`): più server con verifica della connessione, API key nel portachiavi di sistema (Secret Service), upload a chunk in streaming con coda, annullamento e retry, opzioni (scadenza, download, password), trascinamento nella finestra e "Apri con", cartelle (un link per file), link copiato e notifiche, upload che continuano a finestra chiusa, cronologia sincronizzata con il server, eliminazione dal server, file sul server con download, "Svuota cronologia" che conserva i file ancora sul server. Test con server simulato, CI (`.github/workflows/linux.yml`), `install.sh`, file .desktop e metainfo. Ambiente di prova con Docker (`linux/dev/`): desktop Ubuntu nel browser + Gokapi di test.
- Android: sincronizzazione della cronologia con i file del server (`/files/list`, permesso View): i file caricati da altri dispositivi o dal web compaiono con l'etichetta "Caricato da un altro dispositivo"; sincronizzazione all'apertura (max ogni 30 s) e con il pulsante "Sincronizza con il server". Le voci rimosse dalla cronologia restano nascoste. Database v2 con migrazione.
- macOS: stessa sincronizzazione della cronologia (all'avvio, quando l'app torna attiva e con "Sincronizza"), compatibile con le cronologie già salvate.

### Modificato
- macOS: nuova interfaccia in stile Liquid Glass (macOS 26+, con ripiego sui materiali su macOS 14–15): finestra traslucida con bagliori del brand, toolbar unificata con wordmark, pulsanti di vetro, area di rilascio e righe della cronologia come pannelli di vetro, icona del tipo di file con badge di stato, barre di avanzamento sottili; stesso stile nel pannello della barra dei menu (link recenti con un clic). Stili centralizzati in `Glass.swift`.
- Palette: la terracotta lascia il posto all'arancio `#EC5F36` dell'icona, con varianti per contrasto AA (intenso `#CD3D13` per azioni e testo in chiaro, vivo `#EE724E` in scuro, tenue/profondo per superfici e testo). Aggiornati token, tema Android, colori macOS, notifiche, logo/favicon/banner generati e linee guida.
- Nuove icone condivise da Android e macOS, ricavate dal logo vettoriale `brand/logo/new/panaru.svg`: paniere bianco su arancio `#EC5F36` (stesse proporzioni sulle due piattaforme). Su Android l'icona adattiva, l'icona a tema, quella delle notifiche e il simbolo nell'interfaccia sono ora vettoriali; su macOS cambiano AppIcon e glifi della barra dei menu. Generatore unico: `brand/tools/app_icons.py` (gli script precedenti sono in `brand/tools/legacy/`).
- Android: icona dell'app dall'illustrazione ufficiale del paniere (con trama), ingrandita al massimo della zona visibile; icona a tema e icona notifiche derivate.
- Simbolo geometrico: corpo con trama a montanti verticali (intreccio sopra-sotto).

## [0.1.0] — 2026-11-21
### Aggiunto
- Identità visiva: simbolo "cesta" geometrica (con variante "paniere"), wordmark, palette, icone, linee guida.
- Icona macOS dall'illustrazione ufficiale del paniere e glifi template per la barra dei menu (normale e trasferimento).
- Android: gestione multi-server con API key nel Keystore, verifica connessione, Share Sheet
  (`ACTION_SEND`/`SEND_MULTIPLE`) senza copia dei file, coda di upload a chunk in servizio in
  foreground, notifiche con Copia/Condividi, copia automatica, cronologia con stato ed eliminazione,
  file sul server e download, scorciatoia dal launcher, tema chiaro/scuro, italiano/inglese.
- macOS: gestione multi-server con API key nel Portachiavi, drag & drop su finestra, Dock e barra
  dei menu, Servizi del Finder e "Apri con", cartelle, coda con concorrenza, notifiche,
  cronologia, file sul server e download.
- Documentazione (architettura, API, sicurezza, compilazione, test), CI GitHub Actions.
