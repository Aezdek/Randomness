# Running Hush

This is the reference for whoever runs a Hush server. The commands assume Hush is in `/opt/hush`; if you put it
somewhere else, use that folder instead.

## What is where

```
compose.yml   .env (settings: the address, the port, the pinned image, the backup remote)
install.sh's helpers: update.sh  backup.sh  restore.sh  control.sh  lib.sh
secrets/      this machine's key for the database (never backed up)
data/         everything Hush stores:
                hush.db        the database, encrypted
                keyring.json   the database key, sealed (see "The encrypted database")
                blobs/         media, encrypted by whoever sent it
                snapshots/     nightly copies of the database
                signing/       the key that signs the Android app
                apk/           the signed Android app that phones update from
                config.json    the settings changed in the admin panel
```

The server runs in one Docker container and listens on `127.0.0.1` only, on the port in `.env`
(`HUSH_HOST_PORT`). What makes it reachable from outside is whatever you chose at install: the Cloudflare Tunnel
on the machine, or your own reverse proxy.

## Everyday

| To | Run |
| --- | --- |
| Update (shows what changed, asks, checks the new version, goes back if it fails) | `sudo /opt/hush/update.sh` |
| See whether there is an update | `sudo /opt/hush/update.sh --check` |
| Go back to the previous version | `sudo /opt/hush/update.sh --rollback` |
| Read the logs | `docker compose -f /opt/hush/compose.yml logs -f` |
| Restart | `docker compose -f /opt/hush/compose.yml restart` |
| Back up now | `sudo systemctl start hush-backup`, then `cat /opt/hush/data/backup-status.json` |
| Make an invite | In the app: the admin panel, Invites. Or `sudo /opt/hush/control.sh invite` |
| Change the address, or how it is reached | Run `install.sh` again |

Nothing updates by itself. The running image is pinned in `.env`, and `update.sh` refuses to start an image that
does not match the pin.

## Reaching it from outside

**Cloudflare Tunnel.** The installer adds one rule for your address to `/etc/cloudflared/config.yml`, just above
the catch-all rule, and restarts `cloudflared`. Before that it saves a copy of the file next to it
(`config.yml.bak-<time>`) and has `cloudflared` check the edited version. Afterwards it checks that every address
the tunnel already served still answers, and puts the old file back if one stopped.

**Your own reverse proxy.** Point it at `http://127.0.0.1:<port>`. It has to pass WebSockets and allow uploads of
at least 40 MB. It also has to add `X-Forwarded-For`, which most proxies do without being asked: Hush uses it to
limit sign-in attempts per visitor, and to tell visitors apart from its own scripts on the server.

```
# Caddy
hush.example.com {
    reverse_proxy 127.0.0.1:8470
}

# nginx
location / {
    proxy_pass http://127.0.0.1:8470;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header Upgrade $http_upgrade;
    proxy_set_header Connection "upgrade";
    proxy_set_header X-Forwarded-For $remote_addr;
    client_max_body_size 40m;
}
```

If the proxy runs in another container and cannot reach `127.0.0.1` on the host, set `HUSH_BIND` in `.env` to an
address it can reach, and restart.

**Calls.** Video calls go directly between devices when they can. On networks where they cannot, mobile data
especially, they need a relay. Hush can use Cloudflare's: run `install.sh` again with `CLOUDFLARE_API_TOKEN` (a
token allowed to edit Calls) and `CLOUDFLARE_ACCOUNT_ID` set. The relay only carries encrypted traffic.

## The admin panel's server controls

The Server tab of the admin panel (Settings, then Admin, in the Android app) can install an update, restart Hush,
back up now and check for updates. It also holds the server's settings: how many members it takes, how long
invites last, and how much disk space to keep free. Changing anything there asks for the server password, which
unlocks the controls for ten minutes.

Hush cannot run anything on the machine itself. It leaves a request in `data/control/`, and a small helper
(`control.sh`, started by the `hush-control.path` systemd unit) does that one job and reports back. Those four jobs
are the only ones there are.

The panel can also turn itself off, for every admin. After that only the server can turn it back on:

```
sudo /opt/hush/control.sh web-admin on     # turn the admin panel back on
sudo /opt/hush/control.sh web-admin off    # or off, from here
sudo /opt/hush/control.sh invite           # make an invite while it is off
systemctl status hush-control.path         # is the helper listening?
```

## The encrypted database

The database holds what the server needs to know to work: usernames, who follows and messages whom, when, and
which devices are signed in. It is encrypted on disk with a random key. That key is only ever stored sealed, in
`data/keyring.json`, in two ways:

- under the server password you chose when you installed Hush;
- under this machine's own key, in `secrets/`, so that restarts and reboots unlock by themselves. On a machine
  with a TPM that key is sealed to it.

Setting the password, and unlocking the database with it, happen on the server itself: Hush refuses both from
anywhere else. A fresh server stores nothing until the password is set.

You need the password again only when the machine cannot unlock by itself: after a restore, or on new hardware.
Hush then shows as locked. Run `sudo /opt/hush/update.sh`, type the password once, and it seals the key for the
new machine. Keep the password in your password manager. Without it nobody can open a backup, you included.

This protects copies: a copy of `data/` or of a backup is unreadable without the password. It does not protect
against someone with root on the running server, who can read the server's memory. What people post and send is
encrypted on their own devices on top of this, so it stays unreadable even then.

## Backups

Everything Hush stores is already encrypted, and the nightly backup sends it to an rclone remote of the `crypt`
kind, so it is encrypted again on the way.

- At 03:00 the server writes a consistent copy of the database to `data/snapshots/`. It keeps seven.
- At 03:30 `hush-backup.timer` uploads the snapshots, syncs the media, and copies the keyring, `.env` and the
  Android signing key. It never uploads `secrets/`, so a backup opens only with the server password.
- Media deleted since the last run moves to a dated folder on the remote. That and old snapshots are kept for
  thirty days.

If you have no encrypted remote yet, backups show as off in the admin panel. To make one for Google Drive, for
example:

```
sudo rclone config
#  n   name: drive         storage: drive    (sign in when the browser opens)
#  n   name: drive-crypt   storage: crypt    remote: drive:hush   (let it generate both passwords)
sudo bash install.sh       # finds the new remote and turns the nightly backup on
```

Keep the crypt passwords in your password manager too. The backup cannot be read without them.

## Restoring

On a new disk or machine, with Docker and the rclone remote set up:

```
sudo ./restore.sh          # the newest snapshot; --date 2026-10-28 for a particular night
sudo bash install.sh       # starts Hush on the restored data and asks for the server password once
```

Media that was deleted after the snapshot was taken is brought back from the remote's dated folders, so the
restored server is complete as of that night.

## The Android signing key

The Android app is built without a signature. When a new server image starts, it signs the app with the key in
`data/signing/`, which it made the first time it ever started, and puts the result in `data/apk/`. That is what
`/download` hands out and what the app updates itself from. The key never leaves your server except inside your
backup.

A phone only accepts an update signed with the key its app was installed with, so `data/signing/` has to survive.
The backup includes it. If it is ever lost, the server makes a new key the next time it starts, and every phone
has to remove and reinstall the app once. No data is lost, since it all lives on the server.

If the logs say `data/signing is incomplete`, the server has refused to replace a damaged key. Restore it with
`restore.sh`. If there is no copy, delete the folder and restart to begin a new key.

## When something is wrong

- **The site is down.** `docker compose -f /opt/hush/compose.yml ps`, then the logs. If Hush says it is locked,
  run `sudo /opt/hush/update.sh` and enter the server password.
- **The tunnel broke after installing.** The installer puts the old tunnel config back by itself when another
  address stops answering. To do it by hand, copy `config.yml.bak-<time>` over `config.yml` and restart
  `cloudflared`.
- **The disk is nearly full.** Uploads stop when free space falls below the limit in the admin panel's settings
  (10 GB unless you changed it), and admins see a banner.
- **Calls fail on mobile data.** There is no relay. See "Calls" above.
- **The admin panel's buttons are greyed out.** The helper is not installed. Run `sudo /opt/hush/update.sh` once.
