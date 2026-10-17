package threed_map.client.map.render;

import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import threed_map.ThreedMapMod;
import threed_map.client.map.data.MapDimension;
import threed_map.client.map.data.MapRegion;
import threed_map.client.map.data.MapShading;

import java.util.Iterator;

/**
 * GPU textures for map regions: full detail (512²) and zoomed-out previews (128²).
 * Updates are limited per frame; textures not drawn for a while are freed. Render thread only.
 */
public final class MapTextures {
    private static final long IDLE_MS = 15_000;
    private static final int FULL_UPDATES_PER_FRAME = 3;
    private static final int LOD_UPDATES_PER_FRAME = 8;
    private static final int LOD_BUILDS_PER_FRAME = 1;
    private static final long LOD_REBUILD_MS = 2_000;

    private final Long2ObjectMap<Tile> full = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectMap<Tile> lod = new Long2ObjectOpenHashMap<>();
    private MapDimension dimension;
    private int fullBudget;
    private int lodBudget;
    private int lodBuildBudget;
    private int nextId;

    private final class Tile {
        final DynamicTexture texture;
        final ResourceLocation id;
        /** Identity of the data last drawn into the texture. */
        Object source;
        int sourceVersion = -1;
        long lastUsedMs;
        long builtMs;

        Tile(int size) {
            texture = new DynamicTexture(size, size, true);
            id = ThreedMapMod.id("map_tile/" + nextId++);
            Minecraft.getInstance().getTextureManager().register(id, texture);
        }

        void close() {
            Minecraft.getInstance().getTextureManager().release(id);
        }
    }

    /** Call once per frame before drawing. Switching dimension drops all textures. */
    public void beginFrame(MapDimension dim) {
        if (dim != dimension) {
            clear();
            dimension = dim;
        }
        fullBudget = FULL_UPDATES_PER_FRAME;
        lodBudget = LOD_UPDATES_PER_FRAME;
        lodBuildBudget = LOD_BUILDS_PER_FRAME;
    }

    /** Full-detail texture for a loaded region, or null if it has not been drawn yet. */
    public ResourceLocation full(MapRegion region) {
        Tile tile = full.get(region.key());
        boolean fresh = tile == null || tile.source != region;
        if (fresh) {
            if (fullBudget <= 0) {
                return null;
            }
            if (tile == null) {
                tile = new Tile(MapRegion.SIZE);
                full.put(region.key(), tile);
            }
            tile.source = region;
            region.markTextureDirty(0, 0, MapRegion.MASK, MapRegion.MASK);
        }
        tile.lastUsedMs = System.currentTimeMillis();
        if (region.hasDirtyTexture() && fullBudget > 0) {
            fullBudget--;
            redraw(tile, region, region.takeDirtyRect());
        }
        return tile.sourceVersion < 0 ? null : tile.id;
    }

    private void redraw(Tile tile, MapRegion region, int[] rect) {
        NativeImage image = tile.texture.getPixels();
        for (int z = rect[1]; z <= rect[3]; z++) {
            for (int x = rect[0]; x <= rect[2]; x++) {
                image.setPixelRGBA(x, z, toAbgr(MapShading.shaded(dimension, region, x, z)));
            }
        }
        tile.texture.bind();
        int w = rect[2] - rect[0] + 1;
        int h = rect[3] - rect[1] + 1;
        image.upload(0, rect[0], rect[1], rect[0], rect[1], w, h, false, false);
        tile.sourceVersion = region.version();
    }

    /**
     * Zoomed-out texture: built from the region if it is in memory, otherwise from the saved preview.
     * Null while nothing is available yet.
     */
    public ResourceLocation lod(int rx, int rz) {
        long key = MapRegion.key(rx, rz);
        Tile tile = lod.get(key);
        MapRegion region = dimension.loaded(rx, rz);
        Object source;
        int version;
        int[] pixels = null;
        if (region != null) {
            source = region;
            version = region.version();
        } else {
            pixels = dimension.lodFromDisk(rx, rz);
            source = pixels;
            version = 0;
        }
        if (source == null) {
            return tile != null ? touch(tile) : null;
        }
        boolean missing = tile == null || tile.source != source;
        boolean stale = missing || tile.sourceVersion != version;
        // Building from a live region costs a few ms: one per frame, and at most every 2 s per tile.
        boolean canBuild = pixels != null
                ? lodBudget > 0
                : lodBuildBudget > 0 && (missing || System.currentTimeMillis() - tile.builtMs > LOD_REBUILD_MS);
        if (stale && canBuild) {
            if (pixels == null) {
                lodBuildBudget--;
            } else {
                lodBudget--;
            }
            if (tile == null) {
                tile = new Tile(MapRegion.LOD_SIZE);
                lod.put(key, tile);
            }
            if (pixels == null) {
                pixels = MapShading.buildLod(dimension, region);
            }
            NativeImage image = tile.texture.getPixels();
            for (int z = 0; z < MapRegion.LOD_SIZE; z++) {
                for (int x = 0; x < MapRegion.LOD_SIZE; x++) {
                    image.setPixelRGBA(x, z, toAbgr(pixels[z * MapRegion.LOD_SIZE + x]));
                }
            }
            tile.texture.upload();
            tile.builtMs = System.currentTimeMillis();
            tile.source = source;
            tile.sourceVersion = version;
        }
        return tile == null || tile.sourceVersion < 0 ? null : touch(tile);
    }

    private static ResourceLocation touch(Tile tile) {
        tile.lastUsedMs = System.currentTimeMillis();
        return tile.id;
    }

    /** Frees textures that were not drawn recently. */
    public void evictIdle() {
        long now = System.currentTimeMillis();
        evict(full, now);
        evict(lod, now);
    }

    private static void evict(Long2ObjectMap<Tile> tiles, long now) {
        Iterator<Tile> it = tiles.values().iterator();
        while (it.hasNext()) {
            Tile tile = it.next();
            if (now - tile.lastUsedMs > IDLE_MS) {
                tile.close();
                it.remove();
            }
        }
    }

    public void clear() {
        full.values().forEach(Tile::close);
        lod.values().forEach(Tile::close);
        full.clear();
        lod.clear();
    }

    /** NativeImage stores pixels as ABGR. */
    private static int toAbgr(int argb) {
        return (argb & 0xFF00FF00) | (argb & 0xFF) << 16 | (argb >> 16) & 0xFF;
    }
}
