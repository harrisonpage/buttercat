package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"sync"
)

// EventReceiver listens for NDJSON events from the Hytale plugin over TCP.
// Also sends render commands back to the plugin on the same connection.
type EventReceiver struct {
	addr  string
	state *State
	hub   *Hub

	mu   sync.Mutex
	conn net.Conn
}

func NewEventReceiver(addr string, state *State, hub *Hub) *EventReceiver {
	return &EventReceiver{addr: addr, state: state, hub: hub}
}

// Listen starts the TCP listener. Blocks forever.
func (e *EventReceiver) Listen() error {
	ln, err := net.Listen("tcp", e.addr)
	if err != nil {
		return fmt.Errorf("event listen: %w", err)
	}
	log.Printf("Listening on %s (events)", e.addr)

	for {
		conn, err := ln.Accept()
		if err != nil {
			log.Printf("Event accept error: %v", err)
			continue
		}
		go e.handleConn(conn)
	}
}

// IsPluginConnected returns true if a plugin is currently connected.
func (e *EventReceiver) IsPluginConnected() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.conn != nil
}

// SendRenderRequest sends a render command to the plugin.
// Returns false if no plugin is connected.
func (e *EventReceiver) SendRenderRequest(world string, x, z int) bool {
	e.mu.Lock()
	conn := e.conn
	e.mu.Unlock()

	if conn == nil {
		return false
	}

	msg, _ := json.Marshal(map[string]any{
		"type":  "render",
		"world": world,
		"x":     x,
		"z":     z,
	})

	e.mu.Lock()
	defer e.mu.Unlock()
	if e.conn == nil {
		return false
	}

	_, err := e.conn.Write(append(msg, '\n'))
	if err != nil {
		log.Printf("Failed to send render request: %v", err)
		return false
	}
	return true
}

func (e *EventReceiver) handleConn(conn net.Conn) {
	e.mu.Lock()
	e.conn = conn
	e.mu.Unlock()

	defer func() {
		e.mu.Lock()
		if e.conn == conn {
			e.conn = nil
		}
		e.mu.Unlock()
		conn.Close()
	}()

	remote := conn.RemoteAddr().String()
	log.Printf("Plugin connected: %s", remote)

	scanner := bufio.NewScanner(conn)
	scanner.Buffer(make([]byte, 1024*1024), 1024*1024)

	for scanner.Scan() {
		line := scanner.Bytes()
		if len(line) == 0 {
			continue
		}

		var msg struct {
			Type       string          `json:"type"`
			Worlds     json.RawMessage `json:"worlds"`
			World      string          `json:"world"`
			X          int             `json:"x"`
			Z          int             `json:"z"`
			Zoom       int             `json:"zoom"`
			ServerName string          `json:"server_name"`
			MaxPlayers int             `json:"max_players"`
		}

		if err := json.Unmarshal(line, &msg); err != nil {
			log.Printf("Event parse error: %v", err)
			continue
		}

		switch msg.Type {
		case "hello":
			log.Printf("Plugin hello: %s", string(line))
			if msg.ServerName != "" {
				e.state.UpdateServerInfo(msg.ServerName, msg.MaxPlayers)
			}

		case "heartbeat":
			// silent

		case "worlds":
			e.state.UpdateWorlds(msg.Worlds)

		case "players":
			e.state.UpdatePlayers(msg.Worlds)
			if wsMsg := e.state.GetPlayersWSMessage(); wsMsg != nil {
				e.hub.Broadcast(wsMsg)
			}

		case "tile":
			tileMsg, _ := json.Marshal(map[string]any{
				"type":  "tile_update",
				"world": msg.World,
				"x":     msg.X,
				"z":     msg.Z,
				"zoom":  msg.Zoom,
			})
			e.hub.Broadcast(tileMsg)
		}
	}

	if err := scanner.Err(); err != nil {
		log.Printf("Plugin disconnected (%s): %v", remote, err)
	} else {
		log.Printf("Plugin disconnected: %s", remote)
	}
}
