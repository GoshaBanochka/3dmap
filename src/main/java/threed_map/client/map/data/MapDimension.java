package threed_map.client.map.data;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import threed_map.ThreedMapMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Map data of one dimension on one server: regions in memory, which regions exist on disk,
 * and zoomed-out previews. Everything except file I/O runs on the client main thread.
 */
public final class MapDimension {
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.dat");
    private static final int SAVE_INTERVAL_TICKS = 30 * 20;
    private static final int EVICT_INTERVAL_TICKS = 5 * 20;
    private static final long REGION_IDLE_MS = 30_000;
    private static final long LOD_IDLE_MS = 60_000;

    public final ResourceKey<Level> dimension;
    private final Path dir;

    private final Long2ObjectMap<MapRegion> regions = new Long2ObjectOpenHashMap<>();
    private final LongSet onDisk = new LongOpenHashSet();
    private final LongSet loading = new LongOpenHashSet();
    private final Long2ObjectMap<Lod> lods = new Long2ObjectOpenHashMap<>();
    private final LongSet lodLoading = new LongOpenHashSet();
    /** Results of background loads, applied on the main thread. */
    private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();
    private int ticks;

    private static final class Lod {
        final int[] pixels;
        long lastUsedMs;

        Lod(int[] pixels) {
            this.pixels = pixels;
            this.lastUsedMs = System.currentTimeMillis();
        }
    }

    public MapDimension(ResourceKey<Level> dimension, Path dir) {
        this.dimension = dimension;
        this.dir = dir;
        indexDisk();
    }

    private void indexDisk() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(p -> {
                Matcher m = REGION_FILE.matcher(p.getFileName().toString());
                if (m.matches()) {
                    onDisk.add(MapRegion.key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
                }
            });
        } catch (IOException | NumberFormatException e) {
            ThreedMapMod.LOGGER.warn("Could not index map cache {}: {}", dir, e.toString());
        }
    }

    public boolean isExplored(int rx, int rz) {
        long key = MapRegion.key(rx, rz);
        return regions.containsKey(key) || onDisk.contains(key);
    }

    public MapRegion loaded(int rx, int rz) {
        return regions.get(MapRegion.key(rx, rz));
    }

    /** Region for the scanner, created if new. Null while it is still being read from disk. */
    public MapRegion regionForWrite(int rx, int rz) {
        long key = MapRegion.key(rx, rz);
        MapRegion region = regions.get(key);
        if (region == null) {
            if (onDisk.contains(key)) {
                requestLoad(rx, rz);
                return null;
            }
            region = new MapRegion(rx, rz);
            addLoaded(region);
        }
        region.lastUsedMs = System.currentTimeMillis();
        return region;
    }

    /** Region for full-detail rendering. Starts loading it from disk if needed; null until ready. */
    public MapRegion regionForRender(int rx, int rz) {
        long key = MapRegion.key(rx, rz);
        MapRegion region = regions.get(key);
        if (region != null) {
            region.lastUsedMs = System.currentTimeMillis();
        } else if (onDisk.contains(key)) {
            requestLoad(rx, rz);
        }
        return region;
    }

    /** Saved zoomed-out preview of a region (LOD_SIZE² ARGB), loading it if needed; null until ready. */
    public int[] lodFromDisk(int rx, int rz) {
        long key = MapRegion.key(rx, rz);
        Lod lod = lods.get(key);
        if (lod != null) {
            lod.lastUsedMs = System.currentTimeMillis();
            return lod.pixels;
        }
        if (onDisk.contains(key) && lodLoading.add(key)) {
            MapStorage.loadLod(MapStorage.lodFile(dir, rx, rz)).thenAccept(pixels -> completions.add(() -> {
                lodLoading.remove(key);
                if (pixels != null) {
                    lods.put(key, new Lod(pixels));
                }
            }));
        }
        return null;
    }

    public int heightAt(int wx, int wz) {
        MapRegion region = regions.get(MapRegion.key(wx >> MapRegion.SHIFT, wz >> MapRegion.SHIFT));
        return region == null ? MapRegion.UNKNOWN_HEIGHT : region.height(wx & MapRegion.MASK, wz & MapRegion.MASK);
    }

    void setColumn(MapRegion region, int lx, int lz, int color, int height) {
        if (!region.set(lx, lz, color, height)) {
            return;
        }
        // Columns on the south/east edge shade the first row/column of the neighbor region.
        if (lx == MapRegion.MASK) {
            MapRegion east = loaded(region.rx + 1, region.rz);
            if (east != null) {
                east.markTextureDirty(0, lz, 0, lz);
            }
        }
        if (lz == MapRegion.MASK) {
            MapRegion south = loaded(region.rx, region.rz + 1);
            if (south != null) {
                south.markTextureDirty(lx, 0, lx, 0);
            }
        }
    }

    private void addLoaded(MapRegion region) {
        region.lastUsedMs = System.currentTimeMillis();
        regions.put(region.key(), region);
        MapRegion east = loaded(region.rx + 1, region.rz);
        if (east != null) {
            east.markTextureDirty(0, 0, 0, MapRegion.MASK);
        }
        MapRegion south = loaded(region.rx, region.rz + 1);
        if (south != null) {
            south.markTextureDirty(0, 0, MapRegion.MASK, 0);
        }
    }

    private void requestLoad(int rx, int rz) {
        long key = MapRegion.key(rx, rz);
        if (!loading.add(key)) {
            return;
        }
        MapStorage.loadRegion(MapStorage.regionFile(dir, rx, rz)).thenAccept(data -> completions.add(() -> {
            loading.remove(key);
            if (regions.containsKey(key)) {
                return;
            }
            if (data == null) {
                // Unreadable file: start this region from scratch.
                onDisk.remove(key);
                return;
            }
            addLoaded(new MapRegion(rx, rz, data.colors(), data.heights()));
        }));
    }

    /** Called every client tick with the player's block position. */
    public void tick(int playerX, int playerZ) {
        Runnable task;
        while ((task = completions.poll()) != null) {
            task.run();
        }
        ticks++;
        if (ticks % SAVE_INTERVAL_TICKS == 0) {
            saveDirty();
        }
        if (ticks % EVICT_INTERVAL_TICKS == 0) {
            evictIdle(playerX >> MapRegion.SHIFT, playerZ >> MapRegion.SHIFT);
        }
    }

    public void saveDirty() {
        for (MapRegion region : regions.values()) {
            if (region.dirtyForSave) {
                save(region);
            }
        }
    }

    private void save(MapRegion region) {
        int[] lod = MapShading.buildLod(this, region);
        MapStorage.save(dir, region.rx, region.rz, region.colors.clone(), region.heights.clone(), lod);
        region.dirtyForSave = false;
        onDisk.add(region.key());
        lods.put(region.key(), new Lod(lod));
    }

    private void evictIdle(int playerRx, int playerRz) {
        long now = System.currentTimeMillis();
        Iterator<MapRegion> it = regions.values().iterator();
        while (it.hasNext()) {
            MapRegion region = it.next();
            boolean nearPlayer = Math.abs(region.rx - playerRx) <= 1 && Math.abs(region.rz - playerRz) <= 1;
            if (!nearPlayer && now - region.lastUsedMs > REGION_IDLE_MS) {
                if (region.dirtyForSave) {
                    save(region);
                }
                it.remove();
            }
        }
        lods.values().removeIf(lod -> now - lod.lastUsedMs > LOD_IDLE_MS);
    }

    /** Saves everything and drops cached data. The instance must not be used afterwards. */
    public void close() {
        saveDirty();
        regions.clear();
        lods.clear();
        completions.clear();
    }

    public int loadedRegionCount() {
        return regions.size();
    }
}
