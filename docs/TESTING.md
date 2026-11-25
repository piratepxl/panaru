# Test

## Automatici

| Area | Android (`./gradlew test`) | macOS (`scripts/test.sh`) |
|------|---------------------------|---------------------------|
| Validazione URL / HTTPS / rete locale | `ServerUrlTest` | `ServerURLTests` |
| Chunking e limiti del server | `ChunkPlannerTest` | `ServerURLTests.chunkPlanning` |
| Nomi file non ASCII (base64), password ASCII | `ChunkPlannerTest` | `fileNameEncoding` |
| Autenticazione (header `apikey`, 401) | `GokapiClientTest` | `GokapiClientTests` |
| Upload singolo e a più chunk (byte verificati) | `GokapiClientTest` | `GokapiClientTests` |
| Retry su 5xx/rete, nessun retry su 4xx | `GokapiClientTest` | `retriesServerErrorsButNotClientErrors` |
| Errori di rete, TLS, protocollo, redirect bloccati | `GokapiClientTest` | `GokapiClientTests` |
| Annullamento | `cancellation aborts the request` | `cancelRetryAndRefresh` |
| Elenco, eliminazione, download | `GokapiClientTest` | `listDeleteAndProtocolErrors` |
| Coda: multi-server, concorrenza, opzioni | `UploadEngineTest` | `UploadQueueTests` |
| Password mai persistite | `UploadEngineTest` | `queueUploadsToRightServersWithConcurrencyLimit` |
| Sorgente non più leggibile + retry | `UploadEngineTest` | `wrongKeyAndMissingFileFail` |
| Upload interrotti al riavvio | `interrupted uploads are recovered as failed` | `UploadQueue.init` |
| Cronologia: aggiornamento stato ed eliminazione | `UploadEngineTest` | `cancelRetryAndRefresh` |
| Sincronizzazione: import da altri dispositivi, niente duplicati, voci nascoste, permesso View | `UploadEngineTest` | `syncImportsFilesFromOtherDevices`, `syncWithoutViewPermission` |
| Credenziali (Keychain) | — (Keystore: vedi sotto) | `KeychainTests` |

**Linux** (`cd linux && cargo test`, oppure `linux/dev/run.sh --test`): validazione URL,
chunking, nomi base64, 401, upload a più chunk con verifica dei byte e degli header, retry su 5xx e
non su 4xx, redirect non seguiti, limite di dimensione, coda con cartelle e cronologia salvata,
sincronizzazione (import, nessun duplicato, voci nascoste) e "Svuota cronologia" che conserva i file
ancora sul server. Prova manuale nel desktop virtuale: vedi [BUILDING.md](BUILDING.md#linux).

I test usano un server Gokapi simulato (MockWebServer / URLProtocol) che riproduce il formato
reale di richieste e risposte documentato in [GOKAPI_API.md](GOKAPI_API.md).

## Integrazione con un Gokapi reale

```bash
docker compose -f testenv/docker-compose.yml up -d
```

Apri `http://localhost:53842`, completa il setup, crea una API key con i permessi
Upload/View/Delete/Download. Dall'emulatore Android il server è `http://10.0.2.2:53842`;
su macOS `http://localhost:53842`. **Mai usare un server con dati reali per i test.**

Checklist manuale (da eseguire a ogni release):

- [ ] Android: Condividi 1 foto da Galleria → link negli appunti, notifica con Copia/Condividi.
- [ ] Android: Condividi 5 file insieme (`SEND_MULTIPLE`) → 5 link, condivisione multipla.
- [ ] Android: file > 200 MB, schermo spento → upload completato (servizio in foreground).
- [ ] Android: chiudere il foglio dello Share Sheet durante l'upload → upload completato.
- [ ] Android: server con chiave errata → messaggio "Chiave API rifiutata".
- [ ] Android: Keystore — riavvio del dispositivo, la chiave resta utilizzabile.
- [ ] macOS: drag & drop in finestra, su Dock, nella barra dei menu; Servizi dal Finder.
- [ ] macOS: cartella con sottocartelle → un link per file.
- [ ] Linux: trascina da Thunar/Files, "Apri con → Panaru", file > 200 MB, chiusura della finestra durante l'upload (continua in background), tema scuro, chiave nel portachiavi dopo il riavvio della sessione.
- [ ] Tutte: annulla, riprova, elimina dal server, aggiorna stato, modalità scura.
