#!/usr/bin/env bash
#
# Kadr server installer for Ubuntu (§4, §13).
#
# Run it from a checkout of this repository, on the machine that will hold the
# photos:
#
#   sudo bash server/deploy/install.sh
#
# Safe to run again: it upgrades an existing install and never touches
# /srv/kadr, which is where the photos and the database live.
#
# It deliberately stops short of two things. Creating the first account needs a
# password typed at a terminal, and installing Caddy's root certificate happens
# on the phone. Both are printed at the end.
set -euo pipefail

APP_DIR=/opt/kadr/server
DATA_DIR=${KADR_DATA_DIR:-/srv/kadr}
SITE=${KADR_SITE:-kadr.lan}
NODE_MAJOR=24

say()  { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; }
die()  { printf '\n\033[31m✗ %s\033[0m\n\n' "$1" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run this with sudo."
command -v apt-get >/dev/null || die "This installer is for Debian/Ubuntu."

# The repo root is two levels up from deploy/.
SRC=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
[ -f "$SRC/package.json" ] || die "Run this from the repository checkout, not a copy of the script."

# ── Node ─────────────────────────────────────────────────────────────────────
say "1. Node $NODE_MAJOR"

# node:sqlite is what the whole database layer is built on. Older 22.x builds
# hide it behind --experimental-sqlite, which the systemd unit does not pass —
# so an out-of-date Node does not degrade, it simply fails to boot.
needs_node=1
if command -v node >/dev/null; then
  current=$(node --version | sed 's/^v//')
  major=${current%%.*}
  minor=$(echo "$current" | cut -d. -f2)
  if [ "$major" -gt 22 ] || { [ "$major" -eq 22 ] && [ "$minor" -ge 13 ]; }; then
    needs_node=0
    ok "node v$current is new enough"
  else
    warn "node v$current is too old for node:sqlite; installing $NODE_MAJOR.x"
  fi
fi

if [ "$needs_node" -eq 1 ]; then
  curl -fsSL "https://deb.nodesource.com/setup_$NODE_MAJOR.x" | bash -
  apt-get install -y nodejs
  ok "node $(node --version) installed"
fi

# ── Packages ─────────────────────────────────────────────────────────────────
say "2. ffmpeg and Caddy"

apt-get install -y ffmpeg debian-keyring debian-archive-keyring apt-transport-https curl
ok "ffmpeg $(ffmpeg -version 2>/dev/null | head -1 | cut -d' ' -f3)"

if ! command -v caddy >/dev/null; then
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
    | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
  curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
    > /etc/apt/sources.list.d/caddy-stable.list
  apt-get update
  apt-get install -y caddy
fi
ok "caddy $(caddy version 2>/dev/null | head -1)"

# ── User and directories ─────────────────────────────────────────────────────
say "3. Service account and directories"

id kadr >/dev/null 2>&1 || useradd --system --home "$DATA_DIR" --shell /usr/sbin/nologin kadr
mkdir -p "$DATA_DIR" /opt/kadr
chown kadr:kadr "$DATA_DIR"
ok "kadr owns $DATA_DIR"

if ! mountpoint -q "$DATA_DIR"; then
  warn "$DATA_DIR is not a mount point — photos will land on the root filesystem."
  warn "If a separate disk is meant to hold them, mount it there and re-run."
fi

# ── Application ──────────────────────────────────────────────────────────────
say "4. Application"

mkdir -p "$APP_DIR"
# --delete keeps an upgrade from leaving old files behind, and the excludes make
# sure a developer's checkout never overwrites what is on the server.
rsync -a --delete \
  --exclude node_modules --exclude data --exclude '.*-data' --exclude '.*-tmp' \
  "$SRC"/ "$APP_DIR"/
ok "copied to $APP_DIR"

cd "$APP_DIR"
npm ci --omit=dev
ok "dependencies installed"

# ── systemd ──────────────────────────────────────────────────────────────────
say "5. Service"

install -m 644 "$APP_DIR/deploy/kadr.service" /etc/systemd/system/kadr.service
systemctl daemon-reload
systemctl enable kadr >/dev/null
systemctl restart kadr

for _ in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8787/api/v1/health >/dev/null 2>&1; then break; fi
  sleep 1
done

health=$(curl -fsS http://127.0.0.1:8787/api/v1/health 2>/dev/null || true)
[ -n "$health" ] || die "The service did not come up. Look at: journalctl -u kadr -n 50"
ok "service is answering"

case "$health" in
  *'"thumbnails":"available"'*) ok "ffmpeg is reachable from the service" ;;
  *) warn "the service cannot see ffmpeg — thumbnails will 503" ;;
esac

# ── Caddy ────────────────────────────────────────────────────────────────────
say "6. TLS"

if [ -f /etc/caddy/Caddyfile ] && ! grep -q 'reverse_proxy 127.0.0.1:8787' /etc/caddy/Caddyfile; then
  cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.before-kadr.$(date +%s)"
  warn "kept your previous Caddyfile alongside the new one"
fi

sed "s/^kadr\.lan {/$SITE {/" "$APP_DIR/deploy/Caddyfile" > /etc/caddy/Caddyfile
systemctl reload caddy 2>/dev/null || systemctl restart caddy
ok "caddy serving $SITE"

if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 443/tcp >/dev/null
  ok "opened 443 (8787 stays closed — it is Caddy's alone)"
fi

# ── What is left ─────────────────────────────────────────────────────────────
say "Done. Three things still need you:"

cat <<EOF

  1. Create the first account. The password is read from the terminal, never
     from an argument — arguments end up in shell history and in ps output.

       sudo -u kadr KADR_DATA_DIR=$DATA_DIR node $APP_DIR/src/cli.js user add <name>

  2. Point $SITE at this machine. Add it to the router's DNS, or give this
     box a DHCP reservation and a hosts entry on the router. Caddy issued the
     certificate for that name, so the phone has to reach it by that name.

  3. Install Caddy's root certificate on the phone, or a release build will
     refuse the connection:

       sudo cat /var/lib/caddy/.local/share/caddy/pki/authorities/local/root.crt

     Copy it over, then Settings > Security > Encryption & credentials >
     Install a certificate > CA certificate.

  Then check from the phone's browser: https://$SITE  — no warning should show.

  Not set up here: backups. $DATA_DIR is one disk, and §2 says never lose a
  file. At minimum, take the database somewhere else on a schedule:

       sqlite3 $DATA_DIR/kadr.db ".backup /some/other/disk/kadr-\$(date +%F).db"

EOF
