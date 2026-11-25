#!/usr/bin/env bash
# Compila Panaru nel container e lo avvia sul desktop virtuale.
#   linux/dev/run.sh [file…]        build di debug + avvio (i file vengono aperti/caricati)
#   linux/dev/run.sh --test         esegue i test (cargo test)
set -euo pipefail
if [[ "${1:-}" == --test ]]; then
  exec docker exec -w /src/linux panaru-desktop bash -lc 'cargo test 2>&1'
fi
docker exec -w /src/linux panaru-desktop bash -lc '
  set -e; source /home/dev/.session-env
  cargo build 2>&1 | grep -Ev "^\s+(Compiling|Downloaded|Downloading)" || true
  test -x "$CARGO_TARGET_DIR/debug/panaru" || exit 1
  pkill -x panaru 2>/dev/null || true
  GSK_RENDERER=cairo setsid "$CARGO_TARGET_DIR/debug/panaru" '"$*"' >/tmp/panaru.log 2>&1 < /dev/null &
  sleep 1; echo "Panaru avviato (log: docker exec panaru-desktop cat /tmp/panaru.log)"'
