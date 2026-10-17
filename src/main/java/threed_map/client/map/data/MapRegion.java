package threed_map.client.map.data;

import java.util.Arrays;

/**
 * Explored map data for a 512×512 block area: one base color and one surface height per column.
 * A color with alpha 0 means the column has not been seen yet. Main thread only.
 */
public final class MapRegion {
    public static final int SHIFT = 9;
    public static final int SIZE = 1 << SHIFT;
    public static final int MASK = SIZE - 1;
    public static final int AREA = SIZE * SIZE;
    public static final int LOD_SCALE = 4;
    public static final int LOD_SIZE = SIZE / LOD_SCALE;
    public static final int UNKNOWN_HEIGHT = Short.MIN_VALUE;

    public final int rx;
    public final int rz;
    final int[] colors;
    final short[] heights;

    /** Changed since last save. */
    boolean dirtyForSave;
    /** Bumped on every change; render caches compare against it. */
    private int version;
    long lastUsedMs;

    private int dirtyMinX = SIZE, dirtyMinZ = SIZE, dirtyMaxX = -1, dirtyMaxZ = -1;

    MapRegion(int rx, int rz) {
        this(rx, rz, new int[AREA], new short[AREA]);
        Arrays.fill(heights, (short) UNKNOWN_HEIGHT);
    }

    MapRegion(int rx, int rz, int[] colors, short[] heights) {
        this.rx = rx;
        this.rz = rz;
        this.colors = colors;
        this.heights = heights;
        markTextureDirty(0, 0, MASK, MASK);
    }

    public static long key(int rx, int rz) {
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }

    public long key() {
        return key(rx, rz);
    }

    private static int index(int lx, int lz) {
        return lz << SHIFT | lx;
    }

    public int color(int lx, int lz) {
        return colors[index(lx, lz)];
    }

    public int height(int lx, int lz) {
        return heights[index(lx, lz)];
    }

    /** @return true if the column changed. */
    boolean set(int lx, int lz, int color, int height) {
        int i = index(lx, lz);
        if (colors[i] == color && heights[i] == height) {
            return false;
        }
        colors[i] = color;
        heights[i] = (short) height;
        dirtyForSave = true;
        // Shading of a column depends on its north and west neighbors, so the south/east ones change too.
        markTextureDirty(lx, lz, Math.min(lx + 1, MASK), Math.min(lz + 1, MASK));
        return true;
    }

    public int version() {
        return version;
    }

    public void markTextureDirty(int minX, int minZ, int maxX, int maxZ) {
        dirtyMinX = Math.min(dirtyMinX, minX);
        dirtyMinZ = Math.min(dirtyMinZ, minZ);
        dirtyMaxX = Math.max(dirtyMaxX, maxX);
        dirtyMaxZ = Math.max(dirtyMaxZ, maxZ);
        version++;
    }

    public boolean hasDirtyTexture() {
        return dirtyMaxX >= 0;
    }

    /** Returns {minX, minZ, maxX, maxZ} of the area to redraw and clears it, or null if clean. */
    public int[] takeDirtyRect() {
        if (!hasDirtyTexture()) {
            return null;
        }
        int[] rect = {dirtyMinX, dirtyMinZ, dirtyMaxX, dirtyMaxZ};
        dirtyMinX = dirtyMinZ = SIZE;
        dirtyMaxX = dirtyMaxZ = -1;
        return rect;
    }
}
