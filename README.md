# buttercat

A live map server for [Hytale](https://hytale.com) dedicated servers. Renders top-down map tiles, tracks player positions in real time and serves an interactive [Leaflet](https://leafletjs.com) map in the browser.

![screenshot](screenshot.png)

## Architecture

Buttercat has two components:

```
Hytale Server                        buttercat-httpd
========================             ========================

 buttercat-export.jar                 Go binary (single file)
 ┌────────────────────┐              ┌───────────────────────┐
 │                    │  PNG tiles   │                       │
 │ Tile renderer      │─────────────>│ Tile server           │
 │  writes to disk    │  on disk     │  serves from disk     │
 │                    │              │                       │
 │ Event pusher ──────│── NDJSON ───>│ Event receiver        │
 │  player positions  │  over TCP    │  TCP :9100            │
 │  world metadata    │ (bidir)      │                       │
 │  tile updates      │              │ HTTP + WebSocket      │
 │                    │              │  :9200                │
 └────────────────────┘              │                       │
                                     │ Leaflet frontend      │
                                     │  embedded in binary   │
                                     └───────────────────────┘
```

The plugin renders map tiles using Hytale's `WorldMapManager` API and writes them as PNG files to an export directory. It pushes events (player positions, world metadata, tile updates) to the map server over a TCP connection using [NDJSON](http://ndjson.org/).

The map server receives these events, serves tiles over HTTP, and forwards player/tile updates to browsers via WebSocket. The Leaflet frontend is embedded in the Go binary.

Tiles are rendered on demand — when a browser requests a tile that doesn't exist on disk, the server asks the plugin to render it.

## Requirements

- Hytale dedicated server (2026.03.26 or later)
- Java 25 (for building the plugin)
- Go 1.21+ (for building the map server)
- Gradle 9.4+ (included via wrapper)

## Building

```bash
# Build the plugin
cd plugin && ./gradlew shadowJar

# Build the map server
cd server && go build -o buttercat-httpd .
```

See `build.sh.example` for a script you can adapt.

## Installation

### Plugin

Copy the built JAR into your Hytale mods directory:

```bash
cp plugin/build/libs/buttercat-export-*.jar /path/to/hytale/mods/
```

The plugin needs an export directory for tile output.

### Map server

Copy the `buttercat-httpd` binary to your server and run it:

```bash
./buttercat-httpd -export /path/to/export -http :9200 -event :9100
```

Flags:

| Flag | Default | Description |
|------|---------|-------------|
| `-export` | `/thin/hytale/export` | Path to the tile export directory |
| `-http` | `:9200` | HTTP listen address |
| `-event` | `:9100` | TCP event listener address |

A systemd unit file is provided in `ansible/buttercat-httpd.service`.

### Deployment with Ansible

Example Ansible files are included for automated deployment:

- `ansible/publish.yml` — playbook that deploys the JAR, binary, and systemd unit
- `ansible/buttercat-httpd.service` — systemd unit file
- `ansible/inventory.ini.example` — inventory template

Copy the example files, fill in your server details, and run:

```bash
cp ansible/inventory.ini.example ansible/inventory.ini
# edit inventory.ini with your server details
ansible-playbook ansible/publish.yml -i ansible/inventory.ini
```

## Reverse proxy (nginx)

The map server listens on HTTP. Use nginx (or similar) to terminate TLS:

```nginx
server {
    server_name map.example.com;
    listen 443 ssl;

    # your SSL cert config here

    location /ws/ {
        proxy_pass http://127.0.0.1:9200;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400s;
    }

    location / {
        proxy_pass http://127.0.0.1:9200;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_buffering off;
    }
}
```

The `/ws/` location block is required for WebSocket support (live player tracking and tile updates).

## Logging

All plugin log messages are prefixed with `[buttercat]`:

```
[ButtercatPlugin] [buttercat] Starting...
[EventPusher]     [buttercat] Connected to event receiver
[TileExporter]    [buttercat] MapImage info: 96x96, palette=212 colors...
```

The map server logs to stdout (or journalctl if running under systemd):

```
Listening on :9200 (HTTP)
Listening on :9100 (events)
Plugin connected: 127.0.0.1:34212
Plugin hello: {"type":"hello","version":1,...}
```

## Endpoints

| Path | Description |
|------|-------------|
| `/` | Map frontend |
| `/tiles` | Tile API (GET single, POST batch) |
| `/worlds` | World metadata JSON |
| `/ws/players` | WebSocket (player positions, tile updates) |
| `/healthcheck` | Returns 200 if plugin is connected, 503 if not |
| `/robots.txt` | Disallow all crawlers |

## License

MIT

## References

Absolutely inspired by [VoxelAtlas](https://github.com/boul2gom/VoxelAtlas) which was archived by the owner on Mar 30, 2026.