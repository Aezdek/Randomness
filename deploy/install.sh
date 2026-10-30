#!/usr/bin/env bash
# Sets AezGram up on Resolute, once. Safe to re-run: each step checks before it changes anything.
#
#   curl -fsSLO https://github.com/Aezdek/Randomness/releases/download/aezgram-latest/install.sh
#   sudo bash install.sh
#
# Optional, for TURN relays (calls on networks that block peer-to-peer):
#   sudo CLOUDFLARE_API_TOKEN=... CLOUDFLARE_ACCOUNT_ID=... bash install.sh
#
# What it does:
#   1. checks Docker, cloudflared and the tools it needs; refuses to put data on a USB/removable disk
#   2. lays out /opt/hl/aezgram like the other /opt/hl stacks and picks a port PORTS.md doesn't list
#   3. installs and starts the newest image CI published (pinned by digest, see update.sh)
#   4. adds gram.aezdek.xyz to /etc/cloudflared/config.yml *carefully*: backup, insert above the catch-all,
#      `cloudflared tunnel ingress validate`, restart, re-check lucifer/print and every other hostname, and
#      restore the backup if anything got worse
#   5. installs the nightly backup timer (to the encrypted Google Drive remote)
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=${AEZGRAM_ROOT:-/opt/hl/aezgram}
HOST=${AEZGRAM_HOSTNAME:-gram.aezdek.xyz}
REPO=${AEZGRAM_REPO:-Aezdek/Randomness}
RELEASE_BASE=${AEZGRAM_RELEASE_BASE:-https://github.com/$REPO/releases/download/aezgram-latest}
CF_CONFIG=${CLOUDFLARED_CONFIG:-/etc/cloudflared/config.yml}
CF_SERVICE=${CLOUDFLARED_SERVICE:-cloudflared}
PORTS_MD=${PORTS_MD:-/opt/hl/PORTS.md}
SYSTEMD_DIR=${SYSTEMD_DIR:-/etc/systemd/system}
PORT_RANGE=${AEZGRAM_PORT_RANGE:-8470-8499}
RESTART_SETTLE_SECONDS=${RESTART_SETTLE_SECONDS:-60}

# The deploy files: next to this script in a checkout, otherwise from the same release as the image.
if [ -f "$HERE/lib.sh" ] && [ -f "$HERE/compose.yml" ]; then
  SRC=$HERE
else
  SRC=$(mktemp -d)
  trap 'rm -rf "$SRC"' EXIT
  curl -fsSL --retry 3 "$RELEASE_BASE/aezgram-deploy.tar.gz" | tar -xz -C "$SRC" ||
    { echo "error: could not download the deploy files from $RELEASE_BASE" >&2; exit 1; }
fi
# shellcheck source=deploy/lib.sh
. "$SRC/lib.sh"

[ "$(id -u)" = 0 ] || [ -n "${AEZGRAM_TEST:-}" ] || die "run with sudo"

# ------------------------------------------------------------------------------------------- 1. preflight
log "Checking this machine"
missing=()
for tool in docker curl flock ss findmnt lsblk sha256sum awk sed tar; do command -v "$tool" >/dev/null || missing+=("$tool"); done
[ ${#missing[@]} -eq 0 ] || die "missing: ${missing[*]}"
docker compose version >/dev/null 2>&1 || die "the Docker compose plugin is missing (apt install docker-compose-plugin)"
command -v cloudflared >/dev/null || die "cloudflared is not installed; AezGram is served through the existing tunnel"
[ -f "$CF_CONFIG" ] || die "$CF_CONFIG not found (set CLOUDFLARED_CONFIG=... if it lives elsewhere)"
grep -q '^ingress:' "$CF_CONFIG" || die "$CF_CONFIG has no ingress: section"

mkdir -p "$ROOT/data/blobs" "$ROOT/data/snapshots"
problem=$(disk_problem "$ROOT/data")
if [ -n "$problem" ]; then
  [ -n "${ALLOW_EXTERNAL_DISK:-}" ] || die "$problem. AezGram's data must live on the internal disk (set ALLOW_EXTERNAL_DISK=1 to override)."
  warn "$problem (allowed by ALLOW_EXTERNAL_DISK)"
fi
note "Data: $ROOT/data ($(df -h --output=avail "$ROOT/data" | tail -n1 | tr -d ' ') free)"

# ------------------------------------------------------------------------------------------- 2. layout
log "Files in $ROOT"
for file in compose.yml update.sh backup.sh restore.sh lib.sh RUNBOOK.md; do
  [ -f "$SRC/$file" ] && install -m "$([[ $file == *.sh ]] && echo 755 || echo 644)" "$SRC/$file" "$ROOT/$file"
done

ENV_FILE=$ROOT/.env
port=$(env_get "$ENV_FILE" AEZGRAM_HOST_PORT)
if [ -z "$port" ]; then
  port=$(ports_port_of "$PORTS_MD" aezgram)
  if [ -z "$port" ]; then
    port=$(pick_port "$PORTS_MD" "${PORT_RANGE%-*}" "${PORT_RANGE#*-}") || die "no free port in $PORT_RANGE"
    if [ -f "$PORTS_MD" ] || [ -d "$(dirname "$PORTS_MD")" ]; then
      ports_add_row "$PORTS_MD" "$port" "AezGram ($HOST), 127.0.0.1 only, $ROOT"
      note "Port $port recorded in $PORTS_MD"
    fi
  fi
elif port_bound "$port" && ! compose ps --status running -q 2>/dev/null | grep -q .; then
  die "port $port (from .env) is taken by something else"
fi
note "Port: 127.0.0.1:$port"

if [ ! -f "$ENV_FILE" ]; then
  install -m 600 /dev/null "$ENV_FILE"
  {
    echo "# AezGram settings. update.sh manages the AEZGRAM_IMAGE* / COMMIT / VERSION lines."
    echo "AEZGRAM_REPO=$REPO"
    echo "AEZGRAM_VAPID_SUBJECT=mailto:admin@${HOST#*.}"
    echo "AEZGRAM_MIN_FREE_GB=10"
    echo "TZ=$(timedatectl show -p Timezone --value 2>/dev/null || cat /etc/timezone 2>/dev/null || echo UTC)"
  } >>"$ENV_FILE"
fi
env_set "$ENV_FILE" AEZGRAM_HOST_PORT "$port"

# TURN: only with a Cloudflare API token, and only once.
if [ -z "$(env_get "$ENV_FILE" CF_TURN_KEY_ID)" ] && [ -n "${CLOUDFLARE_API_TOKEN:-}" ] && [ -n "${CLOUDFLARE_ACCOUNT_ID:-}" ]; then
  log "Creating a Cloudflare TURN key"
  answer=$(curl -fsS -X POST "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID/calls/turn_keys" \
    -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" -H 'Content-Type: application/json' --data '{"name":"aezgram"}') ||
    die "Cloudflare refused to create a TURN key (the token needs Calls: Edit)"
  key_id=$(sed -n 's/.*"uid":[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$answer")
  key=$(sed -n 's/.*"key":[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$answer")
  [ -n "$key_id" ] && [ -n "$key" ] || die "unexpected answer from Cloudflare: $answer"
  env_set "$ENV_FILE" CF_TURN_KEY_ID "$key_id"
  env_set "$ENV_FILE" CF_TURN_API_TOKEN "$key"
  note "TURN key $key_id saved in .env"
fi

# The rclone remote for backups: RCLONE_REMOTE, else a crypt remote that looks like Google Drive, else the only
# crypt remote. root may not have one, but the user who ran sudo might.
rclone_config=$(env_get "$ENV_FILE" RCLONE_CONFIG)
remote=$(env_get "$ENV_FILE" RCLONE_REMOTE)
if [ -z "$remote" ] && command -v rclone >/dev/null; then
  for config in "${RCLONE_CONFIG:-}" "" "$(getent passwd "${SUDO_USER:-}" 2>/dev/null | cut -d: -f6)/.config/rclone/rclone.conf"; do
    [ -n "$config" ] && [ ! -f "$config" ] && continue
    crypts=$(RCLONE_CONFIG=${config:-${RCLONE_CONFIG:-}} rclone listremotes --long 2>/dev/null | awk '$2 == "crypt" { print $1 }')
    remote=${RCLONE_REMOTE:-$(grep -Ei 'g(oogle)?drive' <<<"$crypts" | head -n1)}
    [ -n "$remote" ] || [ "$(grep -c . <<<"$crypts")" != 1 ] || remote=$crypts
    if [ -n "$remote" ]; then
      rclone_config=$config
      break
    fi
  done
fi
[ -n "$remote" ] && env_set "$ENV_FILE" RCLONE_REMOTE "$remote"
[ -n "$rclone_config" ] && env_set "$ENV_FILE" RCLONE_CONFIG "$rclone_config"

# ------------------------------------------------------------------------------------------- 3. image
chown -R 10001:10001 "$ROOT/data" 2>/dev/null || [ -n "${AEZGRAM_TEST:-}" ] || die "could not hand $ROOT/data to the container user"
AEZGRAM_ROOT=$ROOT AEZGRAM_RELEASE_BASE=$RELEASE_BASE "$ROOT/update.sh" --yes

# ------------------------------------------------------------------------------------------- 4. tunnel
configure_tunnel() {
  local service="http://127.0.0.1:$port" stamp backup candidate rule host
  if ingress_has_host "$CF_CONFIG" "$HOST"; then
    if grep -A3 -E "hostname:[[:space:]]*[\"']?$HOST" "$CF_CONFIG" | grep -q "127.0.0.1:$port"; then
      note "$CF_CONFIG already routes $HOST to $service"
    else
      warn "$CF_CONFIG already has a rule for $HOST pointing elsewhere; leaving it alone. It should be: service: $service"
    fi
    return 0
  fi

  log "Adding $HOST to $CF_CONFIG"
  stamp=$(date +%Y%m%d-%H%M%S)
  backup="$CF_CONFIG.bak-$stamp"
  cp -p "$CF_CONFIG" "$backup"
  note "Backup: $backup"
  candidate=$(mktemp "$(dirname "$CF_CONFIG")/.config.aezgram.XXXXXX")
  ingress_insert "$CF_CONFIG" "$candidate" "$HOST" "$service" || {
    rm -f "$candidate"
    die "could not find the catch-all rule (- service: http_status:404) in $CF_CONFIG; nothing changed"
  }

  # Validate the edited copy before it replaces anything.
  if ! cloudflared tunnel --config "$candidate" ingress validate >/dev/null 2>"$candidate.err"; then
    cat "$candidate.err" >&2
    rm -f "$candidate" "$candidate.err"
    die "cloudflared rejected the edited config; $CF_CONFIG is unchanged"
  fi
  rule=$(cloudflared tunnel --config "$candidate" ingress rule "https://$HOST" 2>&1 || true)
  if ! grep -q "$HOST" <<<"$rule" || ! grep -q "127.0.0.1:$port" <<<"$rule"; then
    echo "$rule" >&2
    rm -f "$candidate" "$candidate.err"
    die "https://$HOST would not reach AezGram with the edited config; $CF_CONFIG is unchanged"
  fi
  rm -f "$candidate.err"

  # How every hostname answers now, so the restart can be judged against it.
  declare -A before=()
  while read -r host; do
    [ -n "$host" ] && [[ $host != \** ]] && before[$host]=$(http_status "https://$host/")
  done < <(ingress_hosts "$CF_CONFIG")

  chmod --reference="$CF_CONFIG" "$candidate"
  chown --reference="$CF_CONFIG" "$candidate" 2>/dev/null || true
  mv "$candidate" "$CF_CONFIG"
  systemctl restart "$CF_SERVICE"

  local deadline=$((SECONDS + RESTART_SETTLE_SECONDS)) worse=() now
  while :; do
    worse=()
    systemctl is-active --quiet "$CF_SERVICE" || worse+=("the $CF_SERVICE service")
    for host in "${!before[@]}"; do
      status_ok "${before[$host]}" || continue
      now=$(http_status "https://$host/")
      status_ok "$now" || worse+=("$host (${before[$host]} -> $now)")
    done
    [ ${#worse[@]} -eq 0 ] && break
    ((SECONDS < deadline)) || break
    sleep "${HEALTH_POLL_SECONDS:-5}"
  done
  if [ ${#worse[@]} -gt 0 ]; then
    warn "after the restart these got worse: ${worse[*]}"
    cp -p "$backup" "$CF_CONFIG"
    systemctl restart "$CF_SERVICE" || true
    die "restored $backup and restarted $CF_SERVICE; AezGram is running locally but not published"
  fi
  note "Tunnel restarted; ${#before[@]} existing hostname(s) still answer"

  local tunnel
  tunnel=$(sed -n 's/^tunnel:[[:space:]]*//p' "$CF_CONFIG" | tr -d "\"' ")
  if [ -n "$tunnel" ]; then
    if out=$(cloudflared tunnel route dns "$tunnel" "$HOST" 2>&1); then
      note "DNS: $HOST -> tunnel $tunnel"
    elif grep -qi 'already' <<<"$out"; then
      note "DNS for $HOST already exists"
    else
      warn "could not create the DNS record ($out). Add a CNAME $HOST -> $tunnel.cfargotunnel.com in Cloudflare."
    fi
  fi
  local deadline=$((SECONDS + RESTART_SETTLE_SECONDS))
  until [[ $(curl -fsS --max-time 5 "https://$HOST/healthz" 2>/dev/null) == *'"ok":true'* ]]; do
    if ((SECONDS >= deadline)); then
      warn "https://$HOST isn't answering yet (new DNS records can take a few minutes)"
      return 0
    fi
    sleep "${HEALTH_POLL_SECONDS:-5}"
  done
  note "https://$HOST answers"
}
configure_tunnel

# ------------------------------------------------------------------------------------------- 5. backups
log "Backups"
sed "s#@ROOT@#$ROOT#g" "$SRC/systemd/aezgram-backup.service" >"$SYSTEMD_DIR/aezgram-backup.service"
install -m 644 "$SRC/systemd/aezgram-backup.timer" "$SYSTEMD_DIR/aezgram-backup.timer"
systemctl daemon-reload
backups_ready=false
if [ -n "$remote" ] && command -v rclone >/dev/null; then
  systemctl enable --now aezgram-backup.timer >/dev/null
  backups_ready=true
  note "Nightly at 03:30 to $remote/aezgram (status in data/backup-status.json and the admin screen)"
else
  systemctl disable --now aezgram-backup.timer >/dev/null 2>&1 || true
  printf '{"ok":false,"lastRunAt":%s,"lastSuccessAt":null,"message":"Backups are not set up yet: see RUNBOOK.md"}\n' "$(date +%s)" \
    >"$ROOT/data/backup-status.json"
fi

# ------------------------------------------------------------------------------------------- summary
echo
log "AezGram is installed."
note "Open:           https://$HOST"
invite=$(cat "$ROOT/data/first-invite.txt" 2>/dev/null || true)
[ -n "$invite" ] && note "First invite:   $invite   (whoever uses it becomes the admin)"
note "Android app:    https://$HOST/download/aezgram.apk"
note "Update later:   sudo $ROOT/update.sh"
[ -n "$(env_get "$ENV_FILE" CF_TURN_KEY_ID)" ] || note "Calls:          STUN only. For TURN, re-run with CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID."
if ! $backups_ready; then
  echo
  warn "backups are OFF until an encrypted rclone remote exists. Once, as root:"
  note "  rclone config     # n) new remote 'gdrive' (Google Drive), then n) new remote 'gdrive-crypt' (crypt, remote gdrive:aezgram-crypt)"
  note "  then: sudo bash install.sh   (re-running is safe; it enables the timer)"
fi
