# Ambiente di sviluppo e prova di Panaru per Linux (Ubuntu 24.04 LTS: GTK 4.14, libadwaita 1.5).
# Desktop leggero (Openbox + Thunar) visibile nel browser via noVNC su http://localhost:6080.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive LANG=it_IT.UTF-8 LC_ALL=it_IT.UTF-8 TZ=Europe/Rome
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential pkg-config curl ca-certificates git locales tzdata \
      libgtk-4-dev libadwaita-1-dev libdbus-1-dev \
      xvfb x11vnc novnc websockify openbox tint2 thunar xterm xdotool dbus-x11 dunst \
      gnome-keyring libsecret-tools xdg-utils desktop-file-utils \
      adwaita-icon-theme librsvg2-common fonts-cantarell fonts-dejavu-core \
 && sed -i 's/# it_IT.UTF-8/it_IT.UTF-8/' /etc/locale.gen && locale-gen \
 && rm -rf /var/lib/apt/lists/*
RUN useradd -m -s /bin/bash dev
USER dev
ENV PATH=/home/dev/.cargo/bin:$PATH CARGO_TARGET_DIR=/home/dev/target
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable \
 && rustup component add clippy rustfmt \
 && mkdir -p /home/dev/.cargo/registry /home/dev/target
WORKDIR /src/linux
COPY --chown=dev:dev desktop.sh /home/dev/desktop.sh
EXPOSE 6080
CMD ["/home/dev/desktop.sh"]
