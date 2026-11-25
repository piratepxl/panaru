#!/usr/bin/env bash
# Esporta gli asset raster di Panaru dagli SVG sorgente (richiede macOS 14+ e swiftc).
set -euo pipefail
cd "$(dirname "$0")/../.."
BRAND=brand
OUT=$BRAND/exports
BIN=$(mktemp -d)/rasterize
swiftc -O $BRAND/tools/rasterize.swift -o "$BIN"
python3 -I $BRAND/tools/generate_brand.py >/dev/null
mkdir -p $OUT/logo $OUT/android $OUT/macos $OUT/web
r() { "$BIN" "$1" "$2" "$3"; echo "• $2"; }

for n in panaru-logo panaru-logo-on-dark panaru-horizontal panaru-horizontal-on-dark panaru-symbol; do
  r $BRAND/logo/$n.svg $OUT/logo/$n@1024.png 1024
  r $BRAND/logo/$n.svg $OUT/logo/$n@512.png 512
done


# Icone delle app (macOS e Android) dal logo brand/logo/new/panaru.svg: paniere bianco su arancio.
# Genera anche i drawable Android, l'icona Google Play e i glifi della barra dei menu.
SET="${BIN%/*}/AppIcon.iconset"; rm -rf "$SET"
python3 $BRAND/tools/app_icons.py "$BIN" "$SET"
iconutil -c icns "$SET" -o $OUT/macos/AppIcon.icns && echo "• $OUT/macos/AppIcon.icns"
cp $OUT/macos/AppIcon.icns macos/Resources/AppIcon.icns
r $BRAND/icons/macos/menubar/panaru-menubar.svg macos/Resources/MenuBarIcon.png 18
r $BRAND/icons/macos/menubar/panaru-menubar.svg macos/Resources/MenuBarIcon@2x.png 36
r $BRAND/icons/macos/menubar/panaru-menubar-active.svg macos/Resources/MenuBarIconActive.png 18
r $BRAND/icons/macos/menubar/panaru-menubar-active.svg macos/Resources/MenuBarIconActive@2x.png 36

# macOS: wordmark come immagine template (nera con alfa, tinta da SwiftUI).
sed 's/stroke="#24211E"/stroke="#000000"/' $BRAND/logo/panaru-horizontal.svg > "${BIN%/*}/h.svg"
python3 -I - "${BIN%/*}/h.svg" "${BIN%/*}/wm.svg" <<'PY'
import re, sys
src = open(sys.argv[1]).read()
# Solo il wordmark: rimuove il gruppo del simbolo e adatta il viewBox.
src = re.sub(r'  <g id="simbolo".*?</g>\n', '', src, flags=re.S)
m = re.search(r'<g id="wordmark"[^>]*translate\(([\d.]+) ', src)
tx = float(m.group(1))
src = re.sub(r'viewBox="[^"]+" width="[^"]+" height="[^"]+"', f'viewBox="{tx-6} -6 334 84" width="334" height="84"', src)
open(sys.argv[2], "w").write(src)
PY
r "${BIN%/*}/wm.svg" macos/Resources/Wordmark.png 100
r "${BIN%/*}/wm.svg" macos/Resources/Wordmark@2x.png 200

# Web
r $BRAND/icons/web/favicon.svg $OUT/web/favicon-32.png 32
r $BRAND/icons/web/panaru-app-icon.svg $OUT/web/apple-touch-icon-180.png 180
r $BRAND/icons/web/readme-banner.svg $OUT/web/readme-banner.png 1280
cp $BRAND/icons/web/favicon.svg $OUT/web/favicon.svg
