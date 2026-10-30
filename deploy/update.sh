#!/usr/bin/env bash
# Updates Hush on Resolute to the newest build CI published, after you approve it. No Watchtower: nothing
# changes until you say yes here, and the running image is pinned by digest in .env.
#
#   sudo /opt/hl/hush/update.sh            show what's new, ask, update, health-check, roll back on failure
#   sudo /opt/hl/hush/update.sh --check    only say whether an update is available
#   sudo /opt/hl/hush/update.sh --rollback switch back to the previous image
#   sudo /opt/hl/hush/update.sh --yes      don't ask (install.sh uses this for the first start)
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=deploy/lib.sh
. "$HERE/lib.sh"

ROOT=${HUSH_ROOT:-$HERE}
ENV_FILE=$ROOT/.env
REPO=$(env_get "$ENV_FILE" HUSH_REPO)
REPO=${HUSH_REPO:-${REPO:-Aezdek/Randomness}}
RELEASE_BASE=${HUSH_RELEASE_BASE:-https://github.com/$REPO/releases/download/hush-latest}
HEALTH_SECONDS=${HEALTH_SECONDS:-120}

mode=update
assume_yes=false
for arg in "$@"; do
  case $arg in
    --check) mode=check ;;
    --rollback) mode=rollback ;;
    --yes | -y) assume_yes=true ;;
    *) die "unknown option $arg (see the top of this script)" ;;
  esac
done

[ "$(id -u)" = 0 ] || [ -n "${HUSH_TEST:-}" ] || die "run with sudo"
[ -f "$ENV_FILE" ] || die "$ENV_FILE is missing; run install.sh first"
PORT=$(env_get "$ENV_FILE" HUSH_HOST_PORT)
[ -n "$PORT" ] || die "HUSH_HOST_PORT is not set in $ENV_FILE"

exec 9>"$ROOT/.update.lock"
flock -n 9 || die "another update is running"

current_commit=$(env_get "$ENV_FILE" HUSH_COMMIT)

# Brings the stack up on whatever .env pins and waits for the right version to answer.
start_pinned() {
  local image id
  image=$(env_get "$ENV_FILE" HUSH_IMAGE)
  id=$(env_get "$ENV_FILE" HUSH_IMAGE_ID)
  [ "$(docker image inspect --format '{{.Id}}' "$image" 2>/dev/null)" = "$id" ] ||
    die "$image no longer matches the pinned digest $id; refusing to start it"
  compose up -d --remove-orphans
  wait_healthy "$PORT" "$(env_get "$ENV_FILE" HUSH_VERSION)" "$HEALTH_SECONDS"
}

if [ "$mode" = rollback ]; then
  [ -f "$ROOT/.env.previous" ] || die "there is no previous version to roll back to"
  cp "$ENV_FILE" "$ROOT/.env.rolled-back"
  cat "$ROOT/.env.previous" >"$ENV_FILE"
  log "Rolling back to $(env_get "$ENV_FILE" HUSH_VERSION) ($(env_get "$ENV_FILE" HUSH_COMMIT | cut -c1-12))"
  start_pinned || die "the previous version did not come up either; see: docker compose -f $ROOT/compose.yml logs"
  log "Rolled back. The version you left is in .env.rolled-back."
  exit 0
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

log "Checking $RELEASE_BASE"
curl -fsSL --retry 3 -o "$work/release.env" "$RELEASE_BASE/hush-release.env" ||
  die "could not download the release manifest (has CI published a build yet?)"
commit=$(release_value "$work/release.env" HUSH_COMMIT '[0-9a-f]{40}')
version=$(release_value "$work/release.env" HUSH_VERSION '[0-9A-Za-z.+-]{1,40}')
tar_sha=$(release_value "$work/release.env" HUSH_IMAGE_SHA256 '[0-9a-f]{64}')
tag=$(release_value "$work/release.env" HUSH_IMAGE_TAG 'hush:[0-9A-Za-z._-]{1,64}')
built=$(env_get "$work/release.env" HUSH_BUILT_AT)

if [ "$commit" = "$current_commit" ]; then
  log "Already on the newest build: $version (${commit:0:12})."
  if [ "$mode" = update ] && ! wait_healthy "$PORT" "" 5; then
    log "It isn't answering, so starting it."
    start_pinned || die "Hush did not become healthy; see: docker compose -f $ROOT/compose.yml logs"
  fi
  exit 0
fi

echo
echo "  New build:   $version  (commit ${commit:0:12}, built $built)"
echo "  Image:       sha256:$tar_sha"
if [ -n "$current_commit" ]; then
  echo "  Running:     $(env_get "$ENV_FILE" HUSH_VERSION)  (commit ${current_commit:0:12})"
  echo "  Changes:     https://github.com/$REPO/compare/$current_commit...$commit"
  # Commit titles, best effort (the API is rate-limited and this box may not have python3).
  if command -v python3 >/dev/null; then
    curl -fsSL --max-time 10 "https://api.github.com/repos/$REPO/compare/$current_commit...$commit" 2>/dev/null |
      python3 -c 'import json,sys
try:
    for c in json.load(sys.stdin).get("commits", [])[-20:]:
        print("     -", c["commit"]["message"].splitlines()[0][:100])
except Exception:
    pass' || true
  fi
fi
echo

[ "$mode" = check ] && exit 0

if ! $assume_yes; then
  read -rp "Install this version? [y/N] " answer
  [[ $answer =~ ^[Yy]$ ]] || {
    echo "Nothing changed."
    exit 0
  }
fi

log "Downloading the image"
curl -fL --retry 3 --progress-bar -o "$work/image.tar.gz" "$RELEASE_BASE/hush-image.tar.gz" || die "download failed"
echo "$tar_sha  $work/image.tar.gz" | sha256sum -c --quiet - || die "the download does not match the published digest; nothing changed"

log "Loading it into Docker"
docker load -i "$work/image.tar.gz" >/dev/null
image_id=$(docker image inspect --format '{{.Id}}' "$tag") || die "the archive did not contain $tag"

if [ -n "$current_commit" ]; then
  cp "$ENV_FILE" "$ROOT/.env.previous"
  # A copy of the database from just before the new version touches it (migrations only ever add).
  if [ -f "$ROOT/data/hush.db" ]; then
    compose stop >/dev/null 2>&1 || true
    mkdir -p "$ROOT/data/snapshots"
    cp "$ROOT/data/hush.db" "$ROOT/data/snapshots/pre-update-${current_commit:0:12}.db"
  fi
fi
env_set "$ENV_FILE" HUSH_IMAGE "$tag"
env_set "$ENV_FILE" HUSH_IMAGE_ID "$image_id"
env_set "$ENV_FILE" HUSH_IMAGE_SHA256 "$tar_sha"
env_set "$ENV_FILE" HUSH_COMMIT "$commit"
env_set "$ENV_FILE" HUSH_VERSION "$version"

log "Starting $version"
if start_pinned; then
  log "Hush $version is up on 127.0.0.1:$PORT."
else
  if [ -f "$ROOT/.env.previous" ] && [ -n "$current_commit" ]; then
    warn "the new version did not pass its health check; rolling back"
    compose logs --tail 80 || true
    cp "$ENV_FILE" "$ROOT/.env.failed"
    cat "$ROOT/.env.previous" >"$ENV_FILE"
    start_pinned || die "the previous version did not come back either; see: docker compose -f $ROOT/compose.yml logs"
    die "rolled back to $(env_get "$ENV_FILE" HUSH_VERSION); the failed attempt is described in .env.failed"
  fi
  compose logs --tail 80 || true
  die "Hush did not become healthy"
fi

# Keep the running and the previous image for rollback; drop older ones.
keep="$tag $(env_get "$ROOT/.env.previous" HUSH_IMAGE)"
docker image ls hush --format '{{.Repository}}:{{.Tag}}' | while read -r old; do
  [[ " $keep " == *" $old "* ]] || docker image rm "$old" >/dev/null 2>&1 || true
done
