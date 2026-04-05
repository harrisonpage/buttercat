package buttercat;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.worldmap.MapImage;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.WorldMapManager;
import com.hypixel.hytale.math.util.ChunkUtil;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Renders map tiles via Hytale's WorldMapManager, writes PNGs to disk,
 * and pushes tile events over the NDJSON stream.
 */
public class TileExporter {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final int TILE_SIZE = 96;

    private volatile boolean loggedImageInfo = false;
    private final Path exportDir;
    private final EventPusher pusher;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    public TileExporter(Path exportDir, EventPusher pusher) {
        this.exportDir = exportDir;
        this.pusher = pusher;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "buttercat-tile");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Render a single tile and write it to disk. Deduplicates concurrent
     * requests for the same tile.
     */
    public CompletableFuture<Void> renderTile(String worldName, int x, int z) {
        String key = worldName + "/0/" + x + "_" + z;

        CompletableFuture<Void> existing = pending.get(key);
        if (existing != null) return existing;

        CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
            try {
                World world = Universe.get().getWorld(worldName);
                if (world == null) return null;

                // Skip unexplored chunks
                ChunkAccessor accessor = new ChunkAccessor(world);
                Boolean unexplored = accessor.isUnexplored(x, z).join();
                if (unexplored) return null;

                WorldMapManager mapManager = world.getWorldMapManager();

                // Clear cached map image so Hytale regenerates from current world state
                long chunkIndex = ChunkUtil.indexChunk(x, z);
                LongOpenHashSet chunks = new LongOpenHashSet();
                chunks.add(chunkIndex);
                mapManager.clearImagesInChunks(chunks);

                MapImage image = mapManager.getImageAsync(x, z).join();
                if (image == null) return null;

                if (!loggedImageInfo) {
                    loggedImageInfo = true;
                    LOGGER.atInfo().log("[buttercat] MapImage info: "
                            + image.width + "x" + image.height
                            + ", palette=" + (image.palette != null ? image.palette.length : 0) + " colors"
                            + ", bitsPerIndex=" + (image.bitsPerIndex & 0xFF)
                            + ", imageScale=" + mapManager.getWorldMapSettings().getImageScale());
                }

                byte[] png = PngEncoder.encode(image, TILE_SIZE);
                if (png == null || png.length == 0) return null;

                writeTile(worldName, x, z, png);

                // Push tile event
                pusher.send("{\"type\":\"tile\",\"ts\":" + System.currentTimeMillis()
                        + ",\"world\":\"" + worldName + "\",\"renderer\":\"flat\",\"zoom\":0"
                        + ",\"x\":" + x + ",\"z\":" + z + "}");

            } catch (Exception e) {
                LOGGER.atWarning().log("[buttercat] Tile render failed (" + x + "," + z + "): " + e.getMessage());
            }
            return null;
        }, executor);

        pending.put(key, future);
        future.whenComplete((v, ex) -> pending.remove(key));

        return future;
    }

    /**
     * Render all tiles in a square radius around (centerX, centerZ).
     */
    public void renderAround(String worldName, int centerX, int centerZ, int radius) {
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                renderTile(worldName, x, z);
            }
        }
    }

    /**
     * Write PNG bytes to disk with atomic rename.
     */
    private void writeTile(String worldName, int x, int z, byte[] png) throws IOException {
        Path dir = exportDir.resolve("tiles").resolve("flat").resolve(worldName).resolve("0");
        Files.createDirectories(dir);

        Path tmp = dir.resolve(x + "_" + z + ".png.tmp");
        Path target = dir.resolve(x + "_" + z + ".png");

        Files.write(tmp, png);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
