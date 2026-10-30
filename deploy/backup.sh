#!/usr/bin/env bash
# Nightly off-site backup (aezgram-backup.timer, 03:30). Everything AezGram stores is already ciphertext, so it
# goes straight to the encrypted rclone remote:
#
#   <remote>/aezgram/snapshots/   the server's nightly SQLite snapshots (VACUUM INTO), kept 30 days
#   <remote>/aezgram/blobs/       media, synced incrementally (blobs never change once written)
#   <remote>/aezgram/deleted/DATE blobs deleted that day, kept 30 days so a restore from any snapshot is complete
#   <remote>/aezgram/env/.env     settings and secrets
#
# Every run writes data/backup-status.json; admins see a banner in the app when the last success is over 48 h old.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

ROOT=${AEZGRAM_ROOT:-$HERE}
ENV_FILE=$ROOT/.env
DATA=$ROOT/data
STATUS=$DATA/backup-status.json
REMOTE=${RCLONE_REMOTE:-$(env_get "$ENV_FILE" RCLONE_REMOTE)}
REMOTE=${REMOTE:-gdrive-crypt:}
RETENTION_DAYS=${BACKUP_RETENTION_DAYS:-30}
config=$(env_get "$ENV_FILE" RCLONE_CONFIG)
[ -n "$config" ] && [ -z "${RCLONE_CONFIG:-}" ] && export RCLONE_CONFIG=$config
DEST=${REMOTE%/}
[[ $DEST == *: ]] || DEST=$DEST/
DEST=${DEST}aezgram
FLAGS=(--fast-list --transfers 4 --checkers 8 --retries 5 --low-level-retries 10 --stats-one-line --stats 0)
[ -n "${BACKUP_BWLIMIT:-}" ] && FLAGS+=(--bwlimit "$BACKUP_BWLIMIT")

exec 9>"$ROOT/.backup.lock"
flock -n 9 || die "a backup is already running"

started=$(date +%s)
previous_success=$(sed -n 's/.*"lastSuccessAt":[[:space:]]*\([0-9]*\).*/\1/p' "$STATUS" 2>/dev/null || true)

write_status() {
  local ok=$1 message=$2 success=${3:-$previous_success}
  local tmp
  tmp=$(mktemp "$DATA/.backup-status.XXXXXX")
  message=${message//\\/\\\\}
  message=${message//\"/\\\"}
  printf '{"ok":%s,"lastRunAt":%s,"lastSuccessAt":%s,"remote":"%s","message":"%s"}\n' \
    "$ok" "$started" "${success:-null}" "$DEST" "$message" >"$tmp"
  chmod 644 "$tmp"
  mv "$tmp" "$STATUS"
}

fail() {
  write_status false "$1"
  die "$1"
}

command -v rclone >/dev/null || fail "rclone is not installed"
if [[ $REMOTE == *:* ]] && [[ $REMOTE != /* ]]; then
  rclone listremotes 2>/dev/null | grep -Fxq "${REMOTE%%:*}:" ||
    fail "rclone remote ${REMOTE%%:*}: does not exist yet (see RUNBOOK.md, 'Backups')"
fi

today=$(date +%F)
newest=$(find "$DATA/snapshots" -maxdepth 1 -name 'aezgram-*.db' -type f 2>/dev/null | sort | tail -n1)
[ -n "$newest" ] || fail "no database snapshot yet (the server writes one at 03:00)"

log "Snapshots -> $DEST/snapshots"
rclone copy "$DATA/snapshots" "$DEST/snapshots" --include 'aezgram-*.db' --include 'pre-update-*.db' "${FLAGS[@]}" ||
  fail "uploading snapshots failed"

log "Media -> $DEST/blobs"
rclone sync "$DATA/blobs" "$DEST/blobs" --backup-dir "$DEST/deleted/$today" "${FLAGS[@]}" ||
  fail "syncing media failed"

if [ -f "$ENV_FILE" ]; then
  rclone copyto "$ENV_FILE" "$DEST/env/.env" "${FLAGS[@]}" || fail "uploading .env failed"
fi

log "Pruning anything older than $RETENTION_DAYS days"
rclone delete "$DEST/snapshots" --min-age "${RETENTION_DAYS}d" "${FLAGS[@]}" || warn "pruning snapshots failed"
cutoff=$(date -d "-$RETENTION_DAYS days" +%F)
{ rclone lsf --dirs-only "$DEST/deleted" 2>/dev/null || true; } | tr -d / | while read -r day; do
  [[ $day =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] && [[ $day < $cutoff ]] && rclone purge "$DEST/deleted/$day" || true
done

message="Backed up $(basename "$newest") and media"
[[ $(basename "$newest") == "aezgram-$today.db" ]] || message="$message (today's snapshot was missing; used the newest)"
write_status true "$message" "$(date +%s)"
log "$message to $DEST."
