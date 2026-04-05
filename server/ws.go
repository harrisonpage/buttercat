package main

import (
	"log"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

// Hub manages WebSocket connections and broadcasts messages.
type Hub struct {
	mu      sync.RWMutex
	clients map[*websocket.Conn]struct{}
}

func NewHub() *Hub {
	return &Hub{
		clients: make(map[*websocket.Conn]struct{}),
	}
}

// HandleWS upgrades an HTTP connection to WebSocket and registers it.
func (h *Hub) HandleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("WebSocket upgrade error: %v", err)
		return
	}

	h.mu.Lock()
	h.clients[conn] = struct{}{}
	count := len(h.clients)
	h.mu.Unlock()

	log.Printf("WebSocket client connected (%d total)", count)

	// Read loop — just discard incoming messages, detect disconnect
	go func() {
		defer func() {
			h.mu.Lock()
			delete(h.clients, conn)
			count := len(h.clients)
			h.mu.Unlock()
			conn.Close()
			log.Printf("WebSocket client disconnected (%d total)", count)
		}()
		for {
			if _, _, err := conn.ReadMessage(); err != nil {
				break
			}
		}
	}()
}

// Broadcast sends a message to all connected WebSocket clients.
func (h *Hub) Broadcast(msg []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()

	for conn := range h.clients {
		if err := conn.WriteMessage(websocket.TextMessage, msg); err != nil {
			conn.Close()
		}
	}
}

// ClientCount returns the number of connected clients.
func (h *Hub) ClientCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}
