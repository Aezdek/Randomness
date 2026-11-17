#!/usr/bin/env bash
# End-to-end check: builds both containers, connects to the share the way the
# iPad does, drops files in the Inbox and checks they come out sorted.
# Needs docker (with compose) and smbclient. Uses its own temporary folder and
# port, so it doesn't touch a running setup.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
project="pastpapers-smoke-$$"
port="${SMOKE_PORT:-14450}"
user=student
pass='smoke Test $pass"word'

passed=false
cleanup() {
  cd "$work"
  if [ "$passed" != true ]; then
    echo "---- container logs ----"
    docker compose -p "$project" logs --no-color || true
  fi
  docker compose -p "$project" down --remove-orphans >/dev/null 2>&1 || true
  cd /
  rm -rf "$work" 2>/dev/null || docker run --rm -v "$work:/w" python:3.13-alpine rm -rf /w/data || true
  rm -rf "$work" 2>/dev/null || true
}
trap cleanup EXIT

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

cp -r "$here/Dockerfile" "$here/docker-compose.yml" "$here/config.example.toml" "$here/pastpapers" "$here/samba" "$work/"
cd "$work"

uid="$(id -u)"
gid="$(id -g)"
if [ "$uid" = 0 ]; then uid=1000 gid=1000; fi
mkdir -p data/library data/inbox data/state
chown -R "$uid:$gid" data 2>/dev/null || true
sed -e 's/^scan_seconds = .*/scan_seconds = 1/' -e 's/^settle_seconds = .*/settle_seconds = 1/' \
  config.example.toml > config.toml
printf "SMB_USER=%s\nSMB_PASSWORD='%s'\nPUID=%s\nPGID=%s\nSMB_PORT=%s\n" "$user" "$pass" "$uid" "$gid" "$port" > .env

echo "Building and starting containers..."
docker compose -p "$project" up -d --build --quiet-pull

smb() {
  local share="$1"
  shift
  smbclient "//127.0.0.1/$share" -p "$port" -U "$user%$pass" -m SMB3 "$@"
}

echo "Waiting for the share..."
for _ in $(seq 60); do
  if smb Inbox -c ls >/dev/null 2>&1; then break; fi
  sleep 1
done
smb Inbox -c ls >/dev/null || fail "couldn't log in to the Inbox share"

printf '%%PDF-1.7\nfake chemistry paper\n%%%%EOF\n' > qp.pdf
printf '%%PDF-1.7\nmy revision notes\n%%%%EOF\n' > notes.pdf

echo "Dropping two files into the Inbox, like the Files app would..."
smb Inbox -c 'mkdir "Old downloads"; put qp.pdf "Old downloads/0620-S24-QP-42.pdf"; put notes.pdf "Revision notes.pdf"' >/dev/null

expected="Chemistry (0620)/2024/May-June/Chemistry 2024 May-June P42 QP.pdf"
echo "Waiting for: $expected"
for _ in $(seq 60); do
  if smb "Past Papers" -c "get \"$expected\" got-qp.pdf" >/dev/null 2>&1; then break; fi
  sleep 1
done
[ -f got-qp.pdf ] || fail "the paper never appeared in Past Papers"
cmp -s qp.pdf got-qp.pdf || fail "the sorted paper's contents changed"

smb "Past Papers" -c 'get "Unsorted/Revision notes.pdf" got-notes.pdf' >/dev/null 2>&1 \
  || fail "the unrecognised file didn't land in Unsorted"
cmp -s notes.pdf got-notes.pdf || fail "the unsorted file's contents changed"

for _ in $(seq 30); do
  inbox="$(smb Inbox -c ls 2>/dev/null)"
  if ! grep -q -e "Old downloads" -e "Revision notes" <<<"$inbox"; then break; fi
  sleep 1
done
grep -q -e "Old downloads" -e "Revision notes" <<<"$inbox" && fail "the Inbox wasn't emptied: $inbox"

if smb "Past Papers" -c 'put qp.pdf "sneaky.pdf"' 2>&1 | grep -q "putting file"; then
  fail "Past Papers should be read-only"
fi
[ -e "data/library/sneaky.pdf" ] && fail "a file was written into the read-only library"

smb "Past Papers" -c ls 2>/dev/null | grep -q ".incoming" && fail "the .incoming folder is visible on the share"

if smbclient //127.0.0.1/Inbox -p "$port" -U "$user%wrong password" -m SMB3 -c ls >/dev/null 2>&1; then
  fail "a wrong password was accepted"
fi

owner="$(stat -c %u:%g "data/library/$expected")"
[ "$owner" = "$uid:$gid" ] || fail "sorted file is owned by $owner, expected $uid:$gid"

passed=true
echo "Smoke test passed."
