#!/bin/sh
# Container entrypoint: signs the bundled Android APK with this server's own key, then starts the server.
#
# The key is made here on first start and never leaves Resolute except inside the encrypted backup, so GitHub never
# holds anything that could sign an AezGram update. Phones only accept updates signed by the key they installed
# with, so data/signing/ must survive (backup.sh and restore.sh carry it). Signing runs before the server starts so
# the two JVMs never share the container's 512 MB.
set -eu

DATA=${AEZGRAM_DATA_DIR:-/data}
BUNDLE=${AEZGRAM_BUNDLE_DIR:-/app/apk}
KEYS=$DATA/signing
OUT=${AEZGRAM_APK_DIR:-$DATA/apk}

say() { echo "aezgram-entrypoint: $*" >&2; }

sign_apk() {
  if [ ! -e "$KEYS" ]; then
    # Built in a scratch directory and renamed into place, so an interrupted first start leaves no half-made key.
    say "creating the Android signing key in $KEYS (back this up: backup.sh does)"
    rm -rf "$KEYS.new" && mkdir -m 700 "$KEYS.new" || return 1
    (umask 077 && LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 40 >"$KEYS.new/password") || return 1
    keytool -genkeypair -keystore "$KEYS.new/aezgram.p12" -storetype PKCS12 -storepass:file "$KEYS.new/password" \
      -alias aezgram -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=AezGram, O=aezdek.xyz" >/dev/null 2>&1 || return 1
    chmod 600 "$KEYS.new/aezgram.p12" && mv "$KEYS.new" "$KEYS" || return 1
  elif [ ! -s "$KEYS/aezgram.p12" ] || [ ! -s "$KEYS/password" ]; then
    # Never replace a key silently: phones refuse updates signed by a different one.
    say "$KEYS is incomplete. Restore it from the backup (restore.sh), or delete it to start a new key, after which every phone must reinstall the app once"
    return 1
  fi
  mkdir -p "$OUT" || return 1

  source_sha=$(sha256sum "$BUNDLE/aezgram-unsigned.apk" | cut -c1-64)
  if [ -s "$OUT/aezgram.apk" ] && [ "$(cat "$OUT/source.sha256" 2>/dev/null)" = "$source_sha" ]; then
    return 0
  fi
  say "signing the Android app"
  java -Xmx192m -jar "$BUNDLE/apksigner.jar" sign --ks "$KEYS/aezgram.p12" --ks-pass "file:$KEYS/password" \
    --ks-key-alias aezgram --out "$OUT/aezgram.apk.tmp" "$BUNDLE/aezgram-unsigned.apk" || return 1
  rm -f "$OUT/aezgram.apk.tmp.idsig"
  mv "$OUT/aezgram.apk.tmp" "$OUT/aezgram.apk" || return 1

  VERSION_CODE=0 VERSION_NAME=unknown
  # shellcheck disable=SC1091
  if [ -f "$BUNDLE/build.env" ]; then . "$BUNDLE/build.env"; fi
  sha=$(sha256sum "$OUT/aezgram.apk" | cut -c1-64)
  size=$(stat -c %s "$OUT/aezgram.apk")
  printf '{"versionCode":%s,"versionName":"%s","sha256":"%s","size":%s,"url":"/download/aezgram.apk"}\n' \
    "$VERSION_CODE" "$VERSION_NAME" "$sha" "$size" >"$OUT/version.json.tmp" && mv "$OUT/version.json.tmp" "$OUT/version.json" || return 1
  echo "$source_sha" >"$OUT/source.sha256"
  say "signed Android $VERSION_NAME ($sha)"
}

if [ -f "$BUNDLE/aezgram-unsigned.apk" ] && [ -f "$BUNDLE/apksigner.jar" ]; then
  # A failure here only costs the APK download; the web app and the API still start.
  sign_apk || say "could not sign the Android app; /download/aezgram.apk keeps the previous build, if any"
else
  say "this image has no Android build"
fi

exec "${AEZGRAM_SERVER_BIN:-/app/server/bin/server}" "$@"
