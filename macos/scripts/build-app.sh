#!/usr/bin/env bash
# Assembla Panaru.app dai prodotti di `swift build` (funziona con i soli Command Line Tools).
# Uso: scripts/build-app.sh [debug|release]   →   build/Panaru.app
# Firma: ad-hoc per impostazione predefinita; per la distribuzione impostare
#   CODESIGN_IDENTITY="Developer ID Application: …" (e poi notarizzare con notarytool).
set -euo pipefail
cd "$(dirname "$0")/.."
CONFIG="${1:-release}"
VERSION="${PANARU_VERSION:-0.1.0}"
BUILD="${PANARU_BUILD:-1}"

swift build -c "$CONFIG" --product Panaru
BIN="$(swift build -c "$CONFIG" --show-bin-path)/Panaru"

APP=build/Panaru.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/Panaru"
sed -e "s/__VERSION__/$VERSION/" -e "s/__BUILD__/$BUILD/" Resources/Info.plist > "$APP/Contents/Info.plist"
cp Resources/AppIcon.icns Resources/*.png "$APP/Contents/Resources/"
printf 'APPL????' > "$APP/Contents/PkgInfo"

IDENTITY="${CODESIGN_IDENTITY:--}"
codesign --force --options runtime --timestamp=none --sign "$IDENTITY" "$APP"
codesign --verify --strict "$APP"
echo "✔ $APP ($VERSION, firma: $IDENTITY)"
