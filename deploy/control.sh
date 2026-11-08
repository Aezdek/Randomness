#!/usr/bin/env bash
# The admin panel's hands on this machine. Hush runs in a locked-down container and can't start anything here, so
# when an admin (who has entered the server password) asks for an update, a restart, a backup or an update check,
# the server leaves data/control/request.env; systemd notices it (hush-control.path) and runs this script, which
# does that one job and writes back how it went (data/control/status.env and last.log). Only those four jobs
# exist, chosen from a fixed list: nothing in the request is ever run as a command.
#
#   sudo /opt/hl/hush/control.sh                 run the waiting request (systemd does this)
#   sudo /opt/hl/hush/control.sh web-admin on    turn the web admin back on after it was turned off (or: off)
#   sudo /opt/hl/hush/control.sh invite          make an invite code here, e.g. while the web admin is off
#   sudo /opt/hl/hush/control.sh install         set up the systemd units (update.sh and install.sh run this)
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

ROOT=${HUSH_ROOT:-$HERE}
ENV_FILE=$ROOT/.env
CONTROL=$ROOT/data/control
SYSTEMD_DIR=${SYSTEMD_DIR:-/etc/systemd/system}
CONTAINER_USER=${HUSH_CONTAINER_USER:-10001:10001}

[ "$(id -u)" = 0 ] || [ -n "${HUSH_TEST:-}" ] || die "run with sudo"

# A value from one of the small key=value files the server and this script trade; only safe characters come back.
value() { # value KEY FILE
  sed -n "s/^$1=\([A-Za-z0-9._ :+-]*\)\$/\1/p" "$2" 2>/dev/null | head -n1
}

write_status() { # write_status ID ACTION STATE MESSAGE [STARTED]
  local started=${5:-$(date +%s)} finished=""
  [ "$3" = running ] || finished=$(date +%s)
  local message
  message=$(printf '%s' "$4" | tr -d '\r' | tr '\n' ' ' | sed 's/\x1b\[[0-9;]*m//g; s/[^A-Za-z0-9._ :+()\/,-]//g' | cut -c1-200)
  {
    echo "id=$1"
    echo "action=$2"
    echo "state=$3"
    echo "startedAt=$started"
    [ -z "$finished" ] || echo "finishedAt=$finished"
    echo "message=$message"
  } >"$CONTROL/status.env.tmp"
  chmod 644 "$CONTROL/status.env.tmp"
  mv -f "$CONTROL/status.env.tmp" "$CONTROL/status.env"
}

# The last line a job printed that says something, for the panel ("Hush 1.0.40 is running", "Backed up").
summary() {
  sed 's/\x1b\[[0-9;]*m//g' "$CONTROL/last.log" 2>/dev/null | grep -v '^[[:space:]]*$' | tail -n1 | sed 's/^==> //; s/^ *//'
}

run_request() {
  local request=$CONTROL/request.env
  [ -f "$request" ] || return 0
  local taken=$CONTROL/taken.env
  mv -f "$request" "$taken"
  local id action
  id=$(value id "$taken")
  action=$(value action "$taken")
  rm -f "$taken"
  [[ $id =~ ^[A-Za-z0-9_-]{1,32}$ ]] || id=unknown

  local -a job
  case $action in
    update) job=("$ROOT/update.sh" --yes) ;;
    check) job=("$ROOT/update.sh" --check) ;;
    backup) job=("$ROOT/backup.sh") ;;
    restart) job=(in_subshell restart_hush) ;;
    *)
      write_status "$id" "${action:-unknown}" failed "Not something the admin panel can ask for"
      return 0
      ;;
  esac

  local started
  started=$(date +%s)
  write_status "$id" "$action" running "Started" "$started"
  : >"$CONTROL/last.log"
  chmod 644 "$CONTROL/last.log"
  if "${job[@]}" >>"$CONTROL/last.log" 2>&1; then
    write_status "$id" "$action" "done" "$(summary)" "$started"
  else
    write_status "$id" "$action" failed "$(summary)" "$started"
  fi
  # Keep the end of the output only: the panel shows the last few lines.
  tail -n 60 "$CONTROL/last.log" >"$CONTROL/last.log.tmp" && mv -f "$CONTROL/last.log.tmp" "$CONTROL/last.log"
  chmod 644 "$CONTROL/last.log"
}

# A job that may die() runs in a subshell, so its failure lands in the status rather than ending this script.
in_subshell() { ("$@"); }

restart_hush() {
  log "Restarting Hush"
  compose restart
  local port
  port=$(env_get "$ENV_FILE" HUSH_HOST_PORT)
  if wait_healthy "$port" "" "${HEALTH_SECONDS:-120}"; then
    log "Hush is running again"
  else
    die "Hush did not come back healthy; see: docker compose -f $ROOT/compose.yml logs"
  fi
}

# Hush's own endpoints for this machine only (they refuse anything that came through the tunnel).
local_call() { # local_call PATH [JSON]
  local port body='{}'
  [ $# -lt 2 ] || body=$2
  port=$(env_get "$ENV_FILE" HUSH_HOST_PORT)
  [ -n "$port" ] || die "HUSH_HOST_PORT is not set in $ENV_FILE"
  curl -fsS -X POST -H 'Content-Type: application/json' --data "$body" "http://127.0.0.1:$port/api/v1/local/$1"
}

install_units() {
  install -d -m 755 "$CONTROL"
  chown "$CONTAINER_USER" "$CONTROL" 2>/dev/null || [ -n "${HUSH_TEST:-}" ] || die "could not hand $CONTROL to the container user"
  local src=$HERE/systemd
  [ -f "$src/hush-control.path" ] || src=$ROOT/systemd
  sed "s#@ROOT@#$ROOT#g" "$src/hush-control.service" >"$SYSTEMD_DIR/hush-control.service"
  sed "s#@ROOT@#$ROOT#g" "$src/hush-control.path" >"$SYSTEMD_DIR/hush-control.path"
  systemctl daemon-reload
  systemctl enable --now hush-control.path >/dev/null
  # Tells the server someone is listening, so the panel offers its buttons.
  printf 'installedAt=%s\n' "$(date +%s)" >"$CONTROL/agent"
  chmod 644 "$CONTROL/agent"
  note "Server controls are ready: the admin panel can update, restart, back up and check for updates"
}

case ${1:-run} in
  run) run_request ;;
  web-admin)
    case ${2:-} in
      on) local_call web-admin '{"enabled":true}' >/dev/null && log "The web admin is on" ;;
      off) local_call web-admin '{"enabled":false}' >/dev/null && log "The web admin is off; turn it on again with: sudo $ROOT/control.sh web-admin on" ;;
      *) die "say on or off: sudo $ROOT/control.sh web-admin on" ;;
    esac
    ;;
  invite)
    code=$(local_call invites | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([A-Z0-9-]*\)".*/\1/p')
    [ -n "$code" ] || die "could not make an invite (is Hush full?)"
    host=$(env_get "$ENV_FILE" HUSH_HOSTNAME)
    log "Invite: $code"
    note "Link: https://${host:-hush.aezdek.xyz}/?invite=$code"
    ;;
  install) install_units ;;
  *) die "unknown command $1 (see the top of this script)" ;;
esac
