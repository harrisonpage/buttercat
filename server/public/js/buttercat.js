'use strict';

const CONFIG = {
    CHUNK_SIZE: 32,
    TILE_SIZE: 96,
    BATCH_DELAY: 300,
    MAX_BATCH_SIZE: 2000,
    ENDPOINTS: {
        TILES: '/tiles',
        WORLDS: '/worlds',
        WEBSOCKET: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws/players`
    }
};

const SCALE = CONFIG.TILE_SIZE / CONFIG.CHUNK_SIZE;

function throttle(func, limit) {
    let in_throttle;
    return function () {
        const args = arguments;
        const context = this;
        if (!in_throttle) {
            func.apply(context, args);
            in_throttle = true;
            setTimeout(() => in_throttle = false, limit);
        }
    }
}

function escape_html(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

function rad_to_deg(rad) {
    return rad * (180 / Math.PI);
}

// -- Custom Leaflet Layer --

L.TileLayer.Batch = L.TileLayer.extend({
    options: {
        batch_delay: CONFIG.BATCH_DELAY,
        max_batch_size: CONFIG.MAX_BATCH_SIZE,
        batch_endpoint: CONFIG.ENDPOINTS.TILES
    },

    initialize: function (url_template, options) {
        L.TileLayer.prototype.initialize.call(this, url_template, options);
        this._pending_tiles = new Map();
        this._batch_timer = null;
        this._empty_tile_url = null;
        this._is_sending = false;
        this._queued_while_sending = new Map();

        this._world_name = 'world';
        this._renderer = 'FLAT';
    },

    updateWorld: function (world) {
        if (world && this._world_name !== world) {
            this._world_name = world;
            this.redraw();
        }
    },

    getTileUrl: function (coords) {
        return `${this.options.batch_endpoint}?world=${this._world_name}&renderer=${this._renderer}&zoom=0&x=${coords.x}&z=${coords.y}`;
    },

    createTile: function (coords, done) {
        const tile = document.createElement('img');
        tile.alt = '';
        tile.setAttribute('role', 'presentation');
        tile.dataset.x = coords.x;
        tile.dataset.z = coords.y;
        tile.dataset.zoom = coords.z;

        const key = `0/${coords.x}/${coords.y}`;
        this._queue_tile_request(key, coords, tile, done);

        return tile;
    },

    _queue_tile_request: function (key, coords, tile, done) {
        const target_map = this._is_sending ? this._queued_while_sending : this._pending_tiles;

        target_map.set(key, {
            tile: tile,
            done: done,
            coords: coords
        });

        if (this._batch_timer) {
            clearTimeout(this._batch_timer);
        }

        if (!this._is_sending && this._pending_tiles.size >= this.options.max_batch_size) {
            this._send_batch();
        } else if (!this._is_sending) {
            this._batch_timer = setTimeout(() => this._send_batch(), this.options.batch_delay);
        }
    },

    _send_batch: function () {
        if (this._pending_tiles.size === 0) return;

        this._is_sending = true;
        const all_tiles = new Map(this._pending_tiles);
        this._pending_tiles.clear();
        this._batch_timer = null;

        const CHUNK_SIZE = 20;
        const chunks = [];
        let current_chunk = new Map();

        for (const [key, value] of all_tiles) {
            current_chunk.set(key, value);
            if (current_chunk.size >= CHUNK_SIZE) {
                chunks.push(current_chunk);
                current_chunk = new Map();
            }
        }
        if (current_chunk.size > 0) {
            chunks.push(current_chunk);
        }

        const chunk_promises = chunks.map(chunk => this._send_chunk(chunk));

        Promise.all(chunk_promises).finally(() => {
            this._is_sending = false;
            if (this._queued_while_sending.size > 0) {
                for (const [key, value] of this._queued_while_sending) {
                    this._pending_tiles.set(key, value);
                }
                this._queued_while_sending.clear();
                this._batch_timer = setTimeout(() => this._send_batch(), this.options.batch_delay);
            }
        });
    },

    _send_chunk: async function (batch) {
        const tiles = [];
        for (const [key, _] of batch) {
            const [zoom, x, y] = key.split('/').map(Number);
            tiles.push({ zoom, x, z: y });
        }

        const request_body = {
            world: this._world_name,
            renderer: this._renderer,
            tiles: tiles
        };

        try {
            const response = await fetch(this.options.batch_endpoint, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(request_body)
            });

            if (!response.ok) throw new Error(`HTTP ${response.status}`);

            const data = await response.json();

            for (const [key, tile_data] of Object.entries(data.tiles)) {
                const request = batch.get(key);
                if (!request) continue;

                if (tile_data.empty) {
                    this._set_empty_tile(request.tile, request.done);
                } else if (tile_data.data) {
                    request.tile.src = 'data:image/png;base64,' + tile_data.data;
                    request.tile.onload = () => request.done(null, request.tile);
                    request.tile.onerror = () => request.done(new Error('Image load failed'), request.tile);
                } else if (tile_data.error) {
                    request.done(new Error(tile_data.error), request.tile);
                }
            }
        } catch (error) {
            console.error('Batch chunk failed:', error);
            for (const [_, request] of batch) {
                request.done(error, request.tile);
            }
        }
    },

    _set_empty_tile: function (tile, done) {
        if (!this._empty_tile_url) {
            this._empty_tile_url = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=';
        }
        tile.src = this._empty_tile_url;
        done(null, tile);
    }
});

L.tileLayer.batch = function (url_template, options) {
    return new L.TileLayer.Batch(url_template, options);
};

// -- Main Application --

class ButtercatMap {
    constructor() {
        this.map = null;
        this.tile_layer = null;
        this.websocket = null;
        this.reconnect_timer = null;

        this.current_world = null;
        this.worlds_info = {};
        this.players = new Map();
        this.player_list_collapsed = false;

        this.els = {
            coords: document.getElementById('coords-display'),
            status: document.getElementById('connection-status'),
            player_list: document.getElementById('player-list'),
            player_count: document.getElementById('player-count-display'),
            list_toggle: document.getElementById('player-list-toggle'),
            list_content: document.getElementById('player-list-content')
        };

        this.init();
    }

    async init() {
        this.init_map();
        this.init_ui();
        await this.load_worlds();
        this.tile_layer.addTo(this.map);
        this.connect_websocket();

        setInterval(() => this.load_worlds(), 30000);
    }

    init_map() {
        const world_bounds = L.latLngBounds(
            L.latLng(-100000, -100000),
            L.latLng(100000, 100000)
        );

        this.map = L.map('map', {
            crs: L.CRS.Simple,
            minZoom: -1,
            maxZoom: 4,
            zoomSnap: 0.5,
            zoomDelta: 0.5,
            maxBounds: world_bounds,
            maxBoundsViscosity: 1.0
        }).setView([0, 0], 0);

        this.tile_layer = L.tileLayer.batch(CONFIG.ENDPOINTS.TILES, {
            tileSize: CONFIG.TILE_SIZE,
            minNativeZoom: 0,
            maxNativeZoom: 0,
            minZoom: -1,
            maxZoom: 4,
            noWrap: true,
            bounds: [[-100000, -100000], [100000, 100000]],
            batch_delay: CONFIG.BATCH_DELAY,
            max_batch_size: CONFIG.MAX_BATCH_SIZE
        });

        const bc = window.BUTTERCAT || {};
        this.map.attributionControl.addAttribution(`\ud83d\udc31 <a href="https://github.com/harrisonpage/buttercat">buttercat ${bc.version || '?'}</a>`);

        this.map.on('mousemove', throttle((e) => {
            const x = Math.round(e.latlng.lng / SCALE);
            const z = Math.round(-e.latlng.lat / SCALE);
            if (this.els.coords) this.els.coords.textContent = `X: ${x}, Z: ${z}`;
        }, 50));
    }

    init_ui() {
        if (this.els.list_toggle && this.els.list_content) {
            this.els.list_toggle.addEventListener('click', () => {
                this.player_list_collapsed = !this.player_list_collapsed;
                this.els.list_content.classList.toggle('collapsed', this.player_list_collapsed);
                this.els.list_toggle.textContent = this.player_list_collapsed ? '+' : '-';
            });
        }

        window.focus_player = (uuid) => {
            const p = this.players.get(uuid);
            if (p) {
                const pos = this.world_to_latlng(p.data.x, p.data.z);
                this.map.setView(pos, 0);
            }
        };
    }

    world_to_latlng(x, z) {
        return L.latLng(-z * SCALE, x * SCALE);
    }

    center_on_spawn() {
        const info = this.worlds_info[this.current_world];
        if (info && info.spawn) {
            this.map.setView(this.world_to_latlng(info.spawn.x, info.spawn.z), 0);
        } else {
            this.map.setView([0, 0], 0);
        }
    }

    async load_worlds() {
        try {
            const response = await fetch(CONFIG.ENDPOINTS.WORLDS);
            if (!response.ok) throw new Error('Failed to load worlds');

            const data = await response.json();
            const worlds = data.worlds || [];

            worlds.forEach(w => {
                this.worlds_info[w.name] = {
                    spawn: { x: w.spawn_x || 0, z: w.spawn_z || 0 }
                };
            });

            if (worlds.length > 0 && !this.current_world) {
                this.current_world = worlds[0].name;
                this.tile_layer.updateWorld(this.current_world);
                this.center_on_spawn();
            }
        } catch (e) {
            console.error(e);
        }
    }

    // -- WebSocket --

    connect_websocket() {
        if (this.els.status) {
            this.els.status.textContent = 'Connecting...';
            this.els.status.className = 'connecting';
        }

        this.websocket = new WebSocket(CONFIG.ENDPOINTS.WEBSOCKET);

        this.websocket.onopen = () => {
            if (this.els.status) {
                this.els.status.textContent = 'Connected';
                this.els.status.className = 'connected';
            }
            if (this.reconnect_timer) {
                clearTimeout(this.reconnect_timer);
                this.reconnect_timer = null;
            }
        };

        this.websocket.onmessage = (e) => this.handle_message(e);

        this.websocket.onclose = () => {
            if (this.els.status) {
                this.els.status.textContent = 'Disconnected';
                this.els.status.className = 'disconnected';
            }
            if (!this.reconnect_timer) {
                this.reconnect_timer = setTimeout(() => this.connect_websocket(), 3000);
            }
        };
    }

    handle_message(e) {
        try {
            const data = JSON.parse(e.data);
            if (data.type === 'player_positions') {
                this.handle_player_positions(data.data);
            } else if (data.type === 'tile_update') {
                this.handle_tile_update(data);
            }
        } catch (err) {
            console.error("WS Parsing Error:", err);
        }
    }

    handle_tile_update(data) {
        if (data.world !== this.current_world) return;

        const selector = `img[data-x="${data.x}"][data-z="${data.z}"][data-zoom="${data.zoom}"]`;
        const tile = document.querySelector(selector);
        if (tile) {
            const ts = Date.now();
            tile.src = `${CONFIG.ENDPOINTS.TILES}?world=${data.world}&renderer=FLAT&zoom=${data.zoom}&x=${data.x}&z=${data.z}&t=${ts}`;
        }
    }

    // -- Players --

    handle_player_positions(all_worlds_data) {
        let current_world_players = [];
        const world_data = all_worlds_data.find(d => d.world === this.current_world);
        if (world_data) {
            current_world_players = world_data.players;
        }

        const seen_uuids = new Set();

        current_world_players.forEach(p => {
            seen_uuids.add(p.uuid);
            this.update_player_marker(p);
        });

        for (const [uuid, p] of this.players) {
            if (p.marker && !seen_uuids.has(uuid)) {
                this.map.removeLayer(p.marker);
                p.marker = null;
            }
        }

        const all_players = [];
        all_worlds_data.forEach(wd => {
            wd.players.forEach(p => {
                p.world = wd.world;
                all_players.push(p);
            });
        });

        this.update_players_store(all_players);
        this.update_player_list_ui();

        if (this.els.player_count) {
            this.els.player_count.textContent = `Players: ${current_world_players.length}`;
        }
    }

    update_players_store(new_data) {
        const new_uuids = new Set(new_data.map(p => p.uuid));

        for (const [uuid, p] of this.players) {
            if (!new_uuids.has(uuid)) {
                if (p.marker) this.map.removeLayer(p.marker);
                this.players.delete(uuid);
            }
        }

        new_data.forEach(p => {
            let existing = this.players.get(p.uuid);
            if (!existing) {
                existing = { data: p, marker: null };
                this.players.set(p.uuid, existing);
            } else {
                existing.data = p;
            }
        });
    }

    update_player_marker(p) {
        const entry = this.players.get(p.uuid);
        if (!entry) return;

        const pos = this.world_to_latlng(p.x, p.z);
        const yaw_deg = rad_to_deg(p.yaw || 0);

        if (entry.marker) {
            entry.marker.setLatLng(pos);
            this.rotate_marker(entry.marker, yaw_deg);
        } else {
            const rotation = yaw_deg + 180;
            const icon = L.divIcon({
                className: 'player-marker',
                html: `<div class="player-arrow" style="transform: rotate(${rotation}deg);"></div>`,
                iconSize: [20, 20],
                iconAnchor: [10, 10]
            });
            const marker = L.marker(pos, { icon: icon });
            marker.bindTooltip(p.name, {
                permanent: false,
                direction: 'top',
                offset: [0, -12],
                className: 'player-tooltip'
            });
            marker.addTo(this.map);
            entry.marker = marker;
        }
    }

    rotate_marker(marker, yaw_deg) {
        const el = marker.getElement();
        if (el) {
            const arrow = el.querySelector('.player-arrow');
            if (arrow) {
                arrow.style.transform = `rotate(${yaw_deg + 180}deg)`;
            }
        }
    }

    clear_players() {
        for (const p of this.players.values()) {
            if (p.marker) {
                this.map.removeLayer(p.marker);
                p.marker = null;
            }
        }
    }

    update_player_list_ui() {
        if (!this.els.player_list) return;

        const visible_players = Array.from(this.players.values())
            .map(e => e.data)
            .filter(p => p.world === this.current_world)
            .sort((a, b) => a.name.localeCompare(b.name));

        if (visible_players.length === 0) {
            this.els.player_list.innerHTML = '<li class="player-list-empty">No players online</li>';
            return;
        }

        const html = visible_players.map(p => `
            <li data-uuid="${p.uuid}" onclick="window.focus_player('${p.uuid}')">
                <span class="player-icon"></span>
                <span class="player-name">${escape_html(p.name)}</span>
                <span class="player-coords">${Math.round(p.x)}, ${Math.round(p.z)}</span>
            </li>
        `).join('');

        if (this.els.player_list.innerHTML !== html) {
            this.els.player_list.innerHTML = html;
        }
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.buttercat_map = new ButtercatMap();
});
