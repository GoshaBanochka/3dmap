package threed_map.client.map.data;

/**
 * Turns stored base colors and heights into displayed ARGB pixels: relief shading from the north-west,
 * contour lines every few blocks of height and a light parchment tint, like a drawn map.
 */
public final class MapShading {
    private static final int MAX_STEP = 6;
    private static final float STEP_FACTOR = 0.045f;
    /** Height interval of the contour lines, in blocks. */
    private static final int CONTOUR_STEP = 8;
    private static final float CONTOUR_DARKEN = 0.84f;
    private static final int PARCHMENT = 0xFFE8DCC0;
    private static final float PARCHMENT_TINT = 0.16f;

    private MapShading() {
    }

    /** Shaded ARGB color of a column; 0 if unexplored. Light comes from the north-west. */
    public static int shaded(MapDimension dim, MapRegion region, int lx, int lz) {
        int color = region.color(lx, lz);
        if ((color >>> 24) == 0) {
            return 0;
        }
        int h = region.height(lx, lz);
        int wx = (region.rx << MapRegion.SHIFT) + lx;
        int wz = (region.rz << MapRegion.SHIFT) + lz;
        int north = lz > 0 ? region.height(lx, lz - 1) : dim.heightAt(wx, wz - 1);
        int west = lx > 0 ? region.height(lx - 1, lz) : dim.heightAt(wx - 1, wz);
        int diff = 0;
        boolean contour = false;
        if (north != MapRegion.UNKNOWN_HEIGHT) {
            diff += h - north;
            contour = Math.floorDiv(h, CONTOUR_STEP) != Math.floorDiv(north, CONTOUR_STEP);
        }
        if (west != MapRegion.UNKNOWN_HEIGHT) {
            diff += h - west;
            contour |= Math.floorDiv(h, CONTOUR_STEP) != Math.floorDiv(west, CONTOUR_STEP);
        }
        float f = 1f + Math.clamp(diff, -MAX_STEP, MAX_STEP) * STEP_FACTOR;
        if (contour) {
            f *= CONTOUR_DARKEN; // topographic line where the terrain crosses a height band
        }
        // A little of the parchment shows through, like ink on paper.
        return mix(f == 1f ? color : scale(color, f), PARCHMENT, PARCHMENT_TINT);
    }

    public static int scale(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
        int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) * f));
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** Linear blend: t=0 gives a, t=1 gives b. Alpha is taken from a. */
    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (a & 0xFF000000) | r << 16 | g << 8 | bl;
    }

    /** Downsampled shaded image (LOD_SIZE²) used when the map is zoomed out. */
    public static int[] buildLod(MapDimension dim, MapRegion region) {
        int s = MapRegion.LOD_SCALE;
        int[] lod = new int[MapRegion.LOD_SIZE * MapRegion.LOD_SIZE];
        for (int z = 0; z < MapRegion.LOD_SIZE; z++) {
            for (int x = 0; x < MapRegion.LOD_SIZE; x++) {
                int r = 0, g = 0, b = 0, n = 0;
                for (int dz = 0; dz < s; dz++) {
                    for (int dx = 0; dx < s; dx++) {
                        int c = shaded(dim, region, x * s + dx, z * s + dz);
                        if ((c >>> 24) != 0) {
                            r += (c >> 16) & 0xFF;
                            g += (c >> 8) & 0xFF;
                            b += c & 0xFF;
                            n++;
                        }
                    }
                }
                lod[z * MapRegion.LOD_SIZE + x] = n == 0 ? 0 : 0xFF000000 | (r / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
        return lod;
    }
}
