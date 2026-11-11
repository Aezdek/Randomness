# Ports on this server

Everything binds to 127.0.0.1 unless noted.

| Port | Service | Bind | Path | Notes |
|------|---------|------|------|-------|
| 2283 | Immich | 0.0.0.0 | /opt/stacks/immich | photos |
| 8080 | photos | 127.0.0.1 | /opt/stacks/photos | tunnel: photos.example.com |
| 8090 | files | 127.0.0.1 | /opt/stacks/files | tunnel: files.example.com |
| 8096 | Jellyfin | 0.0.0.0 | /opt/stacks/jellyfin | media |
| 8470 | wiki | 127.0.0.1 | /opt/stacks/wiki | |
| 11434 | Ollama | 127.0.0.1 | - | LLMs |

## Reserved

- 9000-9010: experiments
