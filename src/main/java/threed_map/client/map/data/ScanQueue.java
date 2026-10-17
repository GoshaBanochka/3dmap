package threed_map.client.map.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Chunks waiting to be scanned. Work is spread over ticks with a fixed time budget, so neither
 * joining a world nor flying fast causes a frame spike.
 * <p>
 * Newly received chunks are scanned once. Block changes are picked up by slowly re-scanning loaded
 * chunks around the player (closest first), plus a faster refresh of the player's own neighborhood.
 */
public final class ScanQueue {
    private static final long BUDGET_NANOS = 2_000_000;
    private static final int RESCAN_PER_TICK = 2;
    private static final int NEARBY_INTERVAL_TICKS = 40;
    private static final int MAX_RADIUS = 32;
    /** Chunk offsets within MAX_RADIUS sorted by distance from the center. */
    private static final int[][] OFFSETS = buildOffsets();

    private final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
    private final ChunkScanner scanner = new ChunkScanner();
    private int rescanCursor;
    private int ticks;

    public void enqueue(int chunkX, int chunkZ) {
        queue.add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public void clear() {
        queue.clear();
        rescanCursor = 0;
    }

    public int size() {
        return queue.size();
    }

    public void tick(ClientLevel level, MapDimension dim, int playerChunkX, int playerChunkZ, int radius) {
        ticks++;
        scheduleRescans(playerChunkX, playerChunkZ, Math.min(radius, MAX_RADIUS));

        long deadline = System.nanoTime() + BUDGET_NANOS;
        LongArrayList retry = new LongArrayList();
        while (!queue.isEmpty() && System.nanoTime() < deadline) {
            long packed = queue.removeFirstLong();
            LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed),
                    ChunkStatus.FULL, false);
            if (chunk == null || chunk.isEmpty()) {
                continue; // unloaded meanwhile
            }
            if (!scanner.scan(level, chunk, dim)) {
                retry.add(packed);
            }
        }
        // Region still loading from disk: try again on a later tick.
        retry.forEach(queue::add);
    }

    private void scheduleRescans(int cx, int cz, int radius) {
        if (ticks % NEARBY_INTERVAL_TICKS == 0) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    enqueue(cx + dx, cz + dz);
                }
            }
        }
        for (int i = 0; i < RESCAN_PER_TICK; i++) {
            int[] off = OFFSETS[rescanCursor];
            rescanCursor++;
            if (rescanCursor >= OFFSETS.length || Math.max(Math.abs(off[0]), Math.abs(off[1])) > radius) {
                rescanCursor = 0;
            }
            enqueue(cx + off[0], cz + off[1]);
        }
    }

    private static int[][] buildOffsets() {
        List<int[]> list = new ArrayList<>();
        for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++) {
            for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) {
                list.add(new int[]{dx, dz});
            }
        }
        list.sort(Comparator.comparingInt(o -> Math.max(Math.abs(o[0]), Math.abs(o[1]))));
        return list.toArray(new int[0][]);
    }
}
