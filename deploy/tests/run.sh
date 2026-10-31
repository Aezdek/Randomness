#!/usr/bin/env bash
# Tests for the deploy scripts. No root, Docker daemon or tunnel needed: host commands are replaced by the doubles
# in stubs/, the cloudflared configs and PORTS.md come from fixtures/, and backups go to a local rclone path.
# CI sets REAL_CLOUDFLARED so the edited configs are also validated by the real cloudflared.
#
#   deploy/tests/run.sh
set -uo pipefail

TESTS=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
DEPLOY=$(dirname "$TESTS")
FIX=$TESTS/fixtures
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
export HUSH_TEST=1 HEALTH_POLL_SECONDS=0.1 HEALTH_SECONDS=2 RESTART_SETTLE_SECONDS=1
export PATH="$TESTS/stubs:$PATH"
# shellcheck source=deploy/lib.sh
. "$DEPLOY/lib.sh"

passed=0
failed=0
ok() {
  passed=$((passed + 1))
  printf '  \033[32m✓\033[0m %s\n' "$1"
}
bad() {
  failed=$((failed + 1))
  printf '  \033[31m✗ %s\033[0m\n' "$1"
  [ $# -gt 1 ] && printf '%s\n' "$2" | sed 's/^/      /'
}
check() { # check NAME COMMAND...
  local name=$1
  shift
  local output
  if output=$("$@" 2>&1); then ok "$name"; else bad "$name" "$output"; fi
}
same() { diff -u "$1" "$2"; }

# A fresh fake Resolute: tunnel config, PORTS.md, a published release, empty /opt/hl/hush.
sandbox() {
  local box=$WORK/$1 config=${2:-two-space.yml}
  rm -rf "$box"
  mkdir -p "$box/etc/cloudflared" "$box/opt/hl" "$box/systemd" "$box/state" "$box/release"
  cp "$FIX/$config" "$box/etc/cloudflared/config.yml"
  cp "$FIX/PORTS.md" "$box/opt/hl/PORTS.md"
  export STUB_STATE=$box/state
  export HUSH_ROOT=$box/opt/hl/hush CLOUDFLARED_CONFIG=$box/etc/cloudflared/config.yml
  export PORTS_MD=$box/opt/hl/PORTS.md SYSTEMD_DIR=$box/systemd HUSH_RELEASE_BASE=file://$box/release
  export RCLONE_REMOTE=$box/remote STUB_BOUND_PORTS="8471"
  unset STUB_BREAK_AFTER_RESTART STUB_VALIDATE_FAIL STUB_BROKEN_VERSION
  publish "$box" 1.0.1 1111111111111111111111111111111111111111
  BOX=$box
}

# publish BOX VERSION COMMIT: what CI uploads to the hush-latest release.
publish() {
  local release=$1/release
  head -c 4096 /dev/urandom | gzip >"$release/hush-image.tar.gz"
  cat >"$release/hush-release.env" <<EOF
HUSH_COMMIT=$3
HUSH_VERSION=$2
HUSH_IMAGE_TAG=hush:${3:0:12}
HUSH_IMAGE_SHA256=$(sha256sum "$release/hush-image.tar.gz" | cut -c1-64)
HUSH_BUILT_AT=2026-10-30T10:00:00Z
EOF
  echo "hush:${3:0:12}" >"$1/state/next-tag"
}

echo "cloudflared ingress"
for name in two-space flush-comments four-space; do
  ingress_insert "$FIX/$name.yml" "$WORK/$name.out" hush.aezdek.xyz http://127.0.0.1:8472
  check "$name: rule goes right above the catch-all, nothing else changes" same "$FIX/$name.expected.yml" "$WORK/$name.out"
  if [ -n "${REAL_CLOUDFLARED:-}" ]; then
    check "$name: cloudflared accepts the result" "$REAL_CLOUDFLARED" tunnel --config "$WORK/$name.out" ingress validate
    check "$name: gram routes to Hush" bash -c "'$REAL_CLOUDFLARED' tunnel --config '$WORK/$name.out' ingress rule https://hush.aezdek.xyz | grep -q 127.0.0.1:8472"
    check "$name: lucifer still routes where it did" bash -c "'$REAL_CLOUDFLARED' tunnel --config '$WORK/$name.out' ingress rule https://lucifer.aezdek.xyz | grep -Eq 'localhost:8080|127.0.0.1:8080'"
  fi
done
ingress_insert "$FIX/no-catchall.yml" "$WORK/none.out" hush.aezdek.xyz http://127.0.0.1:8472
check "a config without a catch-all is refused" test $? -eq 3
check "hostnames are read with quotes and comments" bash -c "[ \"\$(. '$DEPLOY/lib.sh'; ingress_hosts '$FIX/flush-comments.yml' | tr '\n' ' ')\" = 'lucifer.aezdek.xyz print.aezdek.xyz ' ]"

echo "PORTS.md"
cp "$FIX/PORTS.md" "$WORK/PORTS.md"
STUB_BOUND_PORTS=8471 check "the first port neither listed nor bound is 8472" test "$(STUB_BOUND_PORTS=8471 pick_port "$WORK/PORTS.md" 8470 8499)" = 8472
ports_add_row "$WORK/PORTS.md" 8472 "Hush (hush.aezdek.xyz), 127.0.0.1 only, /opt/hl/hush"
check "the row matches the table's own columns" same "$FIX/PORTS.expected.md" "$WORK/PORTS.md"
check "a recorded port is found again" test "$(ports_port_of "$WORK/PORTS.md" hush)" = 8472
rm -f "$WORK/fresh.md"
ports_add_row "$WORK/fresh.md" 8472 "Hush"
check "a missing PORTS.md gets a table" grep -q '^| 8472 | hush | Hush |$' "$WORK/fresh.md"

echo "disks"
check "the external HDD is refused" test -n "$(EXTERNAL_HDD_MOUNT=/mnt/hdd disk_problem /mnt/hdd/hush/data)"
check "a path elsewhere is not" test -z "$(EXTERNAL_HDD_MOUNT=/mnt/hdd disk_problem "$WORK")"

echo "install.sh"
sandbox fresh
original=$(sha256sum <"$BOX/etc/cloudflared/config.yml")
out=$(bash "$DEPLOY/install.sh" 2>&1)
status=$?
check "a fresh install succeeds" test $status -eq 0
[ $status -eq 0 ] || echo "$out" | sed 's/^/      /'
check "gram is routed through the tunnel above the catch-all" \
  bash -c "grep -B0 -A2 'hostname: hush.aezdek.xyz' '$BOX/etc/cloudflared/config.yml' | grep -q 'service: http://127.0.0.1:8472' && tail -n1 '$BOX/etc/cloudflared/config.yml' | grep -q http_status:404"
check "the original tunnel config was backed up first" bash -c "ls '$BOX'/etc/cloudflared/config.yml.bak-* | head -n1 | xargs sha256sum | cut -d' ' -f1 | grep -q '${original%% *}'"
check "cloudflared validated the edit before the restart" bash -c "grep -n 'ingress validate' '$STUB_STATE/cloudflared.log' >/dev/null && grep -q 'restart cloudflared' '$STUB_STATE/systemctl.log'"
check "the port is recorded in PORTS.md" grep -q '^| 8472 | hush |' "$BOX/opt/hl/PORTS.md"
check ".env pins the image by digest and is private" bash -c "grep -q '^HUSH_IMAGE_ID=sha256:' '$HUSH_ROOT/.env' && [ \$(stat -c %a '$HUSH_ROOT/.env') = 600 ]"
check "the server runs the published version" test "$(cat "$STUB_STATE/running")" = 1.0.1
check "the backup timer is installed for this root" bash -c "grep -q 'ExecStart=$HUSH_ROOT/backup.sh' '$BOX/systemd/hush-backup.service' && grep -q 'enable --now hush-backup.timer' '$STUB_STATE/systemctl.log'"
check "the summary shows the first invite" grep -q 'ABCDE-FGHJK' <<<"$out"

edited=$(sha256sum <"$BOX/etc/cloudflared/config.yml")
backups=$(ls "$BOX"/etc/cloudflared/config.yml.bak-* | wc -l)
out=$(bash "$DEPLOY/install.sh" 2>&1)
check "re-running is safe" test $? -eq 0
check "re-running leaves the tunnel config alone" test "$(sha256sum <"$BOX/etc/cloudflared/config.yml")" = "$edited"
check "re-running makes no new backup or PORTS.md row" bash -c "[ \$(ls '$BOX'/etc/cloudflared/config.yml.bak-* | wc -l) = $backups ] && [ \$(grep -c hush '$BOX/opt/hl/PORTS.md') = 1 ]"

sandbox regress flush-comments.yml
original=$(sha256sum <"$BOX/etc/cloudflared/config.yml")
export STUB_BREAK_AFTER_RESTART=lucifer.aezdek.xyz
out=$(bash "$DEPLOY/install.sh" 2>&1)
check "if lucifer stops answering after the restart, install fails" test $? -ne 0
check "... and the original tunnel config is back, byte for byte" test "$(sha256sum <"$BOX/etc/cloudflared/config.yml")" = "$original"
check "... and cloudflared was restarted on it" test "$(cat "$STUB_STATE/restarts")" = 2

sandbox invalid
original=$(sha256sum <"$BOX/etc/cloudflared/config.yml")
export STUB_VALIDATE_FAIL=1
out=$(bash "$DEPLOY/install.sh" 2>&1)
check "if cloudflared rejects the edit, install fails" test $? -ne 0
check "... without touching the config or restarting the tunnel" \
  bash -c "[ \"\$(sha256sum <'$BOX/etc/cloudflared/config.yml')\" = '$original' ] && ! grep -q 'restart cloudflared' '$STUB_STATE/systemctl.log' && ! ls '$BOX'/etc/cloudflared/.config.hush.* 2>/dev/null"

sandbox nocatch no-catchall.yml
out=$(bash "$DEPLOY/install.sh" 2>&1)
check "a tunnel config without a catch-all is left alone" bash -c "[ $? -ne 0 ] && diff -q '$FIX/no-catchall.yml' '$BOX/etc/cloudflared/config.yml'"

if command -v rclone >/dev/null; then
  # As on Resolute: rclone installed, no remote named in the environment, and root's own config has no crypt remote.
  sandbox noremote
  unset RCLONE_REMOTE
  : >"$BOX/root-rclone.conf"
  out=$(RCLONE_CONFIG=$BOX/root-rclone.conf bash "$DEPLOY/install.sh" 2>&1)
  status=$?
  check "with no backup remote anywhere, install still finishes" test $status -eq 0
  [ $status -eq 0 ] || echo "$out" | tail -n 5 | sed 's/^/      /'
  check "... and says backups are off until the remote exists" grep -q 'backups are OFF' <<<"$out"

  # The remote found in the config rclone uses, even when it isn't called gdrive.
  sandbox foundremote
  unset RCLONE_REMOTE
  printf '[disk]\ntype = local\n\n[vault]\ntype = crypt\nremote = disk:%s\npassword = unused\n' "$BOX/crypt" >"$BOX/user-rclone.conf"
  out=$(RCLONE_CONFIG=$BOX/user-rclone.conf bash "$DEPLOY/install.sh" 2>&1)
  check "the only crypt remote is picked up for backups" \
    bash -c "grep -qx 'RCLONE_REMOTE=vault:' '$HUSH_ROOT/.env' && grep -qx 'RCLONE_CONFIG=$BOX/user-rclone.conf' '$HUSH_ROOT/.env' && grep -q 'enable --now hush-backup.timer' '$STUB_STATE/systemctl.log'"
fi

echo "update.sh"
sandbox update
bash "$DEPLOY/install.sh" >/dev/null 2>&1
out=$(bash "$DEPLOY/update.sh" --check 2>&1)
check "--check with nothing new says so" grep -q 'Already on the newest build' <<<"$out"
publish "$BOX" 1.0.2 2222222222222222222222222222222222222222
out=$(bash "$DEPLOY/update.sh" --check 2>&1)
check "--check shows the new build and changes nothing" bash -c "grep -q '1.0.2' <<<'$out' && [ \$(cat '$STUB_STATE/running') = 1.0.1 ]"
out=$(echo n | bash "$DEPLOY/update.sh" 2>&1)
check "answering no changes nothing" test "$(cat "$STUB_STATE/running")" = 1.0.1
out=$(echo y | bash "$DEPLOY/update.sh" 2>&1)
check "answering yes installs it" test "$(cat "$STUB_STATE/running")" = 1.0.2

touch "$HUSH_ROOT/data/hush.db"
publish "$BOX" 1.0.3 3333333333333333333333333333333333333333
export STUB_BROKEN_VERSION=1.0.3
out=$(bash "$DEPLOY/update.sh" --yes 2>&1)
check "a build that fails its health check is rolled back" bash -c "[ $? -ne 0 ] && [ \$(cat '$STUB_STATE/running') = 1.0.2 ] && grep -q '^HUSH_VERSION=1.0.2' '$HUSH_ROOT/.env'"
check "... after copying the database" test -f "$HUSH_ROOT/data/snapshots/pre-update-222222222222.db"
unset STUB_BROKEN_VERSION

publish "$BOX" 1.0.4 4444444444444444444444444444444444444444
head -c 100 /dev/urandom >>"$BOX/release/hush-image.tar.gz"
out=$(bash "$DEPLOY/update.sh" --yes 2>&1)
check "a download that doesn't match the published digest is refused" bash -c "[ $? -ne 0 ] && [ \$(cat '$STUB_STATE/running') = 1.0.2 ]"

echo "image$RANDOM" >"$STUB_STATE/images/hush:222222222222"
out=$(bash "$DEPLOY/update.sh" --rollback 2>&1)
check "an image whose digest no longer matches .env is not started" bash -c "[ $? -ne 0 ] || grep -q 'no longer matches' <<<'$out'"

echo "backup.sh / restore.sh"
if command -v rclone >/dev/null; then
  sandbox backup
  data=$HUSH_ROOT/data
  mkdir -p "$data/snapshots" "$data/blobs/ab"
  install -m 600 /dev/null "$HUSH_ROOT/.env"
  today=$(date +%F)
  echo "database of $today" >"$data/snapshots/hush-$today.db"
  echo "ciphertext one" >"$data/blobs/ab/one"
  echo "ciphertext two" >"$data/blobs/ab/two"
  mkdir -p "$data/signing" && echo "the key" >"$data/signing/hush.p12" && echo "pw" >"$data/signing/password"
  out=$(bash "$DEPLOY/backup.sh" 2>&1)
  check "a backup succeeds" test $? -eq 0
  check "... and says so in backup-status.json" grep -q '"ok":true' "$data/backup-status.json"
  rm "$data/blobs/ab/two"
  out=$(bash "$DEPLOY/backup.sh" 2>&1)
  check "media deleted since is kept under deleted/DATE" test -f "$RCLONE_REMOTE/hush/deleted/$today/ab/two"
  restore_root=$WORK/restored
  out=$(bash "$DEPLOY/restore.sh" --to "$restore_root" --remote "$RCLONE_REMOTE" 2>&1)
  check "a restore succeeds" test $? -eq 0
  check "... with the database and every blob the snapshot can reference" \
    bash -c "grep -q 'database of $today' '$restore_root/data/hush.db' && [ -f '$restore_root/data/blobs/ab/one' ] && [ -f '$restore_root/data/blobs/ab/two' ]"
  check "... and the Android signing key" grep -q 'the key' "$restore_root/data/signing/hush.p12"
  out=$(bash "$DEPLOY/restore.sh" --to "$restore_root" --remote "$RCLONE_REMOTE" 2>&1)
  check "restoring over existing data needs --force" test $? -ne 0
  RCLONE_REMOTE=nosuchremote: bash "$DEPLOY/backup.sh" >/dev/null 2>&1
  check "a missing remote is reported in backup-status.json" bash -c "grep -q '\"ok\":false' '$data/backup-status.json' && grep -q 'does not exist' '$data/backup-status.json'"
else
  echo "  (rclone not installed: skipped)"
fi

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
