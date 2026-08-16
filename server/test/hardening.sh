#!/usr/bin/env bash
#
# §14 M7: disk-full handling and database migrations.
#
# Runs on its own port and data directory so it never touches a live instance.
#
set -uo pipefail

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.." || exit 1

PORT=${PORT:-8789}
BASE="http://127.0.0.1:$PORT/api/v1"
DATA=".hardening-data"
TMP=".hardening-tmp"
FAILED=0
PASSED=0

rm -rf "$DATA" "$TMP"
mkdir -p "$TMP"

SERVER_PID=""
cleanup() {
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null
  wait "$SERVER_PID" 2>/dev/null
  rm -rf "$TMP"
}
trap cleanup EXIT

pass() { PASSED=$((PASSED + 1)); printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { FAILED=$((FAILED + 1)); printf '  \033[31mFAIL\033[0m %s\n' "$1"; }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 — expected [$3], got [$2]"; fi; }
jget() { node test/jget.mjs "$1"; }
body() { cat "$TMP/body"; }
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

start_server() { # start_server [extra env assignments...]
  env "$@" KADR_PORT=$PORT KADR_DATA_DIR="$DATA" node src/index.js > "$TMP/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 80); do
    if curl -s -o "$TMP/discard" "$BASE/health"; then return 0; fi
    sleep 0.25
  done
  echo "server did not start; log follows" >&2
  cat "$TMP/server.log" >&2
  exit 1
}

stop_server() {
  kill "$SERVER_PID" 2>/dev/null
  wait "$SERVER_PID" 2>/dev/null
  SERVER_PID=""
}

USERNAME=hardening-tester
PASSWORD=hardeningpassword123

sign_in() {
  # The account is created once; on later calls the CLI simply refuses and the
  # existing one is reused.
  printf '%s\n%s\n' "$PASSWORD" "$PASSWORD" |
    KADR_DATA_DIR="$DATA" node src/cli.js user add "$USERNAME" >/dev/null 2>&1

  TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"deviceName\":\"Hardening\"}" \
    | jget data.token)
}

# ─────────────────────────────────────────────────────────────────────────────
section '1. A full disk is refused before a byte is sent (§15)'

# An absurd reserve makes every upload look like it would fill the disk.
start_server KADR_MIN_FREE_BYTES=999999999999999
sign_in

head -c 65536 /dev/urandom > "$TMP/file.bin"
SIZE=$(( $(wc -c < "$TMP/file.bin") ))
SHA=$(sha256sum "$TMP/file.bin" | cut -d' ' -f1)

status=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/uploads" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"sha256\":\"$SHA\",\"sizeBytes\":$SIZE,\"filename\":\"f.bin\",\"mimeType\":\"image/jpeg\"}")

check 'opening a session fails fast' "$status" 507
check '  with DISK_FULL' "$(body | jget error.code)" DISK_FULL
check '  and says how much room is free' "$([ -n "$(body | jget error.freeBytes)" ] && echo yes)" yes

# Nothing should have been written for a session that never opened.
PARTS=$(find "$DATA/tmp" -name '*.part' 2>/dev/null | wc -l | tr -d ' ')
check '  and leaves no partial file behind' "$PARTS" 0

status=$(curl -s -o "$TMP/body" -w '%{http_code}' "$BASE/health")
check 'health still answers on a "full" disk' "$status" 200

stop_server

# ─────────────────────────────────────────────────────────────────────────────
section '2. The same server with room to work'

start_server
sign_in

status=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST "$BASE/uploads" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"sha256\":\"$SHA\",\"sizeBytes\":$SIZE,\"filename\":\"f.bin\",\"mimeType\":\"image/jpeg\"}")
check 'the same upload is accepted' "$status" 201
UPLOAD=$(body | jget data.uploadId)

status=$(curl -s -o "$TMP/body" -w '%{http_code}' -X PATCH "$BASE/uploads/$UPLOAD" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
  -H "Content-Range: bytes 0-$((SIZE - 1))/$SIZE" --data-binary @"$TMP/file.bin")
check '  chunk accepted' "$status" 200

status=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $TOKEN" "$BASE/uploads/$UPLOAD/complete")
check '  and completes' "$status" 200

# ─────────────────────────────────────────────────────────────────────────────
section '3. Migrations survive a restart (§14 M7)'

VERSION_BEFORE=$(node -e "
  const { DatabaseSync } = require('node:sqlite');
  const db = new DatabaseSync('$DATA/kadr.db');
  console.log(db.prepare('PRAGMA user_version').get().user_version);
")
# v2 added accounts; bump this whenever a migration lands.
check 'the schema records its version' "$VERSION_BEFORE" 2

stop_server
start_server

VERSION_AFTER=$(node -e "
  const { DatabaseSync } = require('node:sqlite');
  const db = new DatabaseSync('$DATA/kadr.db');
  console.log(db.prepare('PRAGMA user_version').get().user_version);
")
check 'reopening does not re-run migrations' "$VERSION_AFTER" "$VERSION_BEFORE"

ASSETS=$(curl -s -H "Authorization: Bearer $TOKEN" "$BASE/assets?since=0&limit=10" | jget data.nextCursor)
check '  and the data is still there' "$([ -n "$ASSETS" ] && [ "$ASSETS" != "0" ] && echo yes)" yes

status=$(curl -s -o "$TMP/body" -w '%{http_code}' "$BASE/health")
check 'health reports a database size' \
  "$([ "$(body | jget data.dbSizeBytes)" -gt 0 ] && echo yes)" yes

# ─────────────────────────────────────────────────────────────────────────────
printf '\n───────────────────────────────\n'
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32m✓ hardening green\033[0m  %d checks passed\n\n' "$PASSED"
else
  printf '\033[31m✗ RED\033[0m  %d passed, %d failed\n\n' "$PASSED" "$FAILED"
fi
exit "$FAILED"
