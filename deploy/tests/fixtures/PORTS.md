# Ports on Resolute

Everything binds to 127.0.0.1 unless noted.

| Port | Service | Bind | Path | Notes |
|------|---------|------|------|-------|
| 2283 | Immich | 0.0.0.0 | /opt/hl/immich | photos |
| 8080 | lucifer | 127.0.0.1 | /opt/hl/lucifer | tunnel: lucifer.aezdek.xyz |
| 8090 | print | 127.0.0.1 | /opt/hl/print | tunnel: print.aezdek.xyz |
| 8096 | Jellyfin | 0.0.0.0 | /opt/hl/jellyfin | media |
| 8470 | openGym | 127.0.0.1 | /opt/hl/opengym | |
| 11434 | Ollama | 127.0.0.1 | - | LLMs |

## Reserved

- 9000-9010: experiments
