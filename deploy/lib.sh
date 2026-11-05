# shellcheck shell=bash
# Helpers shared by the Hush deploy scripts. Sourced, never run on its own.
#
# Everything that touches the host (cloudflared, PORTS.md, disks) lives here as small functions so
# deploy/tests/run.sh can exercise each one against fixtures without root or a real tunnel.

log() { printf '\033[1m==>\033[0m %s\n' "$*"; }
note() { printf '    %s\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
die() {
  printf '\033[31merror:\033[0m %s\n' "$*" >&2
  exit 1
}

# Each script's ERR trap: under `set -e` a failure nobody planned for would otherwise end the script without a word.
# Not set here, because deploy/tests/run.sh sources this file too.
stopped() {
  printf '\033[31merror:\033[0m %s stopped at line %s, running: %s\n' "${0##*/}" "$1" "$2" >&2
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

# ingress_insert IN OUT HOST SERVICE
# Writes IN to OUT with a rule for HOST placed directly above the catch-all (`- service: http_status:404`),
# indented exactly like the catch-all itself. This is a text edit, not a YAML round trip, so comments, ordering and
# the formatting lucifer and print rely on stay exactly as they were. Exit status 3: no catch-all found.
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

# ports_add_row FILE PORT NOTE: adds a row to PORTS.md's port table, filling its own columns; keeps the file's
# owner and mode. Starts a table if the file has none.
ports_add_row() {
  local file=$1 port=$2 notes=$3 tmp
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
  awk -v port="$port" -v notes="$notes" '
    function value(name, n) {
      n = tolower(name)
      gsub(/^[ \t]+|[ \t]+$/, "", n)
      if (n ~ /port/) return port
      if (n ~ /^(service|app|name|stack|container|what|project)/) return "hush"
      if (n ~ /(path|dir|folder|location|compose)/) return "/opt/hl/hush"
      if (n ~ /(bind|interface|listen|address|host$)/) return "127.0.0.1"
      if (n ~ /(url|domain|hostname|public|route)/) return "hush.aezdek.xyz"
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

# disk_problem DIR: prints why DIR must not hold Hush's data (USB, removable, the external HDD), or nothing.
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

# ask_password new|existing: from HUSH_SERVER_PASSWORD, else typed at the terminal (never echoed).
ask_password() {
  local first second
  if [ -n "${HUSH_SERVER_PASSWORD:-}" ]; then
    printf '%s' "$HUSH_SERVER_PASSWORD"
    return 0
  fi
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
  local root=$1 port=$2 state code password body answer attempt deadline
  state=$(server_state "$port")
  case $state in
    setup)
      deadline=$((SECONDS + 15))
      until [ -s "$root/data/setup-code.txt" ] || ((SECONDS >= deadline)); do sleep 0.5; done
      code=$(tr -d '[:space:]' <"$root/data/setup-code.txt" 2>/dev/null) || die "no setup code in $root/data/setup-code.txt"
      log "Choose Hush's server password"
      note "It encrypts everything Hush keeps on this machine (who's who, who talks to whom, when)."
      note "You type it once. After that Hush unlocks by itself here; you need it again only to restore a"
      note "backup or move to new hardware. Keep it in your password manager: without it and this machine,"
      note "a backup can't be opened by anyone, you included."
      while :; do
        password=$(ask_password new)
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
        password=$(ask_password existing)
        body=$(json_string "$password") || { warn "no control characters, please"; continue; }
        answer=$(curl -sS -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' --data-binary @- \
          "http://127.0.0.1:$port/api/v1/setup/unlock" <<<"{\"password\":\"$body\"}") || answer=000
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
