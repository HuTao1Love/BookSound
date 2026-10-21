#!/bin/sh
# Installs or updates the BookSound server in a Debian/Ubuntu LXC container (or any systemd machine).
#
#   curl -fsSL https://raw.githubusercontent.com/HuTao1Love/BookSound/main/deploy/lxc/install.sh | sh
#   sh install.sh /path/to/booksound-server.tar.gz      # a local archive instead of the latest release
#
# Data lives in /var/lib/booksound (library, database, covers); settings in /etc/booksound.env.
# Running it again updates the program and keeps the data.
set -eu

REPO="HuTao1Love/BookSound"
PREFIX=/opt/booksound
DATA=/var/lib/booksound
UNIT_URL="https://raw.githubusercontent.com/$REPO/main/deploy/lxc/booksound.service"

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root." >&2
    exit 1
fi

echo "Installing Java and ffmpeg..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ffmpeg curl ca-certificates gnupg >/dev/null
# The server needs Java 21 or newer. Distributions without it (Debian 12 ships 17) get Eclipse Temurin.
JAVA=
for candidate in openjdk-25-jre-headless openjdk-21-jre-headless temurin-25-jre temurin-21-jre; do
    if apt-cache show "$candidate" >/dev/null 2>&1; then JAVA=$candidate; break; fi
done
if [ -z "$JAVA" ]; then
    echo "Adding the Eclipse Temurin repository (packages.adoptium.net)..."
    CODENAME=$(. /etc/os-release && echo "${VERSION_CODENAME:-}")
    if [ -z "$CODENAME" ]; then
        echo "Can't tell the distribution release; install Java 21+ and run this again." >&2
        exit 1
    fi
    curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $CODENAME main" \
        > /etc/apt/sources.list.d/adoptium.list
    apt-get update -qq
    JAVA=temurin-21-jre
fi
apt-get install -y -qq "$JAVA" >/dev/null

if ! id booksound >/dev/null 2>&1; then
    useradd --system --home-dir "$DATA" --shell /usr/sbin/nologin booksound
fi
mkdir -p "$DATA"
chown booksound:booksound "$DATA"

ARCHIVE=${1:-}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
if [ -z "$ARCHIVE" ]; then
    echo "Downloading the latest release..."
    URL=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
        | grep -o '"browser_download_url": *"[^"]*BookSound-Server-[^"]*\.tar\.gz"' | head -n 1 | cut -d '"' -f 4)
    if [ -z "$URL" ]; then
        echo "The latest release has no server archive." >&2
        exit 1
    fi
    ARCHIVE="$TMP/server.tar.gz"
    curl -fsSL -o "$ARCHIVE" "$URL"
fi

echo "Installing to $PREFIX..."
tar -xzf "$ARCHIVE" -C "$TMP"
SOURCE=$(find "$TMP" -maxdepth 2 -type d -name 'booksound-server*' | head -n 1)
if [ -z "$SOURCE" ] || [ ! -x "$SOURCE/bin/booksound-server" ]; then
    echo "Unexpected archive layout." >&2
    exit 1
fi
systemctl stop booksound 2>/dev/null || true
rm -rf "$PREFIX.new"
cp -a "$SOURCE" "$PREFIX.new"
rm -rf "$PREFIX"
mv "$PREFIX.new" "$PREFIX"

UNIT=/etc/systemd/system/booksound.service
SCRIPT_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd || echo "")
if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/booksound.service" ]; then
    cp "$SCRIPT_DIR/booksound.service" "$UNIT"
else
    curl -fsSL -o "$UNIT" "$UNIT_URL"
fi

if [ ! -f /etc/booksound.env ]; then
    cat > /etc/booksound.env <<'ENV'
# BookSound server settings; restart with `systemctl restart booksound` after changes.
# BOOKSOUND_PORT=8080
# The reverse proxy's address, so the server sees real client addresses and https:
# BOOKSOUND_TRUSTED_PROXIES=192.168.1.1
# BOOKSOUND_TORRENTS=off
ENV
fi

systemctl daemon-reload
systemctl enable --now booksound
echo
echo "BookSound is running on port 8080 (see /etc/booksound.env)."
echo "The setup token for the first account is in the log: journalctl -u booksound | grep 'setup token'"
