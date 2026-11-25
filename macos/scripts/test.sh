#!/usr/bin/env bash
# Esegue i test di PanaruKit (Swift Testing).
# Con i soli Command Line Tools (senza Xcode) SwiftPM non sempre passa al compilatore il plugin
# delle macro di Swift Testing: lo script lo indica esplicitamente. Inoltre, se il percorso
# contiene spazi, i sorgenti vengono copiati in una cartella temporanea prima dei test.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ "$PWD" == *" "* ]]; then
  WORK="${TMPDIR:-/tmp}/panaru-macos-tests"
  mkdir -p "$WORK"
  rsync -a --delete --exclude .build --exclude build ./ "$WORK/"
  cd "$WORK"
fi
EXTRA=()
DEV="$(xcode-select -p 2>/dev/null || true)"
if [[ "$DEV" == *CommandLineTools* && -d "$DEV/usr/lib/swift/host/plugins/testing" ]]; then
  EXTRA=(-Xswiftc -external-plugin-path -Xswiftc "$DEV/usr/lib/swift/host/plugins/testing#$DEV/usr/bin/swift-plugin-server")
fi
# I test condividono un server Gokapi simulato (URLProtocol): vanno eseguiti in sequenza.
swift test --no-parallel ${EXTRA[@]+"${EXTRA[@]}"} "$@"
