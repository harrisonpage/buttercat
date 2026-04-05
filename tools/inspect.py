#!/usr/bin/env python3
"""
Buttercat export format inspector.

Usage:
    inspect.py listen [host:]port   — listen for NDJSON events from the plugin
    inspect.py tiles {export_dir}   — list worlds and count tiles on disk

Examples:
    inspect.py listen 9100          — listen on 0.0.0.0:9100
    inspect.py listen 127.0.0.1:9100
"""

import json
import os
import socket
import sys
import time
from datetime import datetime
from pathlib import Path

COLORS = {
    "hello":     "\033[32m",  # green
    "heartbeat": "\033[90m",  # gray
    "worlds":    "\033[36m",  # cyan
    "players":   "\033[33m",  # yellow
    "tile":      "\033[35m",  # magenta
}
RESET = "\033[0m"
BOLD = "\033[1m"


def format_ts(ts_ms):
    if ts_ms is None:
        return ""
    return datetime.fromtimestamp(ts_ms / 1000).strftime("%H:%M:%S")


def pretty_print(data):
    event_type = data.get("type", "unknown")
    color = COLORS.get(event_type, "")
    ts = format_ts(data.get("ts"))
    ts_str = f" {ts}" if ts else ""

    if event_type == "hello":
        print(f"{color}{BOLD}>>> hello{RESET}{color} v{data.get('version')} "
              f"plugin={data.get('plugin')} hytale={data.get('hytale')}{RESET}")

    elif event_type == "heartbeat":
        print(f"{color}  ~ heartbeat{ts_str}{RESET}")

    elif event_type == "worlds":
        worlds = data.get("worlds", [])
        print(f"{color}{BOLD}worlds{RESET}{color}{ts_str} ({len(worlds)} worlds){RESET}")
        for w in worlds:
            print(f"{color}  {w['name']}: {w.get('player_count', 0)} players, "
                  f"spawn=({w.get('spawn_x', 0):.0f}, {w.get('spawn_z', 0):.0f}){RESET}")

    elif event_type == "players":
        worlds = data.get("worlds", [])
        total = sum(len(w.get("players", [])) for w in worlds)
        print(f"{color}{BOLD}players{RESET}{color}{ts_str} ({total} total){RESET}")
        for w in worlds:
            for p in w.get("players", []):
                print(f"{color}  [{w['name']}] {p['name']} "
                      f"({p['x']:.1f}, {p['y']:.1f}, {p['z']:.1f}) "
                      f"yaw={p.get('yaw', 0):.2f}{RESET}")

    elif event_type == "tile":
        print(f"{color}tile{ts_str} {data.get('world')}/{data.get('renderer')} "
              f"z{data.get('zoom')} ({data.get('x')}, {data.get('z')}){RESET}")

    else:
        print(f"  ? {json.dumps(data)}")


def listen(bind_host, bind_port):
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((bind_host, bind_port))
    server.listen(1)
    print(f"Listening on {bind_host}:{bind_port} — waiting for plugin to connect...")

    while True:
        conn, addr = server.accept()
        print(f"\n{BOLD}Connected: {addr[0]}:{addr[1]}{RESET}")
        buf = b""
        try:
            while True:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        data = json.loads(line)
                        pretty_print(data)
                    except json.JSONDecodeError as e:
                        print(f"  ! bad JSON: {e} — {line[:100]}")
        except (ConnectionResetError, BrokenPipeError):
            pass
        print(f"{BOLD}Disconnected: {addr[0]}:{addr[1]}{RESET}\n")
        conn.close()


def inspect_tiles(export_dir):
    tiles_dir = Path(export_dir) / "tiles"
    if not tiles_dir.exists():
        print(f"No tiles directory found at {tiles_dir}")
        return

    total = 0
    for renderer in sorted(tiles_dir.iterdir()):
        if not renderer.is_dir():
            continue
        for world in sorted(renderer.iterdir()):
            if not world.is_dir():
                continue
            for zoom in sorted(world.iterdir()):
                if not zoom.is_dir():
                    continue
                pngs = list(zoom.glob("*.png"))
                count = len(pngs)
                total += count

                # Find most recent tile
                newest = None
                newest_time = 0
                for p in pngs:
                    mtime = p.stat().st_mtime
                    if mtime > newest_time:
                        newest_time = mtime
                        newest = p

                newest_str = ""
                if newest:
                    age = time.time() - newest_time
                    if age < 60:
                        newest_str = f", newest: {newest.name} ({age:.0f}s ago)"
                    else:
                        newest_str = f", newest: {newest.name} ({age / 60:.0f}m ago)"

                print(f"  {renderer.name}/{world.name}/z{zoom.name}: "
                      f"{count} tiles{newest_str}")

    print(f"\n  Total: {total} tiles")


def main():
    if len(sys.argv) < 2:
        print(__doc__.strip())
        sys.exit(1)

    cmd = sys.argv[1]

    if cmd == "listen":
        addr = sys.argv[2] if len(sys.argv) > 2 else "9100"
        if ":" in addr:
            host, port = addr.rsplit(":", 1)
        else:
            host, port = "0.0.0.0", addr
        listen(host, int(port))
    elif cmd == "tiles":
        export_dir = sys.argv[2] if len(sys.argv) > 2 else "."
        inspect_tiles(export_dir)
    else:
        print(f"Unknown command: {cmd}")
        print(__doc__.strip())
        sys.exit(1)


if __name__ == "__main__":
    main()
