#!/usr/bin/env bash
# Avvia un desktop X11 virtuale (1280×800) con Openbox, pannello, notifiche e portachiavi,
# esposto via noVNC. Poi resta in attesa: l'app si lancia con `docker compose exec`.
set -euo pipefail
export DISPLAY=:1
Xvfb :1 -screen 0 1280x800x24 -nolisten tcp &
sleep 1
eval "$(dbus-launch --sh-syntax)"
printf 'export DISPLAY=%s DBUS_SESSION_BUS_ADDRESS=%s\n' "$DISPLAY" "$DBUS_SESSION_BUS_ADDRESS" > /home/dev/.session-env
# Portachiavi (Secret Service) sbloccato con una password di prova: solo per questo container.
echo -n "panaru-dev" | gnome-keyring-daemon --unlock --components=secrets >/dev/null
gnome-keyring-daemon --start --components=secrets >/dev/null
openbox-session &
tint2 >/dev/null 2>&1 &
dunst >/dev/null 2>&1 &
mkdir -p /home/dev/Scrivania && cp -n /etc/hostname /home/dev/Scrivania/prova.txt 2>/dev/null || true
thunar /home/dev/Scrivania >/dev/null 2>&1 &
x11vnc -display :1 -forever -shared -nopw -quiet -rfbport 5900 >/dev/null 2>&1 &
exec websockify --web /usr/share/novnc 6080 localhost:5900
