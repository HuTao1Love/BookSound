#!/bin/sh
# Installs or updates the BookSound server in a Debian/Ubuntu LXC container (or any systemd machine).
#
#   curl -fsSL https://raw.githubusercontent.com/HuTao1Love/BookSound/main/deploy/lxc/install.sh | sh
#   sh install.sh /path/to/booksound-server.tar.gz      # a local archive instead of the latest release
#   sh install.sh --source claude/server                # build a branch from GitHub (needs ~2 GB RAM)
#
# Data lives in /var/lib/booksound (library, database, covers); settings in /etc/booksound.env.
# Running it again updates the program and keeps the data.
set -eu

REPO="HuTao1Love/BookSound"
PREFIX=/opt/booksound
DATA=/var/lib/booksound
RAW_URL="https://raw.githubusercontent.com/$REPO/main/deploy/lxc"

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root." >&2
    exit 1
fi

ARCHIVE=
BRANCH=
if [ "${1:-}" = "--source" ]; then
    BRANCH=${2:-main}
else
    ARCHIVE=${1:-}
fi

echo "Installing Java and ffmpeg..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ffmpeg curl ca-certificates gnupg >/dev/null
# The server needs Java 21 or newer. Distributions without it (Debian 12 ships 17) get Eclipse Temurin.
# Building from source takes a JDK (Gradle then fetches the JDK it builds with by itself).
KIND=jre
[ -n "$BRANCH" ] && KIND=jdk
JAVA=
for candidate in "openjdk-25-$KIND-headless" "openjdk-21-$KIND-headless" "temurin-25-$KIND" "temurin-21-$KIND"; do
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
    JAVA=temurin-21-$KIND
fi
apt-get install -y -qq "$JAVA" >/dev/null
[ -n "$BRANCH" ] && apt-get install -y -qq git >/dev/null

if ! id booksound >/dev/null 2>&1; then
    useradd --system --home-dir "$DATA" --shell /usr/sbin/nologin booksound
fi
mkdir -p "$DATA"
chown booksound:booksound "$DATA"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
if [ -n "$BRANCH" ]; then
    echo "Building branch $BRANCH from GitHub (several minutes)..."
    git clone -q --depth 1 --branch "$BRANCH" "https://github.com/$REPO.git" "$TMP/src"
    VERSION="$(sed -n 's/^appVersion=//p' "$TMP/src/gradle.properties" | tr -d '[:space:]')-$(git -C "$TMP/src" rev-parse --short HEAD)"
    # Without the Android parts (no SDK needed); memory kept low for a small container.
    (cd "$TMP/src" && ./gradlew --no-daemon -q -PserverOnly=true -PversionName="$VERSION"         -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx1g" :server:distTar)
    ARCHIVE="$TMP/src/server/build/distributions/booksound-server.tar.gz"
elif [ -z "$ARCHIVE" ]; then
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

# The service, and the units that let the admin page install updates (booksound-update.*): from the built branch,
# next to this script, or from GitHub when it was piped into sh.
SCRIPT_DIR=$(cd "$(dirname "$0")" 2>/dev/null && pwd || echo "")
deploy_file() {
    if [ -f "$TMP/src/deploy/lxc/$1" ]; then
        cp "$TMP/src/deploy/lxc/$1" "$2"
    elif [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/$1" ]; then
        cp "$SCRIPT_DIR/$1" "$2"
    else
        curl -fsSL -o "$2" "$RAW_URL/$1"
    fi
}
deploy_file booksound.service /etc/systemd/system/booksound.service
deploy_file booksound-update.service /etc/systemd/system/booksound-update.service
deploy_file booksound-update.path /etc/systemd/system/booksound-update.path
mkdir -p /usr/local/lib/booksound
deploy_file booksound-update.sh /usr/local/lib/booksound/update.sh
chmod 755 /usr/local/lib/booksound/update.sh
mkdir -p "$DATA/update"
chown booksound:booksound "$DATA/update"

if [ ! -f /etc/booksound.env ]; then
    cat > /etc/booksound.env <<'ENV'
# BookSound server settings; restart with `systemctl restart booksound` after changes.
# BOOKSOUND_PORT=8080
# The router's address, so the server sees real client addresses and https behind KeenDNS:
# BOOKSOUND_TRUSTED_PROXIES=192.168.1.1
# BOOKSOUND_TORRENTS=off
ENV
fi

systemctl daemon-reload
systemctl enable --now booksound
systemctl enable --now booksound-update.path
echo
echo "BookSound is running on port 8080 (see /etc/booksound.env)."
echo "The setup token for the first account is in the log: journalctl -u booksound | grep 'setup token'"
