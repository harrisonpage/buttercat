package buttercat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ButtercatPlugin extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String HYTALE_VERSION = "2026.03.26";
    private static final Path EXPORT_DIR = Path.of("/thin/hytale/export");
    private static final int VIEW_RADIUS = 5;
    private static final int PREGEN_RADIUS = 10;

    private final EventPusher pusher = new EventPusher();
    private TileExporter tiles;
    private ScheduledExecutorService scheduler;

    public ButtercatPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    public void setup() {
        LOGGER.atInfo().log("[buttercat] Initializing...");
    }

    @Override
    public void start() {
        LOGGER.atInfo().log("[buttercat] Starting...");
        pusher.setOnConnect(() -> {
            pushWorlds();
            pushPlayers();
        });

        // Build hello JSON with server info
        String serverName = HytaleServer.get().getServerName();
        int maxPlayers = HytaleServer.get().getConfig().getMaxPlayers();
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("version", 1);
        hello.addProperty("plugin", "buttercat-export");
        hello.addProperty("hytale", HYTALE_VERSION);
        hello.addProperty("server_name", serverName);
        hello.addProperty("max_players", maxPlayers);
        pusher.start(hello.toString());
        tiles = new TileExporter(EXPORT_DIR, pusher);
        pusher.setOnRender((world, x, z) -> tiles.renderTile(world, x, z));

        scheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "buttercat-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Log world map settings for diagnostics
        scheduler.schedule(() -> {
            for (World world : Universe.get().getWorlds().values()) {
                var settings = world.getWorldMapManager().getWorldMapSettings();
                LOGGER.atInfo().log("[buttercat] WorldMap settings for " + world.getName()
                        + ": imageScale=" + settings.getImageScale());
            }
        }, 3, TimeUnit.SECONDS);

        // Push world metadata every 30 seconds (first after 2s)
        scheduler.scheduleAtFixedRate(this::pushWorlds, 2, 30, TimeUnit.SECONDS);

        // Push player positions every 5 seconds
        scheduler.scheduleAtFixedRate(this::pushPlayers, 5, 5, TimeUnit.SECONDS);

        // Render tiles around players every 30 seconds
        scheduler.scheduleAtFixedRate(this::renderPlayerTiles, 10, 30, TimeUnit.SECONDS);

        // Pre-generate tiles around spawn on first startup
        scheduler.schedule(this::pregenSpawn, 5, TimeUnit.SECONDS);
    }

    @Override
    public void shutdown() {
        LOGGER.atInfo().log("[buttercat] Shutting down...");
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (tiles != null) {
            tiles.shutdown();
        }
        pusher.shutdown();
    }

    private void renderPlayerTiles() {
        for (World world : Universe.get().getWorlds().values()) {
            for (PlayerRef player : world.getPlayerRefs()) {
                Vector3d pos = player.getTransform().getPosition();
                int chunkX = ChunkUtil.chunkCoordinate((int) pos.x);
                int chunkZ = ChunkUtil.chunkCoordinate((int) pos.z);
                tiles.renderAround(world.getName(), chunkX, chunkZ, VIEW_RADIUS);
            }
        }
    }

    private void pregenSpawn() {
        Path lockFile = this.getDataDirectory().resolve("pregen_done");
        if (Files.exists(lockFile)) return;

        LOGGER.atInfo().log("[buttercat] First start: pre-generating tiles around spawn...");

        for (World world : Universe.get().getWorlds().values()) {
            Vector3d spawn = getSpawn(world);
            int chunkX = ChunkUtil.chunkCoordinate((int) spawn.x);
            int chunkZ = ChunkUtil.chunkCoordinate((int) spawn.z);

            LOGGER.atInfo().log("[buttercat] Pre-generating " + world.getName()
                    + " around (" + chunkX + ", " + chunkZ + ") radius " + PREGEN_RADIUS);
            tiles.renderAround(world.getName(), chunkX, chunkZ, PREGEN_RADIUS);
        }

        try {
            Files.createDirectories(lockFile.getParent());
            Files.createFile(lockFile);
        } catch (Exception e) {
            LOGGER.atWarning().log("[buttercat] Failed to create pregen lock: " + e.getMessage());
        }
    }

    private void pushWorlds() {
        if (!pusher.isConnected()) return;

        JsonArray worlds = new JsonArray();
        for (World world : Universe.get().getWorlds().values()) {
            JsonObject w = new JsonObject();
            w.addProperty("name", world.getName());
            w.addProperty("player_count", world.getPlayerCount());

            Vector3d spawn = getSpawn(world);
            w.addProperty("spawn_x", spawn.x);
            w.addProperty("spawn_z", spawn.z);

            worlds.add(w);
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "worlds");
        msg.addProperty("ts", System.currentTimeMillis());
        msg.add("worlds", worlds);

        pusher.send(msg.toString());
    }

    private void pushPlayers() {
        if (!pusher.isConnected()) return;

        JsonArray worlds = new JsonArray();
        for (World world : Universe.get().getWorlds().values()) {
            JsonArray players = new JsonArray();
            for (PlayerRef player : world.getPlayerRefs()) {
                JsonObject p = new JsonObject();
                p.addProperty("name", player.getUsername());
                p.addProperty("uuid", player.getUuid().toString());

                Transform transform = player.getTransform();
                Vector3d pos = transform.getPosition();
                Vector3f rot = transform.getRotation();

                p.addProperty("x", pos.x);
                p.addProperty("y", pos.y);
                p.addProperty("z", pos.z);
                p.addProperty("yaw", rot.y);

                players.add(p);
            }

            JsonObject w = new JsonObject();
            w.addProperty("name", world.getName());
            w.add("players", players);
            worlds.add(w);
        }

        JsonObject msg = new JsonObject();
        msg.addProperty("type", "players");
        msg.addProperty("ts", System.currentTimeMillis());
        msg.add("worlds", worlds);

        pusher.send(msg.toString());
    }

    private static Vector3d getSpawn(World world) {
        ISpawnProvider provider = world.getWorldConfig().getSpawnProvider();
        if (provider != null) {
            Transform t = provider.getSpawnPoint(world, world.getWorldConfig().getUuid());
            if (t != null) return t.getPosition();
        }
        return new Vector3d(0, 0, 0);
    }
}
