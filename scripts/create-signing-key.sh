#!/usr/bin/env bash
# Creates a release keystore for one app and stores it as GitHub Actions secrets.
#
#   scripts/create-signing-key.sh aezgram     # AEZGRAM_* secrets, aezgram-release.jks
#   scripts/create-signing-key.sh aezprint    # AEZPRINT_* secrets, aezprint-release.jks
#
# Run once on your own machine and keep the .jks and its password somewhere safe: losing them means every phone
# must uninstall before it can install a build signed with a new key. Until AEZGRAM_* secrets exist, the AezGram
# workflow signs with the AEZPRINT_* key (no setup, but a leak of that one key then affects both apps).
set -euo pipefail

APP=$(tr '[:upper:]' '[:lower:]' <<<"${1:-aezgram}")
REPO="${2:-Aezdek/Randomness}"
PREFIX=$(tr '[:lower:]' '[:upper:]' <<<"$APP")
NAME=$(sed 's/^aez/Aez/; s/gram$/Gram/; s/print$/Print/' <<<"$APP")
KEYSTORE="${KEYSTORE:-$APP-release.jks}"
ALIAS="${ALIAS:-$APP}"

[[ $APP =~ ^[a-z][a-z0-9]+$ ]] || { echo "usage: $0 aezgram|aezprint [owner/repo]" >&2; exit 1; }
command -v keytool >/dev/null || { echo "keytool (from a JDK) is required" >&2; exit 1; }
command -v gh >/dev/null || { echo "The GitHub CLI (gh) is required and must be logged in" >&2; exit 1; }

if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists; reusing it."
  read -rsp "Keystore password: " STORE_PASSWORD; echo
else
  STORE_PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD" \
    -dname "CN=$NAME, O=Aezdek"
  echo "Created $KEYSTORE. Store the password below in your password manager:"
  echo "  $STORE_PASSWORD"
fi

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set "${PREFIX}_KEYSTORE_BASE64" --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set "${PREFIX}_KEYSTORE_PASSWORD" --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set "${PREFIX}_KEY_ALIAS" --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set "${PREFIX}_KEY_PASSWORD" --repo "$REPO"
echo "Secrets ${PREFIX}_* set on $REPO. Re-run the $NAME workflow to get a build signed with this key."
