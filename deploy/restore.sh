#!/usr/bin/env bash
# Rebuilds Hush's data from the off-site backup, for a new disk or a new machine.
#
#   sudo ./restore.sh                         newest snapshot into /opt/hl/hush
#   sudo ./restore.sh --date 2026-10-28       that night's snapshot
#   sudo ./restore.sh --to /opt/hl/hush --remote gdrive-crypt: [--force]
#
# Afterwards run update.sh --yes (or install.sh on a fresh machine) to start the server on the restored data.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

ROOT=${HUSH_ROOT:-/opt/hl/hush}
REMOTE=${RCLONE_REMOTE:-}
date=""
force=false
while [ $# -gt 0 ]; do
  case $1 in
    --to) ROOT=$2; shift ;;
    --remote) REMOTE=$2; shift ;;
    --date) date=$2; shift ;;
    --force) force=true ;;
    *) die "unknown option $1" ;;
  esac
  shift
done
[ -n "$REMOTE" ] || REMOTE=$(env_get "$ROOT/.env" RCLONE_REMOTE)
REMOTE=${REMOTE:-gdrive-crypt:}
config=$(env_get "$ROOT/.env" RCLONE_CONFIG)
[ -n "$config" ] && [ -z "${RCLONE_CONFIG:-}" ] && export RCLONE_CONFIG=$config
SRC=${REMOTE%/}
[[ $SRC == *: ]] || SRC=$SRC/
SRC=${SRC}hush
DATA=$ROOT/data

[ "$(id -u)" = 0 ] || [ -n "${HUSH_TEST:-}" ] || die "run with sudo"
command -v rclone >/dev/null || die "rclone is not installed"
if [ -f "$DATA/hush.db" ] && ! $force; then
  die "$DATA/hush.db already exists; pass --force to replace it (the current one is kept as hush.db.before-restore)"
fi

if [ -f "$ROOT/compose.yml" ] && [ -f "$ROOT/.env" ] && command -v docker >/dev/null; then
  compose stop >/dev/null 2>&1 || true
fi

snapshots=$(rclone lsf "$SRC/snapshots" --include 'hush-*.db' | sort)
[ -n "$snapshots" ] || die "no snapshots found in $SRC/snapshots"
if [ -n "$date" ]; then
  snapshot="hush-$date.db"
  grep -Fxq "$snapshot" <<<"$snapshots" || die "no snapshot for $date; available: $(tr '\n' ' ' <<<"$snapshots")"
else
  snapshot=$(tail -n1 <<<"$snapshots")
fi
snapshot_day=${snapshot#hush-}
snapshot_day=${snapshot_day%.db}

mkdir -p "$DATA/blobs" "$DATA/snapshots"
if [ -f "$DATA/hush.db" ]; then mv "$DATA/hush.db" "$DATA/hush.db.before-restore"; fi
rm -f "$DATA/hush.db-wal" "$DATA/hush.db-shm"

log "Database: $snapshot"
rclone copyto "$SRC/snapshots/$snapshot" "$DATA/hush.db"

log "Media"
rclone copy "$SRC/blobs" "$DATA/blobs" --transfers 8
# Blobs deleted after the snapshot was taken are still referenced by it: bring those back too.
{ rclone lsf --dirs-only "$SRC/deleted" 2>/dev/null || true; } | tr -d / | sort | while read -r day; do
  [[ $day =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && ! [[ $day < $snapshot_day ]] || continue
  rclone copy "$SRC/deleted/$day" "$DATA/blobs" --ignore-existing
done

if [ ! -e "$DATA/signing" ]; then
  log "Android signing key"
  if ! rclone copy "$SRC/signing" "$DATA/signing" 2>/dev/null || [ ! -s "$DATA/signing/hush.p12" ]; then
    rm -rf "$DATA/signing"
    warn "no signing key in the backup: the server makes a new one, and every phone must reinstall the app once"
  fi
fi

if [ ! -f "$ROOT/.env" ]; then
  log "Settings (.env)"
  rclone copyto "$SRC/env/.env" "$ROOT/.env" 2>/dev/null && chmod 600 "$ROOT/.env" || warn "no .env in the backup; install.sh will create one"
fi

chown -R 10001:10001 "$DATA" 2>/dev/null || [ -n "${HUSH_TEST:-}" ] || warn "could not hand $DATA to the container user (10001)"
log "Restored $snapshot into $ROOT. Start it with: sudo $ROOT/update.sh --yes"
