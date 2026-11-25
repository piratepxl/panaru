#!/usr/bin/env bash
# Ambiente di prova di Panaru per Linux con il solo `docker` (nessun Compose necessario).
#   linux/dev/up.sh          crea/avvia rete, Gokapi di test e desktop Linux
#   linux/dev/up.sh down     ferma e rimuove i container (i volumi restano)
# Desktop:  http://localhost:6080/vnc.html?autoconnect=1&resize=scale
# Gokapi:   http://localhost:53842  (setup iniziale + API key; in Panaru: http://gokapi.internal:53842)
# Compila e avvia l'app:  linux/dev/run.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(cd ../.. && pwd)"
NET=panaru-dev

if [[ "${1:-}" == down ]]; then
  docker rm -f panaru-desktop panaru-gokapi >/dev/null 2>&1 || true
  echo "Container fermati."; exit 0
fi

docker network inspect $NET >/dev/null 2>&1 || docker network create $NET >/dev/null
docker image inspect panaru-linux-dev >/dev/null 2>&1 || docker build -t panaru-linux-dev .

if ! docker ps --format '{{.Names}}' | grep -qx panaru-gokapi; then
  docker rm -f panaru-gokapi >/dev/null 2>&1 || true
  # ".internal" è considerato rete locale da Panaru: lì l'HTTP senza TLS è ammesso.
  docker run -d --name panaru-gokapi --network $NET --network-alias gokapi.internal \
    -p 127.0.0.1:53842:53842 -e TZ=Europe/Rome -e GOKAPI_MAX_FILESIZE=2048 -e GOKAPI_CHUNK_SIZE_MB=10 \
    -v panaru-gokapi-data:/app/data -v panaru-gokapi-config:/app/config f0rc3/gokapi:latest >/dev/null
fi

if ! docker ps --format '{{.Names}}' | grep -qx panaru-desktop; then
  docker rm -f panaru-desktop >/dev/null 2>&1 || true
  docker run -d --name panaru-desktop --network $NET --shm-size 512m \
    -p 127.0.0.1:6080:6080 -v "$ROOT:/src" \
    -v panaru-cargo:/home/dev/.cargo/registry -v panaru-target:/home/dev/target panaru-linux-dev >/dev/null
fi
echo "Desktop: http://localhost:6080/vnc.html?autoconnect=1&resize=scale"
echo "Gokapi:  http://localhost:53842"
