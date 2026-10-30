# Hush on Resolute

Everything lives in `/opt/hl/hush`:

```
compose.yml   .env (0600: port, pinned image, TURN key, rclone remote)
update.sh  backup.sh  restore.sh  lib.sh  RUNBOOK.md
data/hush.db  data/blobs/  data/snapshots/  data/backup-status.json  data/first-invite.txt
data/signing/ (the Android signing key)  data/apk/ (the signed APK the app updates from)
```

The container listens on `127.0.0.1:<HUSH_HOST_PORT>` only. `cloudflared` publishes it as `hush.aezdek.xyz`
(the rule sits above the `http_status:404` catch-all in `/etc/cloudflared/config.yml`; install.sh left a
`config.yml.bak-<time>` next to it). The port is recorded in `/opt/hl/PORTS.md`.

## Everyday

| Task | Command |
| --- | --- |
| Update (shows changes, asks, health-checks, rolls back on failure) | `sudo /opt/hl/hush/update.sh` |
| Is there an update? | `sudo /opt/hl/hush/update.sh --check` |
| Go back to the previous version | `sudo /opt/hl/hush/update.sh --rollback` |
| Logs | `docker compose -f /opt/hl/hush/compose.yml logs -f` |
| Restart | `docker compose -f /opt/hl/hush/compose.yml restart` |
| Back up now | `sudo systemctl start hush-backup` then `cat /opt/hl/hush/data/backup-status.json` |
| New invite code | in the app: Settings → Server and invites → New invite |

There is no Watchtower: the running image only changes when you approve `update.sh`. The image is pinned in
`.env` (`HUSH_IMAGE_ID`), and `update.sh` refuses to start an image whose digest doesn't match.

## Backups

All of Hush's data is ciphertext, so it goes straight to the encrypted rclone remote (`RCLONE_REMOTE` in
`.env`, normally `gdrive-crypt:`), never to the external HDD:

- 03:00 the server writes `data/snapshots/hush-DATE.db` (a consistent `VACUUM INTO` copy; 7 kept locally);
- 03:30 `hush-backup.timer` uploads the snapshots, syncs `data/blobs`, and copies `.env` and `data/signing/`;
- deleted media goes to `hush/deleted/DATE` and, like snapshots, is kept 30 days remotely.

If the remote doesn't exist yet (backups show as off in the app's admin screen):

```
sudo rclone config
#  n  -> name: gdrive        storage: drive      (sign in with Google in the browser it opens)
#  n  -> name: gdrive-crypt  storage: crypt      remote: gdrive:hush-crypt   (generate both passwords)
sudo bash install.sh        # re-run: it finds the remote and enables the timer
```

Keep the crypt passwords in your password manager: without them the backup can't be read.

## Restore

On a fresh disk or machine (Docker, cloudflared and the rclone remote set up):

```
sudo ./restore.sh                      # newest snapshot; or --date 2026-10-28
sudo bash install.sh                   # starts it on the restored data
```

Media deleted after the snapshot's night is brought back from `hush/deleted/`, so the snapshot is complete.

## The Android signing key

The APK comes out of CI unsigned. When a new image starts, its entrypoint signs the APK with the key in
`data/signing/` (made on the very first start) and puts the result in `data/apk/`, where the app's update screen
and `https://hush.aezdek.xyz/download/hush.apk` find it. GitHub never holds the key.

Phones only accept an update signed with the key they installed with, so **`data/signing/` must survive**:
backup.sh uploads it and restore.sh brings it back. If it's ever lost, the server makes a new one on the next start
and every phone has to uninstall and reinstall the app once (nothing is lost: all data is on the server).

If the logs say `data/signing is incomplete`, the server refused to replace a damaged key: restore it with
`restore.sh`, or, if there's no copy, `sudo rm -r /opt/hl/hush/data/signing` and restart to start a new key.

## If something is wrong

- **Site down, other tunnel hosts fine:** `docker compose -f /opt/hl/hush/compose.yml ps` and `logs`.
- **Tunnel broken after install:** install.sh restores the backup by itself if lucifer/print got worse. To undo
  by hand: `sudo cp /etc/cloudflared/config.yml.bak-<time> /etc/cloudflared/config.yml && sudo systemctl restart cloudflared`.
- **Disk nearly full:** uploads stop below `HUSH_MIN_FREE_GB` (default 10) and admins see a banner.
- **Calls fail on mobile data:** TURN isn't configured. Re-run install.sh with `CLOUDFLARE_API_TOKEN` (Calls: Edit)
  and `CLOUDFLARE_ACCOUNT_ID`.
