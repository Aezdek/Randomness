#!/bin/sh
# Creates the login from SMB_USER / SMB_PASSWORD, then runs Samba in the foreground.
set -eu

: "${SMB_USER:?SMB_USER is not set. Run ./setup.sh}"
: "${SMB_PASSWORD:?SMB_PASSWORD is not set. Run ./setup.sh}"
PUID="${PUID:-1000}"
PGID="${PGID:-1000}"

case "$SMB_USER" in
  root | [!a-z_]* | *[!a-z0-9_-]*)
    echo "SMB_USER must use lowercase letters, digits, - and _, start with a letter, and not be root" >&2
    exit 1
    ;;
esac

# Files written through the share belong to PUID:PGID, the same owner as the
# papers service, so it can sort them.
if getent group papers >/dev/null; then
  groupmod -o -g "$PGID" papers
else
  groupadd -o -g "$PGID" papers
fi
if id "$SMB_USER" >/dev/null 2>&1; then
  usermod -o -u "$PUID" -g papers "$SMB_USER"
else
  useradd -o -M -u "$PUID" -g papers -s /usr/sbin/nologin "$SMB_USER"
fi
printf '%s\n%s\n' "$SMB_PASSWORD" "$SMB_PASSWORD" | smbpasswd -s -a "$SMB_USER" >/dev/null

install -d -o "$PUID" -g "$PGID" /data/library /data/inbox /data/state
install -d /run/samba

echo "Sharing 'Past Papers' and 'Inbox' as user $SMB_USER"
exec smbd --foreground --no-process-group --debug-stdout
