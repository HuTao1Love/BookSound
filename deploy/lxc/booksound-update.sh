#!/bin/sh
# Run as root by booksound-update.service: installs the latest release with install.sh, as running it by hand does,
# and leaves the outcome in update/status for the server's admin page ("installing", or "failed" and the log's end).
#
# The update folder belongs to the server's user, so nothing in it is run or followed: install.sh is downloaded to
# a private temporary folder, and the status is written elsewhere and moved in (a rename doesn't follow links).
set -u

DIR=/var/lib/booksound/update
URL="https://raw.githubusercontent.com/HuTao1Love/BookSound/main/deploy/lxc/install.sh"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

status() {
    printf '%s\n' "$@" > "$TMP/status"
    chmod 644 "$TMP/status"
    mv -fT "$TMP/status" "$DIR/status"
}

rm -f "$DIR/request"
status installing
if curl -fsSL -o "$TMP/install.sh" "$URL" && sh "$TMP/install.sh" > "$TMP/log" 2>&1; then
    rm -f "$DIR/status"
else
    status failed "$(tail -n 20 "$TMP/log" 2>/dev/null || echo "Can't download $URL")"
fi
cat "$TMP/log" 2>/dev/null
