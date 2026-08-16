#!/usr/bin/env bash
#
# §17: "network drop mid-chunk, server restart". This one kills the server
# between chunks and proves the upload picks up where it fell — the SQLite row
# and the .part file are the only state that matters.
#
# Runs on its own port and data dir so it never touches a live instance.
#
set -uo pipefail

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.." || exit 1

PORT=${PORT:-8788}
BASE="http://127.0.0.1:$PORT/api/v1"
DATA=".restart-data"
TMP=".restart-tmp"
CHUNK=$((4 * 1024 * 1024))
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

USERNAME=restart-tester
PASSWORD=restartpassword123

create_account() {
  printf '%s\n%s\n' "$PASSWORD" "$PASSWORD" |
    KADR_DATA_DIR="$DATA" node src/cli.js user add "$USERNAME" >/dev/null 2>&1
}

start_server() {
  KADR_PORT=$PORT KADR_DATA_DIR="$DATA" node src/index.js > "$TMP/server.log" 2>&1 &
  SERVER_PID=$!
  for _ in $(seq 1 60); do
    if curl -s -o "$TMP/discard" "$BASE/health"; then return 0; fi
    sleep 0.25
  done
  echo "server did not come up" >&2
  exit 1
}

stop_server() {
  kill "$SERVER_PID" 2>/dev/null
  wait "$SERVER_PID" 2>/dev/null
  SERVER_PID=""
}

printf '\n\033[1mUpload interrupted by a server restart\033[0m\n'

start_server
pass 'server started'
create_account

TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USERNAME\",\"password\":\"$PASSWORD\",\"deviceName\":\"Restart Test\"}" \
  | jget data.token)
if [ -n "$TOKEN" ]; then pass 'signed in'; else fail 'signed in'; exit 1; fi

head -c 9961472 /dev/urandom > "$TMP/clip.bin"
SIZE=$(( $(wc -c < "$TMP/clip.bin") ))
SHA=$(sha256sum "$TMP/clip.bin" | cut -d' ' -f1)

UPLOAD=$(curl -s -X POST "$BASE/uploads" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"sha256\":\"$SHA\",\"sizeBytes\":$SIZE,\"filename\":\"clip.mp4\",\"mimeType\":\"video/mp4\"}" \
  | jget data.uploadId)
if [ -n "$UPLOAD" ]; then pass 'upload session opened'; else fail 'upload session opened'; exit 1; fi

send_chunk() { # send_chunk <index>
  local i=$1
  dd if="$TMP/clip.bin" of="$TMP/chunk.bin" bs=$CHUNK skip="$i" count=1 status=none
  local len offset end
  len=$(( $(wc -c < "$TMP/chunk.bin") ))
  offset=$(( i * CHUNK ))
  end=$(( offset + len - 1 ))
  curl -s -o "$TMP/body" -w '%{http_code}' -X PATCH "$BASE/uploads/$UPLOAD" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/octet-stream' \
    -H "Content-Range: bytes $offset-$end/$SIZE" \
    --data-binary @"$TMP/chunk.bin"
}

status=$(send_chunk 0)
check 'first chunk accepted' "$status" 200
check '  4 MB held' "$(body | jget data.receivedBytes)" "$CHUNK"

stop_server
pass 'server killed mid-upload'

start_server
pass 'server restarted'

curl -s -o "$TMP/body" -H "Authorization: Bearer $TOKEN" "$BASE/uploads/$UPLOAD" >/dev/null
check 'the session survived the restart' "$(body | jget data.receivedBytes)" "$CHUNK"
check '  and still knows the target hash' "$(body | jget data.sha256)" "$SHA"

status=$(send_chunk 1)
check 'second chunk resumes cleanly' "$status" 200
status=$(send_chunk 2)
check 'third chunk accepted' "$status" 200

status=$(curl -s -o "$TMP/body" -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $TOKEN" "$BASE/uploads/$UPLOAD/complete")
check 'complete succeeds after the restart' "$status" 200
ASSET=$(body | jget data.assetId)

curl -s -o "$TMP/down.bin" -H "Authorization: Bearer $TOKEN" "$BASE/assets/$ASSET/file"
check 'the reassembled file is byte-identical' \
  "$(sha256sum "$TMP/down.bin" | cut -d' ' -f1)" "$SHA"

printf '\n───────────────────────────────\n'
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32m✓ survives a restart\033[0m  %d checks passed\n\n' "$PASSED"
else
  printf '\033[31m✗ RED\033[0m  %d passed, %d failed\n\n' "$PASSED" "$FAILED"
fi
exit "$FAILED"
