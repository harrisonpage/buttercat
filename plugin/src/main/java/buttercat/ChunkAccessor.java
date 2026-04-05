package buttercat;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.provider.IndexedStorageChunkStorageProvider.IndexedStorageChunkLoader;

import java.util.concurrent.CompletableFuture;

/**
 * Checks whether chunks exist (are generated) without triggering generation.
 * Used to skip tile rendering for unexplored areas.
 */
public record ChunkAccessor(World world) {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public CompletableFuture<Boolean> isUnexplored(int chunkX, int chunkZ) {
        if (world == null) return CompletableFuture.completedFuture(true);

        return CompletableFuture.supplyAsync(() -> {
            try {
                ChunkStore chunkStore = world.getChunkStore();
                if (chunkStore.getLoader() instanceof IndexedStorageChunkLoader loader) {
                    long index = ChunkUtil.indexChunk(chunkX, chunkZ);
                    if (loader.getIndexes().contains(index)) {
                        return false;
                    }
                }
            } catch (Exception e) {
                LOGGER.atWarning().log("[buttercat] Error checking chunk: " + e.getMessage());
            }
            return true;
        });
    }
}
