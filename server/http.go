package main

import (
	"embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"html/template"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

//go:embed public
var publicFS embed.FS

const (
	Version            = "0.1.0"
	renderPollInterval = 100 * time.Millisecond
	renderTimeout      = 5 * time.Second
)

var indexTmpl = template.Must(template.New("index.html").Parse(indexHTML()))

func indexHTML() string {
	data, _ := publicFS.ReadFile("public/index.html")
	return string(data)
}

type pageData struct {
	ServerName string
	Version    string
	MaxPlayers int
}

// Server is the HTTP server that serves tiles, worlds, and the frontend.
type Server struct {
	exportDir string
	state     *State
	hub       *Hub
	events    *EventReceiver
}

func NewServer(exportDir string, state *State, hub *Hub, events *EventReceiver) *Server {
	return &Server{exportDir: exportDir, state: state, hub: hub, events: events}
}

// Handler returns the http.Handler for the server.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("/tiles", s.handleTiles)
	mux.HandleFunc("/worlds", s.handleWorlds)
	mux.HandleFunc("/ws/players", s.hub.HandleWS)
	mux.HandleFunc("/healthcheck", s.handleHealthcheck)
	mux.HandleFunc("/robots.txt", handleRobots)
	mux.HandleFunc("/", s.handleIndex)

	// Static assets (css, js, favicon)
	sub, _ := fs.Sub(publicFS, "public")
	mux.Handle("/css/", http.FileServer(http.FS(sub)))
	mux.Handle("/js/", http.FileServer(http.FS(sub)))
	mux.Handle("/favicon.ico", http.FileServer(http.FS(sub)))

	return securityHeaders(mux)
}

func (s *Server) handleIndex(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	indexTmpl.Execute(w, pageData{
		ServerName: s.state.GetServerName(),
		Version:    Version,
		MaxPlayers: s.state.GetMaxPlayers(),
	})
}

func (s *Server) handleWorlds(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Write(s.state.GetWorldsJSON())
}

func (s *Server) handleTiles(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		s.handleTilesBatch(w, r)
		return
	}
	s.handleTileSingle(w, r)
}

// handleTileSingle serves a single tile PNG.
func (s *Server) handleTileSingle(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	world := q.Get("world")
	renderer := strings.ToLower(q.Get("renderer"))
	zoom := q.Get("zoom")
	x := q.Get("x")
	z := q.Get("z")

	if world == "" || renderer == "" || zoom == "" {
		http.Error(w, "missing parameters", http.StatusBadRequest)
		return
	}

	path := s.tilePath(renderer, world, zoom, x, z)
	data, err := os.ReadFile(path)
	if err != nil {
		// Try on-demand render
		xi, _ := strconv.Atoi(x)
		zi, _ := strconv.Atoi(z)
		data = s.requestAndWaitForTile(world, xi, zi, path)
	}

	if data == nil {
		w.Header().Set("Content-Type", "image/png")
		w.WriteHeader(http.StatusOK)
		return
	}

	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "public, max-age=30")
	w.Write(data)
}

// handleTilesBatch handles batch tile requests.
func (s *Server) handleTilesBatch(w http.ResponseWriter, r *http.Request) {
	var req struct {
		World    string `json:"world"`
		Renderer string `json:"renderer"`
		Tiles    []struct {
			Zoom int `json:"zoom"`
			X    int `json:"x"`
			Z    int `json:"z"`
		} `json:"tiles"`
	}

	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid JSON", http.StatusBadRequest)
		return
	}

	renderer := strings.ToLower(req.Renderer)
	tiles := make(map[string]any)

	// First pass: read existing tiles, collect missing ones
	type missingTile struct {
		key  string
		path string
		x, z int
	}
	var missing []missingTile

	for _, t := range req.Tiles {
		key := fmt.Sprintf("%d/%d/%d", t.Zoom, t.X, t.Z)
		path := s.tilePath(renderer, req.World, fmt.Sprintf("%d", t.Zoom), fmt.Sprintf("%d", t.X), fmt.Sprintf("%d", t.Z))

		data, err := os.ReadFile(path)
		if err != nil {
			missing = append(missing, missingTile{key: key, path: path, x: t.X, z: t.Z})
			continue
		}
		tiles[key] = map[string]string{"data": base64.StdEncoding.EncodeToString(data)}
	}

	// Request missing tiles from plugin
	if len(missing) > 0 {
		for _, m := range missing {
			s.events.SendRenderRequest(req.World, m.x, m.z)
		}

		// Poll for missing tiles to appear
		deadline := time.Now().Add(renderTimeout)
		for time.Now().Before(deadline) {
			time.Sleep(renderPollInterval)

			remaining := missing[:0]
			for _, m := range missing {
				data, err := os.ReadFile(m.path)
				if err == nil {
					tiles[m.key] = map[string]string{"data": base64.StdEncoding.EncodeToString(data)}
				} else {
					remaining = append(remaining, m)
				}
			}
			missing = remaining

			if len(missing) == 0 {
				break
			}
		}

		// Any still missing after timeout
		for _, m := range missing {
			tiles[m.key] = map[string]bool{"empty": true}
		}
	}

	resp := map[string]any{"tiles": tiles}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(resp); err != nil {
		log.Printf("Batch response encode error: %v", err)
	}
}

// requestAndWaitForTile sends a render request and polls for the file to appear.
func (s *Server) requestAndWaitForTile(world string, x, z int, path string) []byte {
	if !s.events.SendRenderRequest(world, x, z) {
		return nil
	}

	deadline := time.Now().Add(renderTimeout)
	for time.Now().Before(deadline) {
		time.Sleep(renderPollInterval)
		data, err := os.ReadFile(path)
		if err == nil {
			return data
		}
	}
	return nil
}

func (s *Server) tilePath(renderer, world, zoom, x, z string) string {
	return filepath.Join(s.exportDir, "tiles", renderer, world, zoom, x+"_"+z+".png")
}

func (s *Server) handleHealthcheck(w http.ResponseWriter, r *http.Request) {
	if !s.events.IsPluginConnected() {
		http.Error(w, "plugin disconnected", http.StatusServiceUnavailable)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func handleRobots(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain")
	fmt.Fprint(w, "User-Agent: *\nDisallow: /\n")
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("Strict-Transport-Security", "max-age=63072000; includeSubDomains")
		h.Set("Content-Security-Policy", strings.Join([]string{
			"default-src 'self'",
			"script-src 'self' 'unsafe-inline' https://unpkg.com",
			"style-src 'self' 'unsafe-inline' https://unpkg.com",
			"img-src 'self' data: https://unpkg.com",
			"connect-src 'self' wss: ws:",
			"font-src 'self'",
			"frame-ancestors 'none'",
		}, "; "))
		h.Set("X-Frame-Options", "DENY")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "strict-origin-when-cross-origin")
		h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
		next.ServeHTTP(w, r)
	})
}
