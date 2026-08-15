#!/usr/bin/env bash
#
# M1 acceptance (§14): curl can pair, start an upload, send chunks, complete,
# and re-download the exact bytes. Plus the failure paths §17 calls out.
#
#   ./test/e2e.sh                     # against http://127.0.0.1:8787
#   HOST=http://kadr.lan:8787 ./test/e2e.sh
#
# Paths stay relative on purpose: curl.exe and node.exe are native Windows
# binaries and do not understand Git Bash's /tmp or /c/... paths.
#
set -uo pipefail

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

cd "$(dirname "$0")/.." || exit 1

HOST=${HOST:-http://127.0.0.1:8787}
BASE="$HOST/api/v1"
TMP=".e2e-tmp"
rm -rf "$TMP" && mkdir -p "$TMP"
trap 'rm -rf "$TMP"' EXIT

CHUNK=$((4 * 1024 * 1024))
FAILED=0
PASSED=0

green() { printf '\033[32m%s\033[0m' "$1"; }
red()   { printf '\033[31m%s\033[0m' "$1"; }

pass() { PASSED=$((PASSED + 1)); printf '  %s %s\n' "$(green PASS)" "$1"; }
fail() { FAILED=$((FAILED + 1)); printf '  %s %s\n' "$(red FAIL)" "$1"; }

check() { # check <label> <actual> <expected>
  if [ "$2" = "$3" ]; then pass "$1"; else fail "$1 — expected [$3], got [$2]"; fi
}

section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

jget() { node test/jget.mjs "$1"; }
body() { cat "$TMP/body"; }
size_of() { echo $(( $(wc -c < "$1") )); }

# req <method> <path> [curl args...] -> prints status, body lands in $TMP/body
req() {
  local method=$1 path=$2
  shift 2
  curl -s -o "$TMP/body" -w '%{http_code}' -X "$method" "$BASE$path" "$@"
}

authed() { # authed <method> <path> [curl args...]
  local method=$1 path=$2
  shift 2
  req "$method" "$path" -H "Authorization: Bearer $TOKEN" "$@"
}

# ─────────────────────────────────────────────────────────────────────────────
section '1. Health'

status=$(req GET /health)
check 'GET /health returns 200' "$status" 200
check '  reports a version' "$(body | jget data.version)" 0.1.0
printf '     free disk: %s bytes · assets: %s · rss: %s\n' \
  "$(body | jget data.freeDiskBytes)" "$(body | jget data.assetCount)" \
  "$(body | jget data.rssBytes)"

# ─────────────────────────────────────────────────────────────────────────────
section '2. Pairing (§9, §13)'

status=$(req POST /auth/pair-code)
check 'localhost can mint a pairing code' "$status" 200
CODE=$(body | jget data.code)

WRONG=$(printf '%06d' $(( (10#$CODE + 7) % 1000000 )))
status=$(req POST /auth/pair -H 'Content-Type: application/json' \
  -d "{\"code\":\"$WRONG\",\"deviceName\":\"impostor\"}")
check 'wrong code is rejected' "$status" 401

status=$(req POST /auth/pair -H 'Content-Type: application/json' \
  -d "{\"code\":\"$CODE\",\"deviceName\":\"Test Pixel\"}")
check 'correct code pairs' "$status" 201
TOKEN=$(body | jget data.token)
DEVICE=$(body | jget data.deviceId)
if [ -n "$TOKEN" ]; then pass 'token issued'; else fail 'token issued'; fi
if [ -n "$DEVICE" ]; then pass 'device id issued'; else fail 'device id issued'; fi

status=$(req POST /auth/pair -H 'Content-Type: application/json' \
  -d "{\"code\":\"$CODE\",\"deviceName\":\"replay\"}")
check 'the same code cannot be used twice' "$status" 401

status=$(req GET /assets)
check 'no token means no library' "$status" 401

status=$(req GET /assets -H 'Authorization: Bearer not-a-real-token')
check 'a bogus token means no library' "$status" 401

# ─────────────────────────────────────────────────────────────────────────────
section '3. A 9.5 MB file, uploaded in 4 MB chunks (§10.4)'

head -c 9961472 /dev/urandom > "$TMP/clip.bin"
SIZE=$(size_of "$TMP/clip.bin")
SHA=$(sha256sum "$TMP/clip.bin" | cut -d' ' -f1)
printf '     %s bytes · sha256 %s…\n' "$SIZE" "${SHA:0:16}"

status=$(authed POST /assets/check -H 'Content-Type: application/json' \
  -d "{\"hashes\":[\"$SHA\"]}")
check 'check says the server does not have it' "$status" 200
check '  it is listed as missing' "$(body | jget data.missing)" "$SHA"

status=$(authed POST /uploads -H 'Content-Type: application/json' -d "{
  \"sha256\":\"$SHA\",\"sizeBytes\":$SIZE,\"filename\":\"clip.mp4\",
  \"mimeType\":\"video/mp4\",\"capturedAt\":1770000000000,
  \"width\":3840,\"height\":2160,\"durationMs\":12000,\"orientation\":0}")
check 'upload session created' "$status" 201
UPLOAD=$(body | jget data.uploadId)
check '  starts at zero bytes' "$(body | jget data.receivedBytes)" 0

dd if="$TMP/clip.bin" of="$TMP/probe.bin" bs=$CHUNK skip=1 count=1 status=none
status=$(authed PATCH "/uploads/$UPLOAD" \
  -H 'Content-Type: application/octet-stream' \
  -H "Content-Range: bytes 4194304-8388607/$SIZE" \
  --data-binary @"$TMP/probe.bin")
check 'a chunk beyond the watermark is refused' "$status" 409
check '  with the gap code' "$(body | jget error.code)" RANGE_GAP

offset=0
i=0
while [ "$offset" -lt "$SIZE" ]; do
  dd if="$TMP/clip.bin" of="$TMP/chunk.bin" bs=$CHUNK skip=$i count=1 status=none
  len=$(size_of "$TMP/chunk.bin")
  end=$(( offset + len - 1 ))

  status=$(authed PATCH "/uploads/$UPLOAD" \
    -H 'Content-Type: application/octet-stream' \
    -H "Content-Range: bytes $offset-$end/$SIZE" \
    --data-binary @"$TMP/chunk.bin")
  check "chunk $((i + 1)) accepted (bytes $offset-$end)" "$status" 200
  check '  watermark advanced' "$(body | jget data.receivedBytes)" "$((end + 1))"

  # Re-send this exact chunk: must be a no-op, never a corruption (§9).
  status=$(authed PATCH "/uploads/$UPLOAD" \
    -H 'Content-Type: application/octet-stream' \
    -H "Content-Range: bytes $offset-$end/$SIZE" \
    --data-binary @"$TMP/chunk.bin")
  check "  re-sending chunk $((i + 1)) is idempotent" "$(body | jget data.duplicate)" true

  offset=$(( end + 1 ))
  i=$(( i + 1 ))
done

status=$(authed GET "/uploads/$UPLOAD")
check 'resume probe reports the full size' "$(body | jget data.receivedBytes)" "$SIZE"

status=$(authed POST "/uploads/$UPLOAD/complete")
check 'complete succeeds' "$status" 200
ASSET=$(body | jget data.assetId)
if [ -n "$ASSET" ]; then pass 'asset id returned'; else fail 'asset id returned'; fi

# ─────────────────────────────────────────────────────────────────────────────
section '4. Re-download the exact bytes (§14 M1)'

curl -s -o "$TMP/down.bin" -H "Authorization: Bearer $TOKEN" "$BASE/assets/$ASSET/file"
check 'downloaded bytes hash to the original' \
  "$(sha256sum "$TMP/down.bin" | cut -d' ' -f1)" "$SHA"
check '  and the size matches' "$(size_of "$TMP/down.bin")" "$SIZE"

# ─────────────────────────────────────────────────────────────────────────────
section '5. Range requests — what video seeking rides on (§11)'

status=$(curl -s -o "$TMP/part.bin" -w '%{http_code}' \
  -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=1000000-1000099' \
  "$BASE/assets/$ASSET/file")
check 'a middle range returns 206' "$status" 206
check '  exactly 100 bytes' "$(size_of "$TMP/part.bin")" 100

dd if="$TMP/clip.bin" of="$TMP/expect.bin" bs=1 skip=1000000 count=100 status=none
if cmp -s "$TMP/part.bin" "$TMP/expect.bin"; then
  pass '  and they are the right 100 bytes'
else
  fail '  and they are the right 100 bytes'
fi

RANGE_HDR=$(curl -s -D - -o "$TMP/discard" -H "Authorization: Bearer $TOKEN" \
  -H 'Range: bytes=1000000-1000099' "$BASE/assets/$ASSET/file" \
  | tr -d '\r' | grep -i '^content-range:' | cut -d' ' -f2-)
check '  Content-Range is correct' "$RANGE_HDR" "bytes 1000000-1000099/$SIZE"

status=$(curl -s -o "$TMP/tail.bin" -w '%{http_code}' \
  -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=-2048' "$BASE/assets/$ASSET/file")
check 'a suffix range returns 206' "$status" 206
check '  with 2048 bytes' "$(size_of "$TMP/tail.bin")" 2048

status=$(curl -s -o "$TMP/discard" -w '%{http_code}' \
  -H "Authorization: Bearer $TOKEN" -H "Range: bytes=$((SIZE + 10))-" "$BASE/assets/$ASSET/file")
check 'a range past the end returns 416' "$status" 416

ACCEPT=$(curl -s -D - -o "$TMP/discard" -H "Authorization: Bearer $TOKEN" \
  "$BASE/assets/$ASSET/file" | tr -d '\r' | grep -i '^accept-ranges:' | cut -d' ' -f2)
check 'Accept-Ranges is advertised' "$ACCEPT" bytes

status=$(curl -s -o "$TMP/discard" -w '%{http_code}' -H "Authorization: Bearer $TOKEN" \
  -H "If-None-Match: \"$SHA\"" "$BASE/assets/$ASSET/file")
check 'a matching ETag returns 304' "$status" 304

# ─────────────────────────────────────────────────────────────────────────────
section '6. Deduplication — reinstall must upload nothing (§15)'

status=$(authed POST /assets/check -H 'Content-Type: application/json' \
  -d "{\"hashes\":[\"$SHA\"]}")
check 'check no longer reports it missing' "$(body | jget data.missing)" ''

status=$(authed POST /uploads -H 'Content-Type: application/json' -d "{
  \"sha256\":\"$SHA\",\"sizeBytes\":$SIZE,\"filename\":\"clip.mp4\",\"mimeType\":\"video/mp4\"}")
check 'starting the same upload short-circuits' "$status" 200
check '  alreadyExists is set' "$(body | jget data.alreadyExists)" true
check '  and it points at the same asset' "$(body | jget data.assetId)" "$ASSET"

# ─────────────────────────────────────────────────────────────────────────────
section '7. Hash mismatch is caught server-side (§9)'

head -c 65536 /dev/urandom > "$TMP/small.bin"
SMALL_SIZE=$(size_of "$TMP/small.bin")
LIE='0000000000000000000000000000000000000000000000000000000000000001'

status=$(authed POST /uploads -H 'Content-Type: application/json' \
  -d "{\"sha256\":\"$LIE\",\"sizeBytes\":$SMALL_SIZE,\"filename\":\"lie.jpg\",\"mimeType\":\"image/jpeg\"}")
BAD_UPLOAD=$(body | jget data.uploadId)

status=$(authed PATCH "/uploads/$BAD_UPLOAD" \
  -H 'Content-Type: application/octet-stream' \
  -H "Content-Range: bytes 0-$((SMALL_SIZE - 1))/$SMALL_SIZE" \
  --data-binary @"$TMP/small.bin")
check 'bytes that do not match the claimed hash are accepted' "$status" 200

status=$(authed POST "/uploads/$BAD_UPLOAD/complete")
check 'complete rejects them' "$status" 409
check '  with HASH_MISMATCH' "$(body | jget error.code)" HASH_MISMATCH
check '  and resets the session to zero' "$(body | jget error.receivedBytes)" 0

status=$(authed GET "/uploads/$BAD_UPLOAD")
check '  the session survives for a retry' "$(body | jget data.receivedBytes)" 0

# ─────────────────────────────────────────────────────────────────────────────
section '8. Lying about chunk length (§17)'

REAL_SMALL=$(sha256sum "$TMP/small.bin" | cut -d' ' -f1)
status=$(authed POST /uploads -H 'Content-Type: application/json' \
  -d "{\"sha256\":\"$REAL_SMALL\",\"sizeBytes\":$SMALL_SIZE,\"filename\":\"s.jpg\",\"mimeType\":\"image/jpeg\"}")
SHORT_UPLOAD=$(body | jget data.uploadId)

status=$(authed PATCH "/uploads/$SHORT_UPLOAD" \
  -H 'Content-Type: application/octet-stream' \
  -H "Content-Range: bytes 0-$((SMALL_SIZE - 1))/$SMALL_SIZE" \
  --data-binary @"$TMP/expect.bin")
check 'a chunk shorter than its Content-Range is refused' "$status" 400
check '  with LENGTH_MISMATCH' "$(body | jget error.code)" LENGTH_MISMATCH
check '  and the watermark stays at zero' "$(body | jget error.receivedBytes)" 0

status=$(authed PATCH "/uploads/$SHORT_UPLOAD" \
  -H 'Content-Type: application/octet-stream' \
  -H "Content-Range: bytes 0-$((SMALL_SIZE - 1))/$SMALL_SIZE" \
  --data-binary @"$TMP/small.bin")
check 'the honest retry then succeeds' "$status" 200

status=$(authed POST "/uploads/$SHORT_UPLOAD/complete")
check '  and completes cleanly' "$status" 200
SMALL_ASSET=$(body | jget data.assetId)

# ─────────────────────────────────────────────────────────────────────────────
section '9. Trash round trip (§9)'

status=$(authed "GET" "/assets?since=0&limit=10")
check 'delta sync lists assets' "$status" 200

status=$(authed DELETE "/assets/$ASSET")
check 'soft delete succeeds' "$status" 200

status=$(authed GET "/assets/$ASSET/file")
check '  the file is no longer served' "$status" 404

status=$(authed POST /assets/check -H 'Content-Type: application/json' \
  -d "{\"hashes\":[\"$SHA\"]}")
check '  a trashed asset counts as missing again' "$(body | jget data.missing)" "$SHA"

status=$(authed GET /assets/trash)
check 'the trash can be listed' "$status" 200
check '  with a retention window' "$(body | jget data.retentionDays)" 30
if node test/jget.mjs data.assets < "$TMP/body" | grep -q "$ASSET" 2>/dev/null; then
  pass '  and the deleted asset is in it'
else
  # jget flattens arrays of objects poorly; fall back to a raw grep.
  if grep -q "$ASSET" "$TMP/body"; then
    pass '  and the deleted asset is in it'
  else
    fail '  and the deleted asset is in it'
  fi
fi

status=$(authed POST "/assets/$ASSET/restore")
check 'restore succeeds' "$status" 200

curl -s -o "$TMP/again.bin" -H "Authorization: Bearer $TOKEN" "$BASE/assets/$ASSET/file"
check '  and the exact bytes come back' \
  "$(sha256sum "$TMP/again.bin" | cut -d' ' -f1)" "$SHA"

# ─────────────────────────────────────────────────────────────────────────────
section '10. Revoke (§13)'

status=$(authed POST /auth/revoke)
check 'a device can revoke itself' "$status" 200

status=$(authed GET /assets)
check '  the token is dead afterwards' "$status" 401

# ─────────────────────────────────────────────────────────────────────────────
printf '\n───────────────────────────────\n'
if [ "$FAILED" -eq 0 ]; then
  printf '%s  %d checks passed\n\n' "$(green '✓ M1 GREEN')" "$PASSED"
else
  printf '%s  %d passed, %d failed\n\n' "$(red '✗ M1 RED')" "$PASSED" "$FAILED"
fi
exit "$FAILED"
