#!/usr/bin/env bash
# Creates the one release keystore AezPrint is signed with and stores it as GitHub Actions secrets.
# Run once on your own machine; keep the generated .jks somewhere safe (losing it means users must
# uninstall before they can install a build signed with a new key).
set -euo pipefail

REPO="${1:-Aezdek/Randomness}"
KEYSTORE="${KEYSTORE:-aezprint-release.jks}"
ALIAS="${ALIAS:-aezprint}"

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
    -dname "CN=AezPrint, O=Aezdek"
  echo "Created $KEYSTORE. Store the password below in your password manager:"
  echo "  $STORE_PASSWORD"
fi

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set AEZPRINT_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set AEZPRINT_KEYSTORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set AEZPRINT_KEY_ALIAS --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set AEZPRINT_KEY_PASSWORD --repo "$REPO"
echo "Secrets set on $REPO. Re-run the AezPrint workflow to get a signed APK."
