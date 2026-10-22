#!/usr/bin/env bash
# Creates the release key Salah is signed with and prints the two GitHub secrets to add.
# Run it once. Keep the .jks and its password safe: an APK signed with a different key can't be
# installed over the app, so losing them means uninstalling (export a backup in Settings first).
set -euo pipefail

KEYSTORE="${1:-salah-release.jks}"
ALIAS="salah"

command -v keytool >/dev/null || { echo "keytool (from a JDK) is required" >&2; exit 1; }
if [ -e "$KEYSTORE" ]; then echo "$KEYSTORE already exists; not replacing it." >&2; exit 1; fi

PASSWORD="$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-32)"
keytool -genkeypair -noprompt \
  -keystore "$KEYSTORE" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" \
  -dname "CN=Salah, O=Aezdek"

base64 < "$KEYSTORE" | tr -d '\n' > "$KEYSTORE.base64.txt"
printf '%s' "$PASSWORD" > "$KEYSTORE.password.txt"

echo "Created $KEYSTORE."
echo "Add these repository secrets (Settings > Secrets and variables > Actions > New repository secret):"
echo "  SALAH_KEYSTORE_BASE64    = the contents of $KEYSTORE.base64.txt"
echo "  SALAH_KEYSTORE_PASSWORD  = the contents of $KEYSTORE.password.txt"
echo "Or with the GitHub CLI:"
echo "  gh secret set SALAH_KEYSTORE_BASE64 --repo Aezdek/Randomness < $KEYSTORE.base64.txt"
echo "  gh secret set SALAH_KEYSTORE_PASSWORD --repo Aezdek/Randomness < $KEYSTORE.password.txt"
echo "Then delete the two .txt files and keep the .jks and the password in your password manager."
