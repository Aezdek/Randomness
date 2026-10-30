# AezGram on Resolute

Everything lives in `/opt/hl/aezgram`:

```
compose.yml   .env (0600: port, pinned image, TURN key, rclone remote)
update.sh  backup.sh  restore.sh  lib.sh  RUNBOOK.md
data/aezgram.db  data/blobs/  data/snapshots/  data/backup-status.json  data/first-invite.txt
```

The container listens on `127.0.0.1:<AEZGRAM_HOST_PORT>` only. `cloudflared` publishes it as `gram.aezdek.xyz`
(the rule sits above the `http_status:404` catch-all in `/etc/cloudflared/config.yml`; install.sh left a
`config.yml.bak-<time>` next to it). The port is recorded in `/opt/hl/PORTS.md`.

## Everyday

| Task | Command |
| --- | --- |
| Update (shows changes, asks, health-checks, rolls back on failure) | `sudo /opt/hl/aezgram/update.sh` |
| Is there an update? | `sudo /opt/hl/aezgram/update.sh --check` |
| Go back to the previous version | `sudo /opt/hl/aezgram/update.sh --rollback` |
| Logs | `docker compose -f /opt/hl/aezgram/compose.yml logs -f` |
| Restart | `docker compose -f /opt/hl/aezgram/compose.yml restart` |
| Back up now | `sudo systemctl start aezgram-backup` then `cat /opt/hl/aezgram/data/backup-status.json` |
| New invite code | in the app: Settings → Admin → New invite |

There is no Watchtower: the running image only changes when you approve `update.sh`. The image is pinned in
`.env` (`AEZGRAM_IMAGE_ID`), and `update.sh` refuses to start an image whose digest doesn't match.

## Backups

All of AezGram's data is ciphertext, so it goes straight to the encrypted rclone remote (`RCLONE_REMOTE` in
`.env`, normally `gdrive-crypt:`), never to the external HDD:

- 03:00 the server writes `data/snapshots/aezgram-DATE.db` (a consistent `VACUUM INTO` copy; 7 kept locally);
- 03:30 `aezgram-backup.timer` uploads the snapshots, syncs `data/blobs`, and copies `.env`;
- deleted media goes to `aezgram/deleted/DATE` and, like snapshots, is kept 30 days remotely.

If the remote doesn't exist yet (backups show as off in the app's admin screen):

```
sudo rclone config
#  n  -> name: gdrive        storage: drive      (sign in with Google in the browser it opens)
#  n  -> name: gdrive-crypt  storage: crypt      remote: gdrive:aezgram-crypt   (generate both passwords)
sudo bash install.sh        # re-run: it finds the remote and enables the timer
```

Keep the crypt passwords in your password manager: without them the backup can't be read.

## Restore

On a fresh disk or machine (Docker, cloudflared and the rclone remote set up):

```
sudo ./restore.sh                      # newest snapshot; or --date 2026-10-28
sudo bash install.sh                   # starts it on the restored data
```

Media deleted after the snapshot's night is brought back from `aezgram/deleted/`, so the snapshot is complete.

## If something is wrong

- **Site down, other tunnel hosts fine:** `docker compose -f /opt/hl/aezgram/compose.yml ps` and `logs`.
- **Tunnel broken after install:** install.sh restores the backup by itself if lucifer/print got worse. To undo
  by hand: `sudo cp /etc/cloudflared/config.yml.bak-<time> /etc/cloudflared/config.yml && sudo systemctl restart cloudflared`.
- **Disk nearly full:** uploads stop below `AEZGRAM_MIN_FREE_GB` (default 10) and admins see a banner.
- **Calls fail on mobile data:** TURN isn't configured. Re-run install.sh with `CLOUDFLARE_API_TOKEN` (Calls: Edit)
  and `CLOUDFLARE_ACCOUNT_ID`.
