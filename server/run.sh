#!/bin/sh
# Starts the Aria proxy. The first run sets up a private Python environment in .venv.
set -e
cd "$(dirname "$0")"
if [ ! -x .venv/bin/python ]; then
    echo "First run: installing yt-dlp and deno into .venv (takes a minute)..."
    python3 -m venv .venv
    .venv/bin/pip install --quiet --upgrade pip
fi
# Keep yt-dlp current. YouTube breaks old versions often.
.venv/bin/pip install --quiet --upgrade -r requirements.txt
exec .venv/bin/python aria_proxy.py "$@"
