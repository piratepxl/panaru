# Contribuire a Panaru

Grazie! Alcune regole per mantenere il progetto coerente.

1. **Non inventare funzionalità di Gokapi.** Ogni uso dell'API va verificato su `openapi.json` e sui
   sorgenti, e documentato in [docs/GOKAPI_API.md](docs/GOKAPI_API.md).
2. **Parità tra piattaforme.** Una modifica al client API va fatta (e testata) sia in
   `android/core/gokapi` sia in `macos/Sources/PanaruKit`.
3. **Niente dipendenze superflue**, niente analitica o servizi esterni.
4. **Niente funzioni finte**: nessun pulsante per funzioni non implementate.
5. **Brand:** il simbolo è la cesta geometrica; gli asset si modificano dal generatore
   (`brand/tools`), non a mano.
6. Test verdi (`./gradlew test lintDebug`, `macos/scripts/test.sh`) prima della PR; aggiorna
   CHANGELOG e documentazione.
7. Non includere mai credenziali, indirizzi privati o dati personali.

Commit in stile convenzionale (`feat:`, `fix:`, `docs:`…). Testi dell'interfaccia: italiano e inglese.
