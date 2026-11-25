# Compilare Panaru

## Android

Requisiti: JDK 17–21, Android SDK (platform 36), connessione per scaricare le dipendenze Gradle.

```bash
cd android
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # oppure ANDROID_HOME
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew test                      # test unitari (client Gokapi, coda, repository)
./gradlew :app:lintDebug            # analisi statica
```

Release firmata: crea un keystore (non versionato) e configura la firma in locale o in CI,
poi `./gradlew :app:assembleRelease` (R8 attivo, risorse ridotte).

Moduli:

- `:core:gokapi` — client API in Kotlin/JVM puro (OkHttp + kotlinx.serialization), testato con MockWebServer.
- `:core:data` — Room, Android Keystore, sorgenti `content://`, motore della coda.
- `:app` — UI Jetpack Compose, Share Sheet, servizio in foreground, notifiche.

## macOS

Requisiti: macOS 14+, Swift 5.10+ (bastano i **Command Line Tools**; Xcode è facoltativo).

```bash
cd macos
scripts/test.sh                     # test di PanaruKit (Swift Testing)
scripts/build-app.sh release        # → build/Panaru.app (firma ad-hoc)
open build/Panaru.app
```

Per la distribuzione: `CODESIGN_IDENTITY="Developer ID Application: …" scripts/build-app.sh`,
poi notarizzazione con `xcrun notarytool submit` e `xcrun stapler staple`.

Note sulla toolchain senza Xcode:

- `scripts/test.sh` passa esplicitamente il plugin delle macro di Swift Testing e, se il percorso
  contiene spazi, esegue i test da una copia temporanea.
- Le view usano `@LocalState` (equivalente di `@State`) perché il plugin `SwiftUIMacros` è
  distribuito solo con Xcode.

## Linux

Requisiti: Rust ≥ 1.85, GTK ≥ 4.14, libadwaita ≥ 1.5, libdbus-1 (pacchetti di sviluppo) e un
servizio Secret Service per le chiavi API (GNOME Keyring, KWallet, KeePassXC…).

```bash
# Debian/Ubuntu (24.04+):  sudo apt install libgtk-4-dev libadwaita-1-dev libdbus-1-dev
# Fedora (40+):            sudo dnf install gtk4-devel libadwaita-devel dbus-devel
cd linux
cargo test                          # client Gokapi, coda, cronologia (server simulato)
cargo run -- file1 file2            # avvia e carica (anche da "Apri con" del file manager)
./install.sh                        # binario, .desktop, icone e metainfo in ~/.local
```

Struttura: `src/gokapi` (client), `src/queue.rs` (coda), `src/history.rs` (cronologia e
sincronizzazione), `src/store.rs` (server, preferenze, portachiavi): libreria senza GTK, testabile
senza display. `src/ui` è l'interfaccia GTK 4 / libadwaita.

### Provarla da macOS o Windows (Docker)

Non serve una macchina Linux: `linux/dev/` crea un Ubuntu 24.04 con un desktop leggero visibile
nel browser e un Gokapi di test, con il solo comando `docker` (anche Colima o Docker Desktop).

```bash
linux/dev/up.sh            # avvia desktop + Gokapi (la prima volta costruisce l'immagine)
linux/dev/run.sh           # compila e apre Panaru nel desktop virtuale
linux/dev/run.sh --test    # esegue i test nel container
linux/dev/up.sh down       # ferma i container
```

- Desktop: <http://localhost:6080/vnc.html?autoconnect=1&resize=scale>
- Gokapi: <http://localhost:53842> (al primo avvio: setup, poi menu *API* → nuova chiave).
  In Panaru il server è `http://gokapi.internal:53842` (`.internal` = rete locale, HTTP ammesso).
- Nel desktop: Thunar (sorgente per il trascinamento), notifiche (dunst), portachiavi sbloccato.
- Con Colima su un disco esterno, la cartella del progetto va montata:
  `colima start --mount "$HOME:w" --mount "<percorso del progetto>:w"`.

## Asset del brand

```bash
python3 brand/tools/generate_brand.py   # rigenera gli SVG e le risorse vettoriali Android
python3 brand/tools/app_icons.py         # icone delle app (Android, macOS, Linux) dal logo
brand/tools/export.sh                    # PNG, ICNS, immagini template macOS (richiede macOS)
```
