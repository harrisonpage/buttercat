package buttercat;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * TCP client that connects to the map server and pushes NDJSON events.
 * Also reads render commands from the server (bidirectional).
 * Reconnects automatically every 5 seconds if disconnected.
 */
public class EventPusher {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String HOST = "localhost";
    private static final int PORT = 9100;
    private static final long RECONNECT_DELAY_MS = 5000;

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile boolean running;
    private volatile boolean loggedWaiting;
    private Thread connectThread;
    private Runnable onConnect;
    private RenderCallback onRender;

    @FunctionalInterface
    public interface RenderCallback {
        void render(String world, int x, int z);
    }

    public void setOnConnect(Runnable callback) {
        this.onConnect = callback;
    }

    public void setOnRender(RenderCallback callback) {
        this.onRender = callback;
    }

    private String helloJson;

    public void start(String helloJson) {
        this.helloJson = helloJson;
        running = true;
        connectThread = new Thread(this::connectLoop, "buttercat-pusher");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    public void shutdown() {
        running = false;
        if (connectThread != null) {
            connectThread.interrupt();
        }
        disconnect();
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    /**
     * Send a single NDJSON line. Thread-safe via synchronization on the output stream.
     * Silently drops the message if not connected.
     */
    public synchronized void send(String json) {
        if (out == null) return;
        try {
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            LOGGER.atWarning().log("[buttercat] Send failed, will reconnect: " + e.getMessage());
            disconnect();
        }
    }

    private void connectLoop() {
        while (running) {
            try {
                LOGGER.atInfo().log("[buttercat] Connecting to " + HOST + ":" + PORT + "...");
                socket = new Socket(HOST, PORT);
                out = socket.getOutputStream();
                LOGGER.atInfo().log("[buttercat] Connected to event receiver");

                // Send hello
                send(helloJson);

                // Notify listener so it can push current state immediately
                if (onConnect != null) {
                    try {
                        onConnect.run();
                    } catch (Exception e) {
                        LOGGER.atWarning().log("[buttercat] onConnect callback error: " + e.getMessage());
                    }
                }

                // Read commands from server in this thread
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

                // Spawn heartbeat thread
                Thread heartbeat = new Thread(() -> {
                    try {
                        while (running && isConnected()) {
                            Thread.sleep(30000);
                            send("{\"type\":\"heartbeat\",\"ts\":" + System.currentTimeMillis() + "}");
                        }
                    } catch (InterruptedException ignored) {}
                }, "buttercat-heartbeat");
                heartbeat.setDaemon(true);
                heartbeat.start();

                // Read loop — blocks until disconnect
                String line;
                while (running && (line = reader.readLine()) != null) {
                    handleCommand(line);
                }

                heartbeat.interrupt();
                loggedWaiting = false;
            } catch (IOException e) {
                if (running && !loggedWaiting) {
                    LOGGER.atInfo().log("[buttercat] Waiting for event receiver on " + HOST + ":" + PORT);
                    loggedWaiting = true;
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("[buttercat] Connection error: " + e.getMessage());
            }

            disconnect();

            if (running) {
                try {
                    Thread.sleep(RECONNECT_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handleCommand(String line) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            String type = obj.get("type").getAsString();

            if ("render".equals(type) && onRender != null) {
                String world = obj.get("world").getAsString();
                int x = obj.get("x").getAsInt();
                int z = obj.get("z").getAsInt();
                onRender.render(world, x, z);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("[buttercat] Bad command from server: " + e.getMessage());
        }
    }

    private void disconnect() {
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        out = null;
        socket = null;
    }
}
