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
export HUSH_TEST=1 HEALTH_POLL_SECONDS=0.1 HEALTH_SECONDS=2 RESTART_SETTLE_SECONDS=1 UNLOCK_SECONDS=2
# What update.sh would ask for at the terminal.
export HUSH_SERVER_PASSWORD='a long test server password'
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
  unset STUB_BREAK_AFTER_RESTART STUB_VALIDATE_FAIL STUB_BROKEN_VERSION STUB_TPM
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
  tar -czf "$release/hush-deploy.tar.gz" -C "${DEPLOY_SRC:-$DEPLOY}" lib.sh compose.yml update.sh backup.sh restore.sh RUNBOOK.md systemd
  echo "HUSH_DEPLOY_SHA256=$(sha256sum "$release/hush-deploy.tar.gz" | cut -c1-64)" >>"$release/hush-release.env"
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
check "the server password was set from the server itself, and the database unlocked" \
  bash -c "grep -q AezCrypt-keyring-1 '$HUSH_ROOT/data/keyring.json' && [ \$(cat '$STUB_STATE/server-state') = ready ] && ! [ -e '$HUSH_ROOT/data/setup-code.txt' ]"
check "this machine's key is private, 32 bytes, and outside data/" \
  bash -c "grep -Eqx '[0-9a-f]{64}' '$HUSH_ROOT/secrets/machine.key' && [ \$(stat -c %a '$HUSH_ROOT/secrets/machine.key') = 400 ] && [ \$(stat -c %a '$HUSH_ROOT/secrets') = 700 ] && grep -qx 'HUSH_SECRETS_DIR=$HUSH_ROOT/secrets' '$HUSH_ROOT/.env'"
check "the container gets it read-only" grep -q 'HUSH_SECRETS_DIR:-./secrets}:/run/secrets/hush:ro' "$HUSH_ROOT/compose.yml"
check "the admin panel's server controls are installed for this root" \
  bash -c "grep -q 'ExecStart=$HUSH_ROOT/control.sh run' '$BOX/systemd/hush-control.service' && grep -q 'PathExists=$HUSH_ROOT/data/control/request.env' '$BOX/systemd/hush-control.path' && grep -q 'enable --now hush-control.path' '$STUB_STATE/systemctl.log' && [ -f '$HUSH_ROOT/data/control/agent' ]"
machine_key=$(sha256sum <"$HUSH_ROOT/secrets/machine.key")

edited=$(sha256sum <"$BOX/etc/cloudflared/config.yml")
backups=$(ls "$BOX"/etc/cloudflared/config.yml.bak-* | wc -l)
out=$(bash "$DEPLOY/install.sh" 2>&1)
check "re-running is safe" test $? -eq 0
check "re-running keeps this machine's key" test "$(sha256sum <"$HUSH_ROOT/secrets/machine.key")" = "$machine_key"
check "re-running leaves the tunnel config alone" test "$(sha256sum <"$BOX/etc/cloudflared/config.yml")" = "$edited"
check "re-running makes no new backup or PORTS.md row" bash -c "[ \$(ls '$BOX'/etc/cloudflared/config.yml.bak-* | wc -l) = $backups ] && [ \$(grep -c hush '$BOX/opt/hl/PORTS.md') = 1 ]"

# As on Resolute: install.sh downloaded into the install folder and run again from there.
cp "$DEPLOY/install.sh" "$HUSH_ROOT/install.sh"
echo "# from an older release" >>"$HUSH_ROOT/lib.sh"
out=$(cd "$HUSH_ROOT" && bash ./install.sh 2>&1)
status=$?
check "re-running from inside the install folder works" test $status -eq 0
[ $status -eq 0 ] || echo "$out" | tail -n 5 | sed 's/^/      /'
check "... and installs the release's scripts, not the old copies" bash -c "! grep -q 'from an older release' '$HUSH_ROOT/lib.sh'"

# A tunnel named in config.yml, and no cert.pem to create DNS records with: the CNAME advice needs the tunnel's ID.
sandbox nocert flush-comments.yml
export STUB_NO_ORIGIN_CERT=1
printf '{"AccountTag":"a","TunnelSecret":"s","TunnelID":"0d6c1d4e-8f7a-4c1b-9e2f-3a4b5c6d7e8f"}\n' >"$BOX/tunnel.json"
sed -i "s#^credentials-file:.*#credentials-file: $BOX/tunnel.json#" "$BOX/etc/cloudflared/config.yml"
out=$(bash "$DEPLOY/install.sh" 2>&1)
check "without cert.pem, install still finishes" test $? -eq 0
check "... and gives the CNAME target by tunnel ID, not name" grep -q 'target 0d6c1d4e-8f7a-4c1b-9e2f-3a4b5c6d7e8f.cfargotunnel.com' <<<"$out"
unset STUB_NO_ORIGIN_CERT

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

echo "the encrypted database"
sandbox moved
bash "$DEPLOY/install.sh" >/dev/null 2>&1
# New hardware (or a restore): another machine key, so the server can't unlock by itself.
rm -f "$HUSH_ROOT/secrets/machine.key" # read-only (0400), as it should be
head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' >"$HUSH_ROOT/secrets/machine.key"
rm -f "$STUB_STATE/running" # it starts again there
out=$(HUSH_SERVER_PASSWORD='not the password at all' bash "$DEPLOY/update.sh" --yes 2>&1)
check "a locked server isn't opened by a wrong password" bash -c "[ $? -ne 0 ] && grep -q \"isn't the server password\" <<<\"\$1\" && [ \$(cat '$STUB_STATE/server-state') = locked ]" _ "$out"
out=$(bash "$DEPLOY/update.sh" --yes 2>&1)
check "the server password unlocks it on the new machine" bash -c "[ $? -eq 0 ] && [ \$(cat '$STUB_STATE/server-state') = ready ]"
rm -f "$STUB_STATE/running" # a reboot
out=$(env -u HUSH_SERVER_PASSWORD bash "$DEPLOY/update.sh" --yes </dev/null 2>&1)
check "... and from then on it unlocks by itself, asking nothing" bash -c "[ $? -eq 0 ] && [ \$(cat '$STUB_STATE/server-state') = ready ]"

sandbox tpm
export STUB_TPM=1
out=$(bash "$DEPLOY/install.sh" 2>&1)
check "with a TPM the machine key is sealed to it, never stored in the clear" \
  bash -c "[ -s '$HUSH_ROOT/secrets/machine.key.cred' ] && ! [ -e '$HUSH_ROOT/secrets/machine.key' ] && grep -q 'encrypt --with-key=tpm2' '$STUB_STATE/systemd-creds.log'"
check "... and unsealed into /run/hush at boot, before Docker" \
  bash -c "grep -q 'Before=docker.service' '$BOX/systemd/hush-unlock.service' && grep -q 'decrypt --name=hush-machine-key $HUSH_ROOT/secrets/machine.key.cred /run/hush/machine.key' '$BOX/systemd/hush-unlock.service' && grep -qx 'HUSH_SECRETS_DIR=/run/hush' '$HUSH_ROOT/.env' && grep -q 'enable hush-unlock.service' '$STUB_STATE/systemctl.log'"
unset STUB_TPM

echo "update.sh"
sandbox refresh
bash "$DEPLOY/install.sh" >/dev/null 2>&1
mkdir -p "$WORK/newer" && cp -r "$DEPLOY"/. "$WORK/newer/"
echo "# a newer runbook" >>"$WORK/newer/RUNBOOK.md"
DEPLOY_SRC=$WORK/newer publish "$BOX" 1.0.9 9999999999999999999999999999999999999999
out=$(bash "$DEPLOY/update.sh" --yes 2>&1)
check "update.sh brings the deploy files up to date from the release" bash -c "[ $? -eq 0 ] && grep -q 'a newer runbook' '$HUSH_ROOT/RUNBOOK.md' && [ \$(cat '$STUB_STATE/running') = 1.0.9 ]"
echo "# tampered" >>"$WORK/newer/RUNBOOK.md"
DEPLOY_SRC=$WORK/newer publish "$BOX" 1.0.10 1010101010101010101010101010101010101010
sed -i 's/^HUSH_DEPLOY_SHA256=.*/HUSH_DEPLOY_SHA256=0000000000000000000000000000000000000000000000000000000000000000/' "$BOX/release/hush-release.env"
out=$(bash "$DEPLOY/update.sh" --yes 2>&1)
check "... but not ones that don't match the manifest" bash -c "! grep -q tampered '$HUSH_ROOT/RUNBOOK.md' && grep -q \"don't match the manifest\" <<<\"\$1\"" _ "$out"

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
  echo '{"format":"AezCrypt-keyring-1"}' >"$data/keyring.json"
  mkdir -p "$HUSH_ROOT/secrets" && echo "this machine only" >"$HUSH_ROOT/secrets/machine.key"
  printf 'SQLite format 3\0old plaintext' >"$WORK/plain.db"
  mkdir -p "$RCLONE_REMOTE/hush/snapshots" && cp "$WORK/plain.db" "$RCLONE_REMOTE/hush/snapshots/hush-2026-01-01.db"
  out=$(bash "$DEPLOY/backup.sh" 2>&1)
  check "a backup succeeds" test $? -eq 0
  check "... and says so in backup-status.json" grep -q '"ok":true' "$data/backup-status.json"
  check "... with the keyring (sealed under the server password)" grep -q AezCrypt-keyring-1 "$RCLONE_REMOTE/hush/keyring/keyring.json"
  check "... but never this machine's key" bash -c "! grep -rq 'this machine only' '$RCLONE_REMOTE'"
  check "... and plaintext snapshots from before encryption are removed from it" bash -c "! [ -e '$RCLONE_REMOTE/hush/snapshots/hush-2026-01-01.db' ] && [ -e '$RCLONE_REMOTE/hush/snapshots/hush-$today.db' ]"
  rm "$data/blobs/ab/two"
  out=$(bash "$DEPLOY/backup.sh" 2>&1)
  check "media deleted since is kept under deleted/DATE" test -f "$RCLONE_REMOTE/hush/deleted/$today/ab/two"
  restore_root=$WORK/restored
  out=$(bash "$DEPLOY/restore.sh" --to "$restore_root" --remote "$RCLONE_REMOTE" 2>&1)
  check "a restore succeeds" test $? -eq 0
  check "... with the database and every blob the snapshot can reference" \
    bash -c "grep -q 'database of $today' '$restore_root/data/hush.db' && [ -f '$restore_root/data/blobs/ab/one' ] && [ -f '$restore_root/data/blobs/ab/two' ]"
  check "... and the Android signing key" grep -q 'the key' "$restore_root/data/signing/hush.p12"
  check "... and the keyring, to unlock with the server password" grep -q AezCrypt-keyring-1 "$restore_root/data/keyring.json"
  out=$(bash "$DEPLOY/restore.sh" --to "$restore_root" --remote "$RCLONE_REMOTE" 2>&1)
  check "restoring over existing data needs --force" test $? -ne 0
  RCLONE_REMOTE=nosuchremote: bash "$DEPLOY/backup.sh" >/dev/null 2>&1
  check "a missing remote is reported in backup-status.json" bash -c "grep -q '\"ok\":false' '$data/backup-status.json' && grep -q 'does not exist' '$data/backup-status.json'"
else
  echo "  (rclone not installed: skipped)"
fi

echo
echo "control.sh"
# The admin panel's jobs, with stand-ins for update.sh and backup.sh.
croot=$WORK/control-root
mkdir -p "$croot/data/control"
cp "$DEPLOY/control.sh" "$DEPLOY/lib.sh" "$croot/"
printf '#!/usr/bin/env bash\necho "==> Backing up"\necho "==> Backed up 3 files"\n' >"$croot/backup.sh"
printf '#!/usr/bin/env bash\necho "==> Checking"\necho "something broke" >&2\nexit 1\n' >"$croot/update.sh"
chmod +x "$croot"/*.sh
printf 'id=abc123\naction=backup\nrequestedAt=1\nby=admin\n' >"$croot/data/control/request.env"
HUSH_ROOT=$croot bash "$croot/control.sh" run
check "a backup the panel asked for runs and reports back" \
  bash -c "grep -qx 'state=done' '$croot/data/control/status.env' && grep -qx 'id=abc123' '$croot/data/control/status.env' && grep -qx 'message=Backed up 3 files' '$croot/data/control/status.env' && ! [ -e '$croot/data/control/request.env' ]"
check "... with its output for the panel" grep -q 'Backing up' "$croot/data/control/last.log"
printf 'id=def456\naction=update\n' >"$croot/data/control/request.env"
HUSH_ROOT=$croot bash "$croot/control.sh" run
check "a job that fails says so" bash -c "grep -qx 'state=failed' '$croot/data/control/status.env' && grep -qx 'message=something broke' '$croot/data/control/status.env'"
printf 'id=x;touch %s/pwned\naction=update --rollback\n' "$croot" >"$croot/data/control/request.env"
HUSH_ROOT=$croot bash "$croot/control.sh" run
check "anything but the four jobs is refused, and nothing in the request runs" \
  bash -c "grep -qx 'state=failed' '$croot/data/control/status.env' && grep -qx 'id=unknown' '$croot/data/control/status.env' && ! [ -e '$croot/pwned' ]"
HUSH_ROOT=$croot bash "$croot/control.sh" run
check "with nothing asked for, it does nothing" test $? -eq 0

echo
echo "$passed passed, $failed failed"
[ "$failed" -eq 0 ]
