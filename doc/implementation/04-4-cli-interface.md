## 4. CLI Interface

### 4.1 Server

```
Usage: ranmt-server [OPTIONS] --port <PORT>

Options:
    --bind-addr <ADDR>       Address to bind UDP socket to
                             [default: 0.0.0.0]
    --port <PORT>            Port to bind UDP socket to
    --cert <PATH>            Path to TLS certificate (PEM)
    --key <PATH>             Path to TLS private key (PEM)
    --output-dir <DIR>       Directory to write session jsonl files
                             [default: ./sessions]
    -v, --verbose            Enable debug logging
    -h, --help               Print help
```

Validation rules:
- `--port`: **1024-65535**. Required. No default.
- `--cert` + `--key`: If BOTH provided, load from disk. If NEITHER provided, generate dev cert with `rcgen`. If only one, error.
- `--output-dir`: Created on startup if not exists (`fs::create_dir_all`).

### 4.2 Client

```
Usage: ranmt-client <SERVER> [OPTIONS] --direction <DIRECTION>

Arguments:
    <SERVER>                  Server FQDN or IPv4 address

Options:
    -d, --direction <DIR>        Test direction: "dl" or "ul" [required]
    -p, --port <PORT>            Server QUIC port [default: 4433]
    -b, --bitrate <BPS>          Target bitrate in bps [default: 8000]
    -t, --duration <SEC>         Test duration in seconds
                                 [default: 0 = run until Ctrl+C]
    --insecure                   Disable certificate verification
                                 (dev mode only)
    --seed <SEED>                RNG seed for mock telemetry
                                 [default: 42]
    --session-id <UUID>          Existing session UUID to reconnect to
    -v, --verbose                Enable debug logging
    -h, --help                   Print help
```

Validation rules:
- `<SERVER>`: Must resolve to at least one A (IPv4) record. AAAA records ignored.
- `--direction`: Required. Must be `"dl"` or `"ul"`.
- `--bitrate`: **1 000 - 1 000 000** (1 kbps - 1 Mbps).
- `--duration`: **0** = infinite. **> 0** = test stops after N seconds and sends `goodbye`.

### 4.3 Example Usage

```bash
# Server with dev cert
ranmt-server --port 4433

# Server with production cert
ranmt-server --port 4433 \
    --cert /etc/letsencrypt/live/rantest.example.com/fullchain.pem \
    --key /etc/letsencrypt/live/rantest.example.com/privkey.pem \
    --output-dir /data/ranmt-sessions

# DL test - 30 seconds, mock GPS, default 8 kbps
ranmt-client rantest.example.com \
    --direction dl --port 4433 --duration 30 --insecure

# Reproducible mock data (same seed = same telemetry)
ranmt-client 10.0.0.50 --direction dl --seed 12345
```
