# shellcheck shell=bash
# Helpers shared by the Hush deploy scripts. Sourced, never run on its own.
#
# Everything that touches the host (cloudflared, PORTS.md, disks, the questions) lives here as small functions so
# deploy/tests/run.sh can exercise each one against fixtures without root or a real tunnel.

log() {
  printf '\033[1m==>\033[0m %s\n' "$*"
  ui_step "$*"
}
note() { printf '    %s\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
die() {
  printf '\033[31merror:\033[0m %s\n' "$*" >&2
  # With windows there may be no terminal to read this in.
  if [ "${HUSH_UI:-cli}" = desktop ]; then
    ui_progress_close
    ui_say "Hush" "$*" error || true
    HUSH_UI_TOLD=1
  fi
  exit 1
}

# Each script's ERR trap: under `set -e` a failure nobody planned for would otherwise end the script without a word.
# Not set here, because deploy/tests/run.sh sources this file too.
stopped() {
  printf '\033[31merror:\033[0m %s stopped at line %s, running: %s\n' "${0##*/}" "$1" "$2" >&2
}

# For a script's EXIT trap: with windows there may be no terminal to read an error in, so a failure nobody has
# been told about yet (die tells its own) gets a window too.
ui_farewell() { # ui_farewell STATUS
  ui_progress_close
  [ "$1" != 0 ] && [ "${HUSH_UI:-cli}" = desktop ] && [ -z "${HUSH_UI_TOLD:-}" ] || return 0
  ui_say "Hush" "${0##*/} stopped before it finished. Run it in a terminal to see why." error || true
}

# The server runs as this user inside its container, and data/ belongs to it.
CONTAINER_USER=${HUSH_CONTAINER_USER:-10001:10001}

# as_owner COMMAND...: runs one command as the container's user. These scripts run as root, and everything in data/
# is the server's to write: if the server were ever broken into, it could leave a link there and wait for root to
# write, chmod or chown through it, reaching any file on this machine. So root never does those things in data/
# itself; it does them as the data's owner, and the worst a planted link can reach is data/ again.
as_owner() {
  if [ "$(id -u)" = 0 ] && [ -z "${HUSH_TEST:-}" ]; then
    setpriv --reuid="${CONTAINER_USER%%:*}" --regid="${CONTAINER_USER##*:}" --clear-groups -- "$@"
  else
    "$@"
  fi
}

# owner_write FILE [MODE]: stdin becomes FILE (in data/), written as its owner and swapped in whole.
owner_write() {
  as_owner sh -c 'umask 022; cat >"$1.tmp" && chmod "$2" "$1.tmp" && mv -f "$1.tmp" "$1"' sh "$1" "${2:-644}"
}

# env_get FILE KEY: the value of KEY in a KEY=VALUE file, without sourcing it.
env_get() {
  [ -f "$1" ] || return 0
  sed -n "s/^$2=//p" "$1" | tail -n1 | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/"
}

# env_set FILE KEY VALUE: replaces or appends KEY, keeping the file's mode and owner.
env_set() {
  local file=$1 key=$2 value=$3 tmp
  tmp=$(mktemp)
  if [ -f "$file" ]; then grep -v "^$key=" "$file" >"$tmp" || true; fi
  printf '%s=%s\n' "$key" "$value" >>"$tmp"
  if [ -f "$file" ]; then cat "$tmp" >"$file"; else install -m 600 "$tmp" "$file"; fi
  rm -f "$tmp"
}

# ------------------------------------------------------------------------------------------------ asking
#
# The scripts have a few questions (install.sh's, and the server password). HUSH_UI says how they are asked:
#
#   cli      plain questions in the terminal (the default)
#   tui      full-screen menus in the terminal, with whiptail or dialog
#   desktop  windows, with zenity or kdialog, for a machine with a desktop
#
# `--gui` means desktop where there is one and tui otherwise (ui_pick). Every question has a default and can be
# answered ahead of time with a variable, so none of this is needed for an install nobody watches.

# Where a person is, when there is one: prompts and menus use it even while output is being captured.
UI_TTY=${HUSH_TTY:-/dev/tty}

ui_has_terminal() { { : <"$UI_TTY"; } 2>/dev/null; }

# The person at the desktop, whose session the windows open in (root usually can't reach their display).
ui_desktop_user() {
  local user=${HUSH_UI_USER:-${SUDO_USER:-}}
  [ -n "$user" ] && [ "$user" != root ] && id -u "$user" >/dev/null 2>&1 && echo "$user"
}

ui_has_desktop() {
  [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ] && return 0
  # sudo drops WAYLAND_DISPLAY: look for the session's socket instead.
  local user
  user=$(ui_desktop_user) && [ -S "/run/user/$(id -u "$user")/wayland-0" ]
}

# ui_pick: what --gui means here. Sets and exports HUSH_UI.
ui_pick() {
  if ui_has_desktop && { command -v zenity >/dev/null || command -v kdialog >/dev/null; }; then
    HUSH_UI=desktop
  elif ui_has_terminal && { command -v whiptail >/dev/null || command -v dialog >/dev/null; }; then
    HUSH_UI=tui
  else
    warn "no menu program here (whiptail or dialog for a terminal, zenity or kdialog for a desktop); asking in the terminal instead"
    HUSH_UI=cli
  fi
  export HUSH_UI
}

ui_tool() {
  [ -z "${HUSH_UI_TOOL:-}" ] || { echo "$HUSH_UI_TOOL"; return 0; }
  case ${HUSH_UI:-cli} in
    desktop) if command -v zenity >/dev/null; then echo zenity; else echo kdialog; fi ;;
    tui) if command -v whiptail >/dev/null; then echo whiptail; else echo dialog; fi ;;
  esac
}

# ui_window PROGRAM ARGS...: a window, opened as the person at the desktop when this runs as root.
ui_window() {
  local user uid
  if [ "$(id -u)" = 0 ] && [ -z "${HUSH_TEST:-}" ] && user=$(ui_desktop_user); then
    uid=$(id -u "$user")
    setpriv --reuid="$uid" --regid="$(id -g "$user")" --init-groups -- env \
      HOME="$(getent passwd "$user" | cut -d: -f6)" DISPLAY="${DISPLAY:-}" XAUTHORITY="${XAUTHORITY:-}" \
      WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-$([ -S "/run/user/$uid/wayland-0" ] && echo wayland-0)}" \
      XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$uid}" DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-unix:path=/run/user/$uid/bus}" \
      "$@"
  else
    "$@"
  fi
}

# ui_menu ARGS...: whiptail or dialog, on the terminal; what was typed or picked comes back on stdout.
ui_menu() {
  "$(ui_tool)" "$@" 2>&1 <"$UI_TTY" >"$UI_TTY"
}

# ui_rows TEXT EXTRA: how tall a box must be for TEXT (wrapped at 68 columns) plus EXTRA rows of its own.
ui_rows() {
  local line rows=0
  while IFS= read -r line; do rows=$((rows + (${#line} + 67) / 68 + (${#line} == 0))); done <<<"$1"
  echo $((rows + $2))
}

# ui_wrap TEXT: broken into lines of a readable width, for the windows that don't wrap their own text.
ui_wrap() { fold -s -w 66 <<<"$1" | sed 's/[[:space:]]*$//'; }

# ui_say TITLE TEXT [error]: something to read.
ui_say() {
  case ${HUSH_UI:-cli} in
    desktop)
      if [ "$(ui_tool)" = zenity ]; then ui_window zenity "--${3:-info}" --title "$1" --width 520 --text "$2" 2>/dev/null
      else ui_window kdialog --title "$1" "--$([ "${3:-}" = error ] && echo error || echo msgbox)" "$2" 2>/dev/null; fi
      ;;
    tui) ui_menu --title "$1" --msgbox "$2" "$(ui_rows "$2" 6)" 74 || true ;;
    *) printf '\n%s\n' "$2" ;;
  esac
}

# ui_ask TITLE QUESTION [DEFAULT]: one line of text. Fails if the person backs out.
ui_ask() {
  local answer
  case ${HUSH_UI:-cli} in
    desktop)
      if [ "$(ui_tool)" = zenity ]; then answer=$(ui_window zenity --entry --title "$1" --width 520 --text "$(ui_wrap "$2")" --entry-text "${3:-}" 2>/dev/null) || return 1
      else answer=$(ui_window kdialog --title "$1" --inputbox "$(ui_wrap "$2")" "${3:-}" 2>/dev/null) || return 1; fi
      ;;
    tui) answer=$(ui_menu --title "$1" --inputbox "$2" "$(ui_rows "$2" 7)" 74 "${3:-}") || return 1 ;;
    *)
      ui_has_terminal || { printf '%s' "${3:-}"; return 0; }
      printf '\n%s%s: ' "$2" "${3:+ [$3]}" >"$UI_TTY"
      IFS= read -r answer <"$UI_TTY" || return 1
      ;;
  esac
  printf '%s' "${answer:-${3:-}}"
}

# ui_secret TITLE QUESTION: a line that isn't shown as it's typed.
ui_secret() {
  local answer
  case ${HUSH_UI:-cli} in
    desktop)
      if [ "$(ui_tool)" = zenity ]; then answer=$(ui_window zenity --entry --hide-text --title "$1" --width 520 --text "$(ui_wrap "$2")" 2>/dev/null) || return 1
      else answer=$(ui_window kdialog --title "$1" --password "$(ui_wrap "$2")" 2>/dev/null) || return 1; fi
      ;;
    tui) answer=$(ui_menu --title "$1" --passwordbox "$2" "$(ui_rows "$2" 7)" 74) || return 1 ;;
    *)
      printf '%s: ' "$2" >"$UI_TTY"
      IFS= read -rs answer <"$UI_TTY" || return 1
      echo >"$UI_TTY"
      ;;
  esac
  printf '%s' "$answer"
}

# ui_choose TITLE QUESTION DEFAULT TAG LABEL [TAG LABEL]...: one of several; prints the TAG picked.
ui_choose() {
  local title=$1 question=$2 default=$3 answer i n
  shift 3
  local -a rows=()
  case ${HUSH_UI:-cli} in
    desktop)
      if [ "$(ui_tool)" = zenity ]; then
        for ((i = 1; i <= $#; i += 2)); do rows+=("$([ "${!i}" = "$default" ] && echo TRUE || echo FALSE)" "${!i}" "${@:i+1:1}"); done
        answer=$(ui_window zenity --list --radiolist --title "$title" --text "$question" --width 620 --height $((230 + 45 * $# / 2)) \
          --column "" --column tag --column "" --hide-column 2 --print-column 2 --hide-header "${rows[@]}" 2>/dev/null) || return 1
      else
        for ((i = 1; i <= $#; i += 2)); do rows+=("${!i}" "${@:i+1:1}" "$([ "${!i}" = "$default" ] && echo on || echo off)"); done
        answer=$(ui_window kdialog --title "$title" --radiolist "$question" "${rows[@]}" 2>/dev/null) || return 1
      fi
      ;;
    tui)
      for ((i = 1; i <= $#; i += 2)); do rows+=("${!i}" "${@:i+1:1}"); done
      # The same option, spelled differently by the two programs.
      answer=$(ui_menu --title "$title" "$([ "$(ui_tool)" = dialog ] && echo --no-tags || echo --notags)" --default-item "$default" \
        --menu "$question" "$(ui_rows "$question" $((${#rows[@]} / 2 + 7)))" 78 "$((${#rows[@]} / 2))" "${rows[@]}") || return 1
      ;;
    *)
      ui_has_terminal || { printf '%s' "$default"; return 0; }
      {
        printf '\n%s\n' "$question"
        n=0
        for ((i = 1; i <= $#; i += 2)); do
          n=$((n + 1))
          printf '  %d) %s\n' "$n" "${@:i+1:1}"
          [ "${!i}" = "$default" ] && answer=$n
        done
      } >"$UI_TTY"
      local pick
      while :; do
        printf 'Choose [%s]: ' "${answer:-1}" >"$UI_TTY"
        IFS= read -r pick <"$UI_TTY" || return 1
        pick=${pick:-${answer:-1}}
        [[ $pick =~ ^[0-9]+$ ]] && [ "$pick" -ge 1 ] && [ "$pick" -le "$n" ] && break
      done
      i=$((pick * 2 - 1))
      answer=${!i}
      ;;
  esac
  [ -n "$answer" ] || return 1
  printf '%s' "$answer"
}

# ui_yes TITLE QUESTION: yes or no (no, if the person backs out).
ui_yes() {
  local answer
  case ${HUSH_UI:-cli} in
    desktop)
      if [ "$(ui_tool)" = zenity ]; then ui_window zenity --question --title "$1" --width 520 --text "$2" 2>/dev/null
      else ui_window kdialog --title "$1" --yesno "$2" 2>/dev/null; fi
      ;;
    tui) ui_menu --title "$1" --yesno "$2" "$(ui_rows "$2" 6)" 74 >/dev/null ;;
    *)
      ui_has_terminal || return 0
      printf '\n%s [Y/n] ' "$2" >"$UI_TTY"
      IFS= read -r answer <"$UI_TTY" || return 1
      [[ ${answer:-y} =~ ^[Yy] ]]
      ;;
  esac
}

# With windows there may be no terminal to watch, so the steps (every `log` line) show in a small progress window.
# ui_progress_open TITLE opens it; ui_progress_close closes it. Scripts started from here write to it too.
ui_progress_open() {
  [ "${HUSH_UI:-cli}" = desktop ] && [ "$(ui_tool)" = zenity ] || return 0
  exec {HUSH_UI_STEPS}> >(ui_window zenity --progress --pulsate --no-cancel --auto-close --title "$1" --width 520 --text "Starting" 2>/dev/null || true)
  export HUSH_UI_STEPS
}

ui_step() {
  [ -n "${HUSH_UI_STEPS:-}" ] || return 0
  { printf '# %s\n' "$1" >&"$HUSH_UI_STEPS"; } 2>/dev/null || true
}

ui_progress_close() {
  [ -n "${HUSH_UI_STEPS:-}" ] || return 0
  { exec {HUSH_UI_STEPS}>&-; } 2>/dev/null || true
  unset HUSH_UI_STEPS
}

# ------------------------------------------------------------------------------------------- cloudflared

# ingress_hosts FILE: every hostname the tunnel config routes, one per line.
ingress_hosts() {
  { grep -E '^[[:space:]]*-?[[:space:]]*hostname:' "$1" || true; } |
    sed -E 's/^[^:]*hostname:[[:space:]]*//; s/[[:space:]]*(#.*)?$//; s/^["'\'']//; s/["'\'']$//'
}

# ingress_has_host FILE HOST
ingress_has_host() {
  ingress_hosts "$1" | grep -Fxq "$2"
}

# ingress_host_for FILE PORT: the hostname the tunnel config routes to this machine's PORT, if any.
ingress_host_for() {
  [ -f "$1" ] || return 0
  awk -v port="$2" '
    /^[[:space:]]*-?[[:space:]]*hostname:/ { host = $0; sub(/^[^:]*hostname:[[:space:]]*/, "", host); sub(/[[:space:]]*(#.*)?$/, "", host); gsub(/["\047]/, "", host) }
    /^[[:space:]]*-?[[:space:]]*service:/ { if ($0 ~ "(127\\.0\\.0\\.1|localhost):" port "([^0-9]|$)" && host != "") { print host; exit } host = "" }
  ' "$1"
}

# ingress_insert IN OUT HOST SERVICE
# Writes IN to OUT with a rule for HOST placed directly above the catch-all (`- service: http_status:404`),
# indented exactly like the catch-all itself. This is a text edit, not a YAML round trip, so comments, ordering and
# the formatting of the rules already there stay exactly as they were. Exit status 3: no catch-all found.
ingress_insert() {
  local in=$1 out=$2 host=$3 service=$4
  awk -v host="$host" -v service="$service" '
    { lines[NR] = $0 }
    END {
      # The catch-all is the last rule: a list item whose first key is service, with no hostname or path after it.
      catch = 0
      for (i = 1; i <= NR; i++) {
        if (lines[i] ~ /^[[:space:]]*-[[:space:]]*service:[[:space:]]*["\047]?http_status:404/ &&
            !(i < NR && lines[i + 1] ~ /^[[:space:]]*(hostname|path):/)) catch = i
      }
      if (!catch) exit 3
      # Mirror the catch-all exactly ("  - " or "-   "), so the new keys line up whatever the style.
      match(lines[catch], /^[[:space:]]*-[[:space:]]*/)
      prefix = substr(lines[catch], 1, RLENGTH)
      keys = sprintf("%" RLENGTH "s", "")
      # A comment block sitting right on top of the catch-all belongs to it: go above that too.
      at = catch
      while (at > 1 && lines[at - 1] ~ /^[[:space:]]*#/) at--
      for (i = 1; i <= NR; i++) {
        if (i == at) {
          print prefix "hostname: " host
          print keys "service: " service
        }
        print lines[i]
      }
    }' "$in" >"$out"
}

# http_status URL: the status code, or 000 when unreachable.
http_status() {
  curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$1" 2>/dev/null || true
}

# A hostname "works" if Cloudflare reached the origin: anything except no answer, 502–504 and 52x/53x.
status_ok() {
  case "$1" in 000 | 502 | 503 | 504 | 52? | 53?) return 1 ;; *) return 0 ;; esac
}

# ------------------------------------------------------------------------------------------------ ports

# ports_listed FILE: every number in PORTS.md's table rows (a superset of the ports in use, which is fine).
ports_listed() {
  [ -f "$1" ] || return 0
  { grep -E '^[[:space:]]*\|' "$1" || true; } | { grep -Eo '\b[0-9]{2,5}\b' || true; } | sort -un
}

# ports_port_of FILE NAME: the port on the row that mentions NAME, if PORTS.md has one.
ports_port_of() {
  [ -f "$1" ] || return 0
  { grep -Ei "^[[:space:]]*\|.*\b$2\b" "$1" || true; } | { grep -Eo '\b[0-9]{4,5}\b' || true; } | head -n1
}

port_bound() {
  [ -n "$(ss -ltnH "sport = :$1" 2>/dev/null)" ]
}

# pick_port FILE FIRST LAST: the first port in [FIRST, LAST] that PORTS.md doesn't list and nothing listens on.
pick_port() {
  local file=$1 port listed
  listed=$(ports_listed "$file")
  for ((port = $2; port <= $3; port++)); do
    grep -qx "$port" <<<"$listed" && continue
    port_bound "$port" && continue
    echo "$port"
    return 0
  done
  return 1
}

# ports_add_row FILE PORT NOTE [FOLDER] [ADDRESS]: adds a row to PORTS.md's port table, filling its own columns;
# keeps the file's owner and mode. Starts a table if the file has none.
ports_add_row() {
  local file=$1 port=$2 notes=$3 folder=${4:--} address=${5:--} tmp
  if [ ! -f "$file" ] || ! grep -Eiq '^[[:space:]]*\|.*port' "$file"; then
    {
      [ -s "$file" ] && echo
      echo "| Port | Service | Notes |"
      echo "| --- | --- | --- |"
      echo "| $port | hush | $notes |"
    } >>"$file"
    return 0
  fi
  tmp=$(mktemp)
  awk -v port="$port" -v notes="$notes" -v folder="$folder" -v address="$address" '
    function value(name, n) {
      n = tolower(name)
      gsub(/^[ \t]+|[ \t]+$/, "", n)
      if (n ~ /port/) return port
      if (n ~ /^(service|app|name|stack|container|what|project)/) return "hush"
      if (n ~ /(path|dir|folder|location|compose)/) return folder
      if (n ~ /(bind|interface|listen|address|host$)/) return "127.0.0.1"
      if (n ~ /(url|domain|hostname|public|route)/) return address
      if (n ~ /(note|desc|purpose|comment|use|what)/) return notes
      return "-"
    }
    { lines[NR] = $0 }
    END {
      for (i = 1; i <= NR; i++) if (lines[i] ~ /^[ \t]*\|/ && tolower(lines[i]) ~ /port/) { head = i; break }
      last = head
      while (last < NR && lines[last + 1] ~ /^[ \t]*\|/) last++
      n = split(lines[head], cells, "|")
      row = "|"
      for (c = 2; c <= n; c++) {
        if (c == n && cells[c] ~ /^[ \t]*$/) break
        row = row " " value(cells[c]) " |"
      }
      for (i = 1; i <= NR; i++) { print lines[i]; if (i == last) print row }
    }' "$file" >"$tmp"
  cat "$tmp" >"$file"
  rm -f "$tmp"
}

# ------------------------------------------------------------------------------------------------ disks

# disk_problem DIR: prints why DIR is a poor place for Hush's data (a USB or removable disk, or under
# EXTERNAL_HDD_MOUNT), or nothing.
disk_problem() {
  local dir=$1 real source disk TRAN RM HOTPLUG
  real=$(realpath -m "$dir")
  if [ -n "${EXTERNAL_HDD_MOUNT:-}" ]; then
    case "$real/" in "$(realpath -m "$EXTERNAL_HDD_MOUNT")"/*)
      echo "$dir is on the external HDD ($EXTERNAL_HDD_MOUNT)"
      return 0
      ;;
    esac
  fi
  source=$(findmnt -n -o SOURCE --target "$real" 2>/dev/null | head -n1)
  source=${source%%\[*}
  [ -b "$source" ] || return 0
  # Walk down through partitions, LVM and LUKS to the physical disk(s).
  while read -r disk; do
    TRAN="" RM="" HOTPLUG=""
    eval "$(lsblk -dnP -o TRAN,RM,HOTPLUG "$disk" 2>/dev/null)"
    if [ "$TRAN" = usb ] || [ "$RM" = 1 ] || [ "$HOTPLUG" = 1 ]; then
      echo "$dir is on $disk, a removable or USB disk"
      return 0
    fi
  done < <(lsblk -slnp -o NAME,TYPE "$source" 2>/dev/null | awk '$2 == "disk" { print $1 }')
}

# --------------------------------------------------------------------------------------------- releases

# release_value FILE KEY PATTERN: a value from the release manifest, only if it matches PATTERN (the file comes
# from the network, so it is parsed, never sourced).
release_value() {
  local value
  value=$(env_get "$1" "$2")
  [[ $value =~ ^$3$ ]] || die "release manifest: $2 is missing or malformed"
  echo "$value"
}

compose() {
  docker compose --project-directory "$ROOT" -f "$ROOT/compose.yml" --env-file "$ROOT/.env" "$@"
}

# wait_healthy PORT VERSION SECONDS: the server answers /healthz with that version.
wait_healthy() {
  local port=$1 version=$2 deadline=$((SECONDS + $3)) body
  while ((SECONDS < deadline)); do
    body=$(curl -fsS --max-time 3 "http://127.0.0.1:$port/healthz" 2>/dev/null || true)
    if [[ $body == *'"ok":true'* ]] && { [ -z "$version" ] || [[ $body == *"\"version\":\"$version\""* ]]; }; then
      return 0
    fi
    sleep "${HEALTH_POLL_SECONDS:-2}"
  done
  return 1
}

# --------------------------------------------------------------------------------------- database keys
#
# Hush's database is encrypted (AezCrypt keyring, see server/.../Vault.kt). Its key is sealed under the server
# password you choose once, and under this machine's key, so restarts unlock by themselves. The machine key never
# goes in the data folder or the backups: a copy of either is useless without the server password.

# tpm_available: systemd-creds can seal to this machine's TPM.
tpm_available() {
  [ -z "${HUSH_NO_TPM:-}" ] && command -v systemd-creds >/dev/null && systemd-creds has-tpm2 >/dev/null 2>&1
}

# ensure_machine_key ROOT [SYSTEMD_DIR]: makes this machine's key once, and points the container at it. With a TPM it
# is sealed to it (hush-unlock.service unseals it into /run/hush at boot, before Docker starts); otherwise it's a file
# only root and the container's user can read.
ensure_machine_key() {
  local root=$1 units=${2:-${SYSTEMD_DIR:-/etc/systemd/system}} dir=$1/secrets key
  install -d -m 700 "$dir"
  if [ ! -s "$dir/machine.key" ] && [ ! -s "$dir/machine.key.cred" ]; then
    key=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
    if tpm_available; then
      printf '%s\n' "$key" | systemd-creds encrypt --with-key=tpm2 --name=hush-machine-key - "$dir/machine.key.cred"
      note "This machine's database key is sealed to its TPM"
    else
      (umask 077 && printf '%s\n' "$key" >"$dir/machine.key")
      note "This machine's database key is in $dir (root only)"
    fi
    unset key
  fi
  if [ -s "$dir/machine.key.cred" ]; then
    # Unseals it into /run/hush (memory only) at boot, before Docker starts the container.
    cat >"$units/hush-unlock.service" <<UNIT
[Unit]
Description=Hush: unseal this machine's database key
After=local-fs.target
Before=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
UMask=0077
ExecStart=/usr/bin/install -d -m 700 -o 10001 -g 10001 /run/hush
ExecStart=/usr/bin/systemd-creds decrypt --name=hush-machine-key $dir/machine.key.cred /run/hush/machine.key
ExecStart=/usr/bin/chown 10001:10001 /run/hush/machine.key

[Install]
WantedBy=multi-user.target
UNIT
    systemctl daemon-reload
    systemctl enable hush-unlock.service >/dev/null 2>&1 || true
    systemctl restart hush-unlock.service || warn "could not unseal the machine key from the TPM; Hush will ask for its server password"
    env_set "$root/.env" HUSH_SECRETS_DIR /run/hush
  else
    chmod 400 "$dir/machine.key"
    chown 10001:10001 "$dir" "$dir/machine.key" 2>/dev/null || [ -n "${HUSH_TEST:-}" ] || warn "could not hand $dir to the container user"
    env_set "$root/.env" HUSH_SECRETS_DIR "$dir"
  fi
}

# server_state PORT: setup, locked or ready (empty for a server from before encryption, or none answering).
server_state() {
  curl -fsS --max-time 5 "http://127.0.0.1:$1/api/v1/setup" 2>/dev/null | sed -n 's/.*"state":"\([a-z]*\)".*/\1/p'
}

# json_string TEXT: TEXT as a JSON string body (no quotes); fails on control characters.
json_string() {
  local s=$1
  [[ $s != *[[:cntrl:]]* ]] || return 1
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  printf '%s' "$s"
}

# local_key ROOT: what proves to the server that a request comes from this machine (it keeps it in data/local.key).
local_key() {
  as_owner cat "$1/data/local.key" 2>/dev/null | head -c 64 | tr -cd '0-9a-f'
}

# ask_password new|existing [ABOUT]: from HUSH_SERVER_PASSWORD, else typed (never shown) at the terminal or in a box.
ask_password() {
  local first second about=${2:+$2$'\n\n'}
  if [ -n "${HUSH_SERVER_PASSWORD:-}" ]; then
    printf '%s' "$HUSH_SERVER_PASSWORD"
    return 0
  fi
  case ${HUSH_UI:-cli} in
    tui | desktop)
      while :; do
        first=$(ui_secret "Hush" "${about}Server password") || die "no password given"
        if [ "$1" = new ]; then
          [ ${#first} -ge 12 ] || { ui_say "Hush" "Use at least 12 characters."; continue; }
          second=$(ui_secret "Hush" "The same password again, to be sure") || die "no password given"
          [ "$first" = "$second" ] || { ui_say "Hush" "Those two don't match. Once more."; continue; }
        fi
        printf '%s' "$first"
        return 0
      done
      ;;
  esac
  { : </dev/tty; } 2>/dev/null || die "Hush needs its server password: run this in a terminal on the server (or set HUSH_SERVER_PASSWORD)"
  while :; do
    read -rsp "    Server password: " first </dev/tty || die "no password given"
    echo >&2
    if [ "$1" = new ]; then
      [ ${#first} -ge 12 ] || { warn "use at least 12 characters"; continue; }
      read -rsp "    Again, to be sure: " second </dev/tty || die "no password given"
      echo >&2
      [ "$first" = "$second" ] || { warn "they don't match; once more"; continue; }
    fi
    printf '%s' "$first"
    return 0
  done
}

# ensure_unlocked ROOT PORT: sets the server password (a fresh server, or one from before encryption) or enters it
# (restored or on new hardware), here on the server itself, so it never crosses the internet. Then waits for Hush.
ensure_unlocked() {
  local root=$1 port=$2 state code password body answer attempt deadline about
  state=$(server_state "$port")
  case $state in
    setup)
      deadline=$((SECONDS + 15))
      until [ -s "$root/data/setup-code.txt" ] || ((SECONDS >= deadline)); do sleep 0.5; done
      # Read as the data's owner and kept to what a setup code can hold, so nothing else could ever be sent as one.
      code=$(as_owner cat "$root/data/setup-code.txt" 2>/dev/null | head -c 64 | tr -cd 'A-Za-z0-9-')
      [ -n "$code" ] || die "no setup code in $root/data/setup-code.txt"
      log "Choose Hush's server password"
      note "It encrypts everything Hush keeps on this machine (who's who, who talks to whom, when)."
      note "You type it once. After that Hush unlocks by itself here; you need it again only to restore a"
      note "backup or move to new hardware. Keep it in your password manager: without it and this machine,"
      note "a backup can't be opened by anyone, you included."
      about="It encrypts everything Hush keeps on this machine. You type it once: after that Hush unlocks by itself here. You need it again only to restore a backup or move to new hardware, so keep it in your password manager."
      while :; do
        password=$(ask_password new "$about")
        body=$(json_string "$password") || { warn "no control characters, please"; [ -n "${HUSH_SERVER_PASSWORD:-}" ] && die "HUSH_SERVER_PASSWORD has control characters"; continue; }
        answer=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary @- \
          "http://127.0.0.1:$port/api/v1/setup" <<<"{\"code\":\"$code\",\"password\":\"$body\"}") || answer=000
        [ "$answer" = 200 ] && break
        [ -n "${HUSH_SERVER_PASSWORD:-}" ] && die "the server refused the setup ($answer)"
        warn "the server refused it ($answer); try again"
      done
      ;;
    locked)
      log "Hush is locked"
      note "This machine can't unseal the database key by itself (a restore, or new hardware)."
      note "Enter the server password you chose when you set Hush up."
      for attempt in 1 2 3 4 5; do
        password=$(ask_password existing "Hush is locked: this machine can't open its database by itself (after a restore, or on new hardware). Enter the server password you chose when you set Hush up.")
        body=$(json_string "$password") || { warn "no control characters, please"; continue; }
        answer=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -H "X-Hush-Local: $(local_key "$root")" \
          --data-binary @- "http://127.0.0.1:$port/api/v1/setup/unlock" <<<"{\"password\":\"$body\"}") || answer=000
        [ "$answer" = 200 ] && break
        [ -n "${HUSH_SERVER_PASSWORD:-}" ] && die "that isn't the server password"
        warn "that isn't it ($answer)"
        sleep 2
        [ "$attempt" = 5 ] && die "still locked; run sudo $root/update.sh again when you have the password"
      done
      ;;
    *) return 0 ;;
  esac
  unset password body
  deadline=$((SECONDS + ${UNLOCK_SECONDS:-120}))
  until [ "$(server_state "$port")" = ready ]; do
    ((SECONDS < deadline)) || die "Hush took too long to start after unlocking; see: docker compose -f $root/compose.yml logs"
    sleep "${HEALTH_POLL_SECONDS:-2}"
  done
  note "Unlocked: the database is encrypted, and this machine opens it by itself from now on"
}
