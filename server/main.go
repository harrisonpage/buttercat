package main

import (
	"flag"
	"log"
	"net/http"
)

func main() {
	exportDir := flag.String("export", "/thin/hytale/export", "path to tile export directory")
	httpAddr := flag.String("http", ":9200", "HTTP listen address")
	eventAddr := flag.String("event", ":9100", "TCP event listener address")
	flag.Parse()

	state := NewState()
	hub := NewHub()

	// Start TCP event receiver
	receiver := NewEventReceiver(*eventAddr, state, hub)
	go func() {
		if err := receiver.Listen(); err != nil {
			log.Fatalf("Event receiver: %v", err)
		}
	}()

	// Start HTTP server
	srv := NewServer(*exportDir, state, hub, receiver)
	log.Printf("Listening on %s (HTTP)", *httpAddr)
	log.Printf("Export directory: %s", *exportDir)
	if err := http.ListenAndServe(*httpAddr, srv.Handler()); err != nil {
		log.Fatalf("HTTP server: %v", err)
	}
}
