#!/usr/bin/env bash
# Sets Hush up on a Linux server that has Docker. It can be run again at any time: it keeps what was chosen before
# and only does what is still missing.
#
#   curl -fsSLO https://github.com/Aezdek/Randomness/releases/download/hush-latest/install.sh
#   sudo bash install.sh           asks a few questions in the terminal
#   sudo bash install.sh --gui     asks them in menus: windows on a desktop, full-screen menus in a terminal (over SSH)
#   sudo bash install.sh --yes     asks nothing: the defaults, an earlier install's choices, and the settings below
#
# Each setting answers one question ahead of time:
#
#   HUSH_HOSTNAME=hush.example.com    the address people will use
#   HUSH_PUBLISH=tunnel|proxy|local   how it is reached: the Cloudflare Tunnel on this machine, your own reverse
#                                     proxy, or not from outside yet
#   HUSH_ROOT=/opt/hush               the folder it lives in
#   HUSH_HOST_PORT=8470               its port on this machine (default: the first free one from 8470)
#   RCLONE_REMOTE=name:               an encrypted rclone remote for the nightly backup (none: no backups)
#   CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID   create a TURN key, for calls on networks that block direct ones
#
# What it does: checks the machine, lays out the folder, installs and starts the newest published image (pinned by
# digest, see update.sh), asks for the server password that encrypts the database, publishes the address the way you
# chose, and sets up the nightly backup and the admin panel's server controls.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=${HUSH_REPO:-Aezdek/Randomness}
RELEASE_BASE=${HUSH_RELEASE_BASE:-https://github.com/$REPO/releases/download/hush-latest}
CF_CONFIG=${CLOUDFLARED_CONFIG:-/etc/cloudflared/config.yml}
CF_SERVICE=${CLOUDFLARED_SERVICE:-cloudflared}
SYSTEMD_DIR=${SYSTEMD_DIR:-/etc/systemd/system}
PORT_RANGE=${HUSH_PORT_RANGE:-8470-8499}
RESTART_SETTLE_SECONDS=${RESTART_SETTLE_SECONDS:-60}

gui=false
ask=true
for arg in "$@"; do
  case $arg in
    --gui) gui=true ;;
    --yes | -y) ask=false ;;
    --help | -h)
      sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d; s/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "error: unknown option $arg (try --help)" >&2
      exit 1
      ;;
  esac
done

# The other deploy files: next to this script in a checkout, otherwise from the same release as the image. Files next
# to it in the install folder itself are the installed copies (possibly older), so a run from there fetches fresh ones.
if [ -f "$HERE/lib.sh" ] && [ -f "$HERE/compose.yml" ] && [ ! -f "$HERE/.env" ]; then
  SRC=$HERE
else
  SRC=$(mktemp -d)
  SRC_TMP=$SRC
  curl -fsSL --retry 3 "$RELEASE_BASE/hush-deploy.tar.gz" | tar -xz -C "$SRC" ||
    { echo "error: could not download the deploy files from $RELEASE_BASE" >&2; exit 1; }
fi
# shellcheck source=deploy/lib.sh
. "$SRC/lib.sh"
set -E
trap 'stopped "$LINENO" "$BASH_COMMAND"' ERR
trap 'status=$?; ui_farewell "$status"; rm -rf "${SRC_TMP:-}"' EXIT

if $gui; then ui_pick; else export HUSH_UI=${HUSH_UI:-cli}; fi

if [ "$(id -u)" != 0 ] && [ -z "${HUSH_TEST:-}" ]; then
  # On a desktop, ask for the administrator's password in a window and carry on as root.
  if [ "$HUSH_UI" = desktop ] && command -v pkexec >/dev/null; then
    keep=("HUSH_UI=desktop" "HUSH_UI_USER=$(id -un)")
    for name in DISPLAY WAYLAND_DISPLAY XAUTHORITY XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS HUSH_HOSTNAME HUSH_PUBLISH HUSH_ROOT \
      HUSH_HOST_PORT HUSH_REPO HUSH_RELEASE_BASE RCLONE_REMOTE RCLONE_CONFIG CLOUDFLARE_API_TOKEN CLOUDFLARE_ACCOUNT_ID CLOUDFLARED_CONFIG; do
      [ -z "${!name:-}" ] || keep+=("$name=${!name}")
    done
    exec pkexec env "${keep[@]}" bash "$HERE/$(basename "${BASH_SOURCE[0]}")" "$@"
  fi
  die "run it with sudo: sudo bash $(basename "${BASH_SOURCE[0]}") $*"
fi
# Without a person to ask (no terminal, no windows), take the defaults.
[ "$HUSH_UI" != cli ] || ui_has_terminal || ask=false

# ------------------------------------------------------------------------------------------- the machine
log "Checking this machine"
missing=()
for tool in docker curl flock ss findmnt lsblk sha256sum awk sed tar setpriv systemctl; do command -v "$tool" >/dev/null || missing+=("$tool"); done
[ ${#missing[@]} -eq 0 ] || die "these are needed and missing: ${missing[*]}"
docker compose version >/dev/null 2>&1 || die "Docker's compose plugin is missing (on Debian and Ubuntu: apt install docker-compose-plugin)"

# An earlier install, wherever it was put: this script inside it, or the folder its systemd units point at.
earlier=""
if [ -f "$HERE/.env" ] && [ -f "$HERE/compose.yml" ]; then
  earlier=$HERE
else
  for unit in "$SYSTEMD_DIR/hush-control.service" "$SYSTEMD_DIR/hush-backup.service"; do
    [ -f "$unit" ] || continue
    earlier=$(sed -n 's#^ExecStart=\(/.*\)/\(control\|backup\)\.sh.*#\1#p' "$unit" | head -n1)
    [ -z "$earlier" ] || break
  done
fi
[ -z "$earlier" ] || [ -f "$earlier/.env" ] || earlier=""
ROOT=${HUSH_ROOT:-${earlier:-/opt/hush}}
ENV_FILE=$ROOT/.env
[ ! -f "$ENV_FILE" ] || note "Found Hush in $ROOT"

have_tunnel=false
if command -v cloudflared >/dev/null && [ -f "$CF_CONFIG" ] && grep -q '^ingress:' "$CF_CONFIG"; then have_tunnel=true; fi

# ------------------------------------------------------------------------------------------- the questions
port=${HUSH_HOST_PORT:-$(env_get "$ENV_FILE" HUSH_HOST_PORT)}
HOST=${HUSH_HOSTNAME:-$(env_get "$ENV_FILE" HUSH_HOSTNAME)}
PUBLISH=${HUSH_PUBLISH:-$(env_get "$ENV_FILE" HUSH_PUBLISH)}
if [ -z "$HOST" ] && [ -n "$port" ] && $have_tunnel; then
  # An install from before the address was recorded: the tunnel knows it.
  HOST=$(ingress_host_for "$CF_CONFIG" "$port")
  [ -z "$HOST" ] || PUBLISH=${PUBLISH:-tunnel}
fi
if [ -z "$PUBLISH" ]; then
  if $have_tunnel; then PUBLISH=tunnel; else PUBLISH=proxy; fi
fi

# The rclone remotes that encrypt (crypt), from root's config or the config of whoever ran sudo.
rclone_config=${RCLONE_CONFIG:-$(env_get "$ENV_FILE" RCLONE_CONFIG)}
remote=${RCLONE_REMOTE:-$(env_get "$ENV_FILE" RCLONE_REMOTE)}
crypts=""
if command -v rclone >/dev/null; then
  sudo_home=$(getent passwd "${SUDO_USER:-}" 2>/dev/null | cut -d: -f6 || true)
  for config in "$rclone_config" "" "${sudo_home:+$sudo_home/.config/rclone/rclone.conf}"; do
    [ -n "$config" ] && [ ! -f "$config" ] && continue
    # `|| true`: finding nothing (no config, or no crypt remote in it) is a normal answer, not a failure.
    crypts=$(RCLONE_CONFIG=${config:-${RCLONE_CONFIG:-}} rclone listremotes --long 2>/dev/null | awk '$2 == "crypt" { print $1 }' || true)
    if [ -n "$crypts" ]; then
      rclone_config=$config
      break
    fi
  done
  # Without being told: the only crypt remote there is, or the one that looks like a cloud drive.
  if [ -z "$remote" ]; then
    remote=$(grep -Ei 'drive|cloud|backup' <<<"$crypts" | head -n1 || true)
    [ -n "$remote" ] || [ "$(grep -c . <<<"$crypts")" != 1 ] || remote=$crypts
  fi
fi

valid_host() {
  if [ "$PUBLISH" = local ]; then [[ $1 =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]]; else [[ $1 =~ ^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$ ]]; fi
}

if $ask; then
  [ "$HUSH_UI" = cli ] || ui_say "Hush" "This sets Hush up on this machine: a few questions, then it installs and starts. Nothing changes until you confirm at the end."

  options=()
  ! $have_tunnel || options+=(tunnel "Through the Cloudflare Tunnel on this machine")
  options+=(proxy "Through my own reverse proxy (Caddy, nginx, Traefik)" local "Not from outside yet: only on this machine or network")
  [ "$PUBLISH" != tunnel ] || $have_tunnel || PUBLISH=proxy
  PUBLISH=$(ui_choose "Hush" "How will people reach Hush?" "$PUBLISH" "${options[@]}") || die "cancelled; nothing was changed"

  while :; do
    HOST=$(ui_ask "Hush" "The address people will use, like hush.example.com" "$HOST") || die "cancelled; nothing was changed"
    HOST=${HOST,,}
    HOST=${HOST#http://}
    HOST=${HOST#https://}
    HOST=${HOST%%/*}
    valid_host "$HOST" && break
    ui_say "Hush" "That doesn't look like an address. It should be a name like hush.example.com, without https:// in front."
  done

  if [ ! -f "$ENV_FILE" ] && [ -z "${HUSH_ROOT:-}" ]; then
    while :; do
      ROOT=$(ui_ask "Hush" "The folder to install it in" "$ROOT") || die "cancelled; nothing was changed"
      [[ $ROOT == /* ]] && [[ $ROOT != *[[:space:]]* ]] && break
      ui_say "Hush" "The folder has to be a full path without spaces, like /opt/hush."
    done
    ENV_FILE=$ROOT/.env
  fi

  if [ -n "$crypts" ]; then
    options=()
    while read -r name; do [ -z "$name" ] || options+=("$name" "Back up to $name every night"); done <<<"$crypts"
    options+=(none "No backups for now")
    grep -qxF "${remote:-none}" <<<"$crypts" || remote=none
    remote=$(ui_choose "Hush" "Nightly backups go to an encrypted rclone remote. Which one?" "${remote:-none}" "${options[@]}") || die "cancelled; nothing was changed"
  fi
fi
[ "$remote" != none ] || remote=""

case $PUBLISH in tunnel | proxy | local) ;; *) die "HUSH_PUBLISH is tunnel, proxy or local (not $PUBLISH)" ;; esac
[ -n "$HOST" ] || die "Hush needs the address people will use: run this in a terminal to be asked, or set HUSH_HOSTNAME=hush.example.com"
valid_host "$HOST" || die "$HOST doesn't look like an address (it should be like hush.example.com)"
[ "$PUBLISH" != tunnel ] || $have_tunnel || die "there is no Cloudflare Tunnel here ($CF_CONFIG with an ingress: section); choose HUSH_PUBLISH=proxy or local"

mkdir -p "$ROOT/data/blobs" "$ROOT/data/snapshots"
problem=$(disk_problem "$ROOT/data")
[ -z "$problem" ] || [ -n "${ALLOW_EXTERNAL_DISK:-}" ] || $ask || die "$problem. Put Hush on an internal disk, or set ALLOW_EXTERNAL_DISK=1 if that is what you want."

if $ask; then
  case $PUBLISH in
    tunnel) how="through the Cloudflare Tunnel on this machine" ;;
    proxy) how="through your own reverse proxy" ;;
    *) how="only on this machine or network for now" ;;
  esac
  summary="Hush will be set up like this:

  Address:  $HOST
  Reached:  $how
  Folder:   $ROOT
  Backups:  ${remote:-off for now}"
  [ -z "$problem" ] || summary="$summary

Careful: $problem. A disk that can be unplugged is a risky home for everything Hush stores."
  ui_yes "Hush" "$summary

Go ahead?" || {
    rmdir "$ROOT/data/blobs" "$ROOT/data/snapshots" "$ROOT/data" "$ROOT" 2>/dev/null || true
    echo "Nothing was changed."
    exit 0
  }
fi
[ -z "$problem" ] || warn "$problem"
ui_progress_open "Setting up Hush"
note "Data: $ROOT/data ($(df -h --output=avail "$ROOT/data" | tail -n1 | tr -d ' ') free)"

# ------------------------------------------------------------------------------------------- the folder
log "Files in $ROOT"
for file in compose.yml update.sh backup.sh restore.sh control.sh lib.sh RUNBOOK.md systemd/hush-control.path systemd/hush-control.service; do
  [ -f "$SRC/$file" ] && ! [ "$SRC/$file" -ef "$ROOT/$file" ] && install -D -m "$([[ $file == *.sh ]] && echo 755 || echo 644)" "$SRC/$file" "$ROOT/$file"
done

# If you keep a PORTS.md beside your stacks (one folder up), Hush's port goes in it and is picked around it.
PORTS_MD=${PORTS_MD:-$(dirname "$ROOT")/PORTS.md}
if [ -z "$port" ]; then
  port=$(ports_port_of "$PORTS_MD" hush)
  if [ -z "$port" ]; then
    port=$(pick_port "$PORTS_MD" "${PORT_RANGE%-*}" "${PORT_RANGE#*-}") || die "no free port in $PORT_RANGE"
    if [ -f "$PORTS_MD" ]; then
      ports_add_row "$PORTS_MD" "$port" "Hush ($HOST), 127.0.0.1 only, $ROOT" "$ROOT" "$HOST"
      note "Port $port recorded in $PORTS_MD"
    fi
  fi
elif port_bound "$port" && ! compose ps --status running -q 2>/dev/null | grep -q .; then
  die "port $port is taken by something else"
fi
note "Port: 127.0.0.1:$port"

if [ ! -f "$ENV_FILE" ]; then
  install -m 600 /dev/null "$ENV_FILE"
  {
    echo "# Hush settings. update.sh manages the HUSH_IMAGE* / COMMIT / VERSION lines."
    echo "HUSH_REPO=$REPO"
    echo "HUSH_VAPID_SUBJECT=mailto:admin@${HOST#*.}"
    echo "HUSH_MIN_FREE_GB=10"
    echo "TZ=$(timedatectl show -p Timezone --value 2>/dev/null || cat /etc/timezone 2>/dev/null || echo UTC)"
  } >>"$ENV_FILE"
fi
env_set "$ENV_FILE" HUSH_HOST_PORT "$port"
env_set "$ENV_FILE" HUSH_HOSTNAME "$HOST"
env_set "$ENV_FILE" HUSH_PUBLISH "$PUBLISH"
env_set "$ENV_FILE" HUSH_HOST_ROOT "$ROOT"
# Whose word the server takes for a visitor's address (it limits sign-in attempts per address): Cloudflare's header
# behind the tunnel, the proxy's behind a proxy, nobody's otherwise.
case $PUBLISH in
  tunnel) env_set "$ENV_FILE" HUSH_CLIENT_IP_HEADER CF-Connecting-IP ;;
  proxy) env_set "$ENV_FILE" HUSH_CLIENT_IP_HEADER X-Forwarded-For ;;
  *) env_set "$ENV_FILE" HUSH_CLIENT_IP_HEADER none ;;
esac

# TURN: only with a Cloudflare API token, and only once.
if [ -z "$(env_get "$ENV_FILE" CF_TURN_KEY_ID)" ] && [ -n "${CLOUDFLARE_API_TOKEN:-}" ] && [ -n "${CLOUDFLARE_ACCOUNT_ID:-}" ]; then
  log "Creating a Cloudflare TURN key"
  answer=$(curl -fsS -X POST "https://api.cloudflare.com/client/v4/accounts/$CLOUDFLARE_ACCOUNT_ID/calls/turn_keys" \
    -H "Authorization: Bearer $CLOUDFLARE_API_TOKEN" -H 'Content-Type: application/json' --data '{"name":"hush"}') ||
    die "Cloudflare refused to create a TURN key (the token needs Calls: Edit)"
  key_id=$(sed -n 's/.*"uid":[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$answer")
  key=$(sed -n 's/.*"key":[[:space:]]*"\([^"]*\)".*/\1/p' <<<"$answer")
  [ -n "$key_id" ] && [ -n "$key" ] || die "unexpected answer from Cloudflare: $answer"
  env_set "$ENV_FILE" CF_TURN_KEY_ID "$key_id"
  env_set "$ENV_FILE" CF_TURN_API_TOKEN "$key"
  note "TURN key $key_id saved in .env"
fi

if [ -n "$remote" ]; then
  env_set "$ENV_FILE" RCLONE_REMOTE "$remote"
  [ -z "$rclone_config" ] || env_set "$ENV_FILE" RCLONE_CONFIG "$rclone_config"
fi

# ------------------------------------------------------------------------------------------- the image
chown -R "$CONTAINER_USER" "$ROOT/data" 2>/dev/null || [ -n "${HUSH_TEST:-}" ] || die "could not hand $ROOT/data to the container user"
HUSH_ROOT=$ROOT HUSH_RELEASE_BASE=$RELEASE_BASE "$ROOT/update.sh" --yes

# ------------------------------------------------------------------------------------------- the address
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
  candidate=$(mktemp "$(dirname "$CF_CONFIG")/.config.hush.XXXXXX")
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
    die "https://$HOST would not reach Hush with the edited config; $CF_CONFIG is unchanged"
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
    die "restored $backup and restarted $CF_SERVICE; Hush is running locally but not published"
  fi
  note "Tunnel restarted; ${#before[@]} existing hostname(s) still answer"

  local tunnel
  tunnel=$(sed -n 's/^tunnel:[[:space:]]*//p' "$CF_CONFIG" | tr -d "\"' ")
  if [ -n "$tunnel" ]; then
    # Under sudo, cloudflared looks for its login (cert.pem) in root's home; `cloudflared tunnel login` usually put it in
    # the invoking user's. Use that one when it's there and nothing else was chosen.
    local login=() home
    home=$(getent passwd "${SUDO_USER:-}" 2>/dev/null | cut -d: -f6 || true)
    if [ -z "${TUNNEL_ORIGIN_CERT:-}" ] && [ -n "$home" ] && [ -f "$home/.cloudflared/cert.pem" ]; then
      login=(env "TUNNEL_ORIGIN_CERT=$home/.cloudflared/cert.pem")
    fi
    if out=$("${login[@]}" cloudflared tunnel route dns "$tunnel" "$HOST" 2>&1); then
      note "DNS: $HOST -> tunnel $tunnel"
    elif grep -qi 'already' <<<"$out"; then
      note "DNS for $HOST already exists"
    else
      # A CNAME has to point at the tunnel's ID; config.yml may name the tunnel instead, so ask its credentials file.
      local id=$tunnel creds
      if ! [[ $id =~ ^[0-9a-f]{8}-([0-9a-f]{4}-){3}[0-9a-f]{12}$ ]]; then
        creds=$(sed -n 's/^credentials-file:[[:space:]]*//p' "$CF_CONFIG" | tr -d "\"' ")
        id=$(sed -n 's/.*"TunnelID"[[:space:]]*:[[:space:]]*"\([0-9a-f-]*\)".*/\1/p' "$creds" 2>/dev/null || true)
      fi
      warn "could not create the DNS record: cloudflared isn't logged in to Cloudflare here (no cert.pem). Either run, as yourself:"
      note "  cloudflared tunnel route dns $tunnel $HOST     (after \`cloudflared tunnel login\` if it asks)"
      note "or add it in the dashboard:"
      if [ -n "$id" ]; then
        note "  DNS for ${HOST#*.}: add a CNAME named ${HOST%%.*}, target $id.cfargotunnel.com, proxied (orange cloud)."
      else
        note "  DNS for ${HOST#*.}: add a CNAME named ${HOST%%.*}, proxied, with the same target as your other tunnel hostnames (<id>.cfargotunnel.com)."
      fi
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

case $PUBLISH in
  tunnel) configure_tunnel ;;
  proxy) note "Hush listens on 127.0.0.1:$port; your proxy does the rest (how is below)" ;;
  *) note "Hush listens on 127.0.0.1:$port, on this machine only" ;;
esac

# ------------------------------------------------------------------------------------------- backups
log "Backups"
sed "s#@ROOT@#$ROOT#g" "$SRC/systemd/hush-backup.service" >"$SYSTEMD_DIR/hush-backup.service"
install -m 644 "$SRC/systemd/hush-backup.timer" "$SYSTEMD_DIR/hush-backup.timer"
systemctl daemon-reload
backups_ready=false
if [ -n "$remote" ] && command -v rclone >/dev/null; then
  systemctl enable --now hush-backup.timer >/dev/null
  backups_ready=true
  note "Nightly at 03:30 to $remote/hush (status in data/backup-status.json and the admin screen)"
else
  systemctl disable --now hush-backup.timer >/dev/null 2>&1 || true
  printf '{"ok":false,"lastRunAt":%s,"lastSuccessAt":null,"message":"Backups are not set up yet: see RUNBOOK.md"}\n' "$(date +%s)" |
    owner_write "$ROOT/data/backup-status.json"
  note "Off: there is no encrypted rclone remote to send them to yet"
fi

# ------------------------------------------------------------------------------------------- server controls
log "Server controls"
"$ROOT/control.sh" install

# ------------------------------------------------------------------------------------------- done
ui_progress_close
invite=$(as_owner cat "$ROOT/data/first-invite.txt" 2>/dev/null | head -c 32 | tr -cd 'A-Z0-9-' || true)
if [ "$PUBLISH" = local ]; then open="http://127.0.0.1:$port"; else open="https://$HOST"; fi
lines=("Open:          $open")
[ -z "$invite" ] || lines+=("First invite:  $invite   (whoever uses it becomes the admin)")
lines+=("Android app:   $open/download/hush.apk" "Update later:  sudo $ROOT/update.sh")
$backups_ready || lines+=("Backups:       OFF until there is an encrypted rclone remote. RUNBOOK.md says how; then run this again.")
[ -n "$(env_get "$ENV_FILE" CF_TURN_KEY_ID)" ] || lines+=("Calls:         direct only. For relayed calls, run this again with CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID.")

echo
log "Hush is installed."
for line in "${lines[@]}"; do note "$line"; done
if [ "$PUBLISH" = proxy ]; then
  echo
  log "Point your reverse proxy at it"
  note "Send https://$HOST to http://127.0.0.1:$port, WebSockets included, with uploads of at least 40 MB allowed."
  note "It must add X-Forwarded-For (most do): Hush refuses its server-only requests when it sees it, and limits"
  note "sign-in attempts by it."
  note ""
  note "Caddy:   $HOST {"
  note "             reverse_proxy 127.0.0.1:$port"
  note "         }"
  note ""
  note "nginx:   location / {"
  note "             proxy_pass http://127.0.0.1:$port;"
  note "             proxy_http_version 1.1;"
  note "             proxy_set_header Host \$host;"
  note "             proxy_set_header Upgrade \$http_upgrade;"
  note "             proxy_set_header Connection \"upgrade\";"
  note "             proxy_set_header X-Forwarded-For \$remote_addr;"
  note "             client_max_body_size 40m;"
  note "         }"
fi
if [ "$HUSH_UI" != cli ]; then
  text="Hush is installed."
  for line in "${lines[@]}"; do text="$text"$'\n'"$line"; done
  [ "$PUBLISH" != proxy ] || text="$text"$'\n\n'"One thing left: point your reverse proxy at http://127.0.0.1:$port. The terminal (and RUNBOOK.md in $ROOT) shows how for Caddy and nginx."
  # Windows use a proportional font: the padding that lines the terminal's columns up only looks ragged there.
  [ "$HUSH_UI" != desktop ] || text=$(sed 's/   */ /g' <<<"$text")
  ui_say "Hush" "$text"
fi
