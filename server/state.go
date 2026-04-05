package main

import (
	"encoding/json"
	"sync"
)

// State holds the latest data received from the plugin.
type State struct {
	mu         sync.RWMutex
	worlds     json.RawMessage // raw JSON array of worlds
	players    json.RawMessage // raw JSON array of player worlds
	serverName string
	maxPlayers int
}

func NewState() *State {
	return &State{
		worlds:  json.RawMessage(`[]`),
		players: json.RawMessage(`[]`),
	}
}

func (s *State) UpdateWorlds(worlds json.RawMessage) {
	s.mu.Lock()
	s.worlds = worlds
	s.mu.Unlock()
}

func (s *State) UpdatePlayers(players json.RawMessage) {
	s.mu.Lock()
	s.players = players
	s.mu.Unlock()
}

func (s *State) UpdateServerInfo(name string, maxPlayers int) {
	s.mu.Lock()
	s.serverName = name
	s.maxPlayers = maxPlayers
	s.mu.Unlock()
}

func (s *State) GetServerName() string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.serverName == "" {
		return "Hytale Server"
	}
	return s.serverName
}

func (s *State) GetMaxPlayers() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.maxPlayers
}

// GetWorldsJSON returns the JSON response for GET /worlds.
func (s *State) GetWorldsJSON() []byte {
	s.mu.RLock()
	w := s.worlds
	s.mu.RUnlock()

	resp, _ := json.Marshal(map[string]json.RawMessage{"worlds": w})
	return resp
}

// GetPlayersWSMessage returns the WebSocket message for player_positions.
// Remaps from plugin format {"type":"players","worlds":[{"name":...}]}
// to frontend format {"type":"player_positions","data":[{"world":...}]}.
func (s *State) GetPlayersWSMessage() []byte {
	s.mu.RLock()
	raw := s.players
	s.mu.RUnlock()

	// Parse the worlds array and remap "name" -> "world"
	var worlds []json.RawMessage
	if err := json.Unmarshal(raw, &worlds); err != nil {
		return nil
	}

	var remapped []json.RawMessage
	for _, w := range worlds {
		var obj map[string]json.RawMessage
		if err := json.Unmarshal(w, &obj); err != nil {
			continue
		}
		// Rename "name" to "world"
		if name, ok := obj["name"]; ok {
			obj["world"] = name
			delete(obj, "name")
		}
		b, _ := json.Marshal(obj)
		remapped = append(remapped, b)
	}

	msg := map[string]interface{}{
		"type": "player_positions",
		"data": remapped,
	}
	b, _ := json.Marshal(msg)
	return b
}
