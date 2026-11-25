#!/usr/bin/env bash
# Compila e installa Panaru per l'utente corrente (~/.local), senza privilegi di root.
#   linux/install.sh            compila e installa
#   linux/install.sh --prebuilt installa il binario incluso nel pacchetto di release
#   linux/install.sh uninstall  rimuove
# Requisiti di build: Rust ≥ 1.85, GTK ≥ 4.14, libadwaita ≥ 1.5, libdbus-1 (pacchetti -dev).
set -euo pipefail
cd "$(dirname "$0")"
PREFIX="${PREFIX:-$HOME/.local}"
ID=app.panaru.Panaru
if [[ "${1:-}" == uninstall ]]; then
  rm -f "$PREFIX/bin/panaru" "$PREFIX/share/applications/$ID.desktop" "$PREFIX/share/metainfo/$ID.metainfo.xml" \
        "$PREFIX/share/icons/hicolor/scalable/apps/$ID.svg" "$PREFIX/share/icons/hicolor/symbolic/apps/$ID-symbolic.svg"
  echo "Panaru rimosso da $PREFIX"; exit 0
fi
# --prebuilt: usa il binario già compilato del pacchetto di release (niente Rust necessario).
if [[ "${1:-}" != --prebuilt ]]; then cargo build --release; fi
TARGET="${CARGO_TARGET_DIR:-target}/release/panaru"
install -Dm755 "$TARGET" "$PREFIX/bin/panaru"
install -Dm644 data/$ID.desktop "$PREFIX/share/applications/$ID.desktop"
install -Dm644 data/$ID.metainfo.xml "$PREFIX/share/metainfo/$ID.metainfo.xml"
install -Dm644 data/icons/hicolor/scalable/apps/$ID.svg "$PREFIX/share/icons/hicolor/scalable/apps/$ID.svg"
install -Dm644 data/icons/hicolor/symbolic/apps/$ID-symbolic.svg "$PREFIX/share/icons/hicolor/symbolic/apps/$ID-symbolic.svg"
command -v update-desktop-database >/dev/null && update-desktop-database "$PREFIX/share/applications" || true
command -v gtk4-update-icon-cache >/dev/null && gtk4-update-icon-cache -qtf "$PREFIX/share/icons/hicolor" || true
echo "Panaru installato in $PREFIX (assicurati che $PREFIX/bin sia nel PATH)"
