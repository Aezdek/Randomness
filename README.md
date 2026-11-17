# Past Papers

A home lab service that keeps your Cambridge past papers sorted in one place and shares them on your
network, so any paper is a few taps away in Notability. Nothing gets installed on the iPad: it uses the
Files app's built-in **Connect to Server**.

> Lives on its own branch of [Aezdek/Randomness](https://github.com/Aezdek/Randomness), like the other
> small projects.

```
iPad (Files / Notability)  ──SMB──▶  home lab
                                      ├─ Past Papers  (sorted, read-only)
                                      │    └─ Chemistry (0620)/2024/May-June/
                                      │         ├─ Chemistry 2024 May-June P42 MS.pdf
                                      │         └─ Chemistry 2024 May-June P42 QP.pdf
                                      └─ Inbox        (drop anything here; it gets sorted)
```

## What it does

- **Sorts.** Drop PDFs into the **Inbox** share, from the iPad or on the server. Within about half a
  minute they move into **Past Papers** as `Subject (code)/Year/Session/`. Each file gets a name that
  still makes sense as a Notability note title, e.g. `Chemistry 2024 May-June P42 QP.pdf`.
  - Question paper, mark scheme and insert sit side by side.
  - Renamed copies are understood (`0620-S24-QP-42 (1).PDF`).
  - Identical copies are removed.
  - Anything it can't identify goes to `Unsorted`, so nothing is lost.
- **Downloads what's missing (optional).** Point it at the past papers site you use and it fills in your
  subjects and years.
  - Newest papers come first, one request per second.
  - It remembers which papers the site doesn't have, so it doesn't keep asking.
  - It checks once a day for newly published papers.
- **Protects the library.** The Past Papers share is read-only, so nothing gets deleted from the iPad by
  accident.

## Set up on the home lab

You need Docker with Compose.

```bash
git clone --branch pastpapers https://github.com/Aezdek/Randomness.git pastpapers
```

```bash
cd pastpapers && ./setup.sh
```

`setup.sh` asks for a username and password for the iPad to log in with. It saves them in `.env`, which
stays on the server and is ignored by git. It also creates `config.toml` and the `data/` folder.

```bash
docker compose up -d --build
```

## Connect the iPad

1. Open **Files**, tap **⋯** at the top of the sidebar, then **Connect to Server**.
2. Enter `smb://` followed by the home lab's IP address, e.g. `smb://192.168.1.20`.
3. Choose **Registered User** and enter the username and password from `setup.sh`.

**Past Papers** and **Inbox** now show under **Shared** in the sidebar. The iPad remembers the server.

**Into Notability:** use Notability's import and pick the paper from the server under **Shared**. You can
also share a PDF from the Files app to Notability, or drag it across in Split View.

## Add the papers you already have

- **From the iPad:** select them in Files, choose **Move** (or **Copy**), and pick **Inbox** on the
  server. Whole folders work too.
- **On the server:** copy them into `data/inbox/`, e.g. `cp -r ~/papers/* data/inbox/`.

Watch it work with `docker compose logs -f papers`.

## Download missing papers

1. In a browser, open the past papers site you use and copy the link of any paper.
2. In `config.toml`, set `url` to that link with the file name swapped for `{filename}`. If the year or
   subject code is in the link, swap those for `{year}` and `{code}` too. For example
   `https://example-papers.com/igcse/0620/2024/0620_s24_qp_42.pdf` becomes
   `https://example-papers.com/igcse/{code}/{year}/{filename}`.
3. Check that it works:
   ```bash
   docker compose run --rm papers check-source
   ```
4. Start downloading:
   ```bash
   docker compose restart papers
   ```

The first full run covers six subjects from 2018 onwards, so it takes a couple of hours at one request per
second. After that it only asks for new papers.

- **Choosing what to download:** subjects, years, sessions and document types (question papers, mark
  schemes, inserts, examiner reports...) are all set in `config.toml`.
- **Preview first:** `docker compose run --rm papers fetch --dry-run` lists what it would request.
- **Fetch one subject or year now:** `docker compose run --rm papers fetch --subject 0620 --year 2024`.

## Away from home

Install [Tailscale](https://tailscale.com) on the home lab and the iPad, then connect to
`smb://<the home lab's Tailscale IP>`. **Don't** forward port 445 on your router: file sharing should
never be open to the internet.

## Troubleshooting

- **The iPad can't connect.** Check that the iPad is on the same network (or Tailscale) and that the
  home lab's firewall allows port 445. If something else on the server already uses port 445 (another
  Samba or NAS share), `docker compose up` fails with "port is already allocated". Either stop that
  share, or add `data/library` to it instead of running the `share` container.
- **A file stays in the Inbox.** Check `docker compose logs papers`. Files still being copied are left
  alone until they stop changing.
- **A subject has the wrong name.** Add it under `[subject_names]` in `config.toml`, then run
  `docker compose restart papers`. Papers already sorted keep their old folder.
- **Changing the password.** Edit `.env`, then run `docker compose up -d`.

## Development

| Path | What it is |
| --- | --- |
| `pastpapers/names.py` | Reads Cambridge file names (`0620_s24_qp_42.pdf`) and builds the tidy ones |
| `pastpapers/library.py`, `inbox.py` | Filing into the library, watching the Inbox |
| `pastpapers/fetcher.py` | Downloads, with polite pacing and the memory of what's missing |
| `pastpapers/config.py` | `config.toml` reading, with plain-English errors |
| `samba/` | The file share container (Ubuntu 24.04 + Samba, tuned for Apple clients) |

The Python code uses only the standard library (Python 3.11+).

```bash
python -m unittest discover -s tests
```

```bash
./scripts/smoke-test.sh
```

The smoke test builds both containers and talks to the real share with `smbclient`. GitHub Actions runs
both on every push.
