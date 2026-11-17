#!/usr/bin/env bash
# One-time setup: makes the data folders, config.toml, and .env (the login the
# iPad uses for the file share). Safe to run again: it never overwrites anything.
set -euo pipefail
cd "$(dirname "$0")"

# When run with sudo, files should still belong to the real user.
uid="${SUDO_UID:-$(id -u)}"
gid="${SUDO_GID:-$(id -g)}"
if [ "$uid" = 0 ]; then
  # Logged in as root (common in LXC containers): don't run the services as root.
  uid=1000
  gid=1000
  echo "Running as root, so the papers will belong to user/group 1000."
fi

mkdir -p data/library data/inbox data/state
if [ ! -f config.toml ]; then
  if [ -d config.toml ]; then
    rmdir config.toml # Docker creates this folder if compose ran before setup
  fi
  cp config.example.toml config.toml
  echo "Created config.toml"
fi
chown "$uid:$gid" data data/library data/inbox data/state config.toml 2>/dev/null || true

if [ -f .env ]; then
  echo ".env already exists, keeping it."
else
  read -rp "Username for the iPad to log in with [papers]: " user
  user="${user:-papers}"
  if ! [[ "$user" =~ ^[a-z_][a-z0-9_-]*$ ]] || [ "$user" = root ]; then
    echo "Use lowercase letters, digits, - and _ only (and not root)." >&2
    exit 1
  fi
  while :; do
    read -rsp "Password: " pw && echo
    read -rsp "Same password again: " pw2 && echo
    if [ "$pw" != "$pw2" ]; then
      echo "Those didn't match, try again."
    elif [ "${#pw}" -lt 8 ]; then
      echo "Use at least 8 characters."
    elif [[ "$pw" == *"'"* ]]; then
      echo "Please pick a password without a ' in it."
    else
      break
    fi
  done
  (
    umask 077
    printf "SMB_USER=%s\nSMB_PASSWORD='%s'\nPUID=%s\nPGID=%s\n" "$user" "$pw" "$uid" "$gid" > .env
  )
  chown "$uid:$gid" .env 2>/dev/null || true
  echo "Created .env"
fi

echo
echo "Done. Start it with:  docker compose up -d --build"
