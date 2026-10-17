package threed_map.client.map.three;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import threed_map.client.map.data.MapRegion;
import threed_map.client.map.data.MapShading;

/**
 * Builds a blocky terrain mesh from a height/color snapshot: one column per cell with a colored top
 * and darker walls down to lower neighbors, like Minecraft's face lighting. Pure function of the
 * snapshot, safe to run on a worker thread.
 */
final class TerrainMesher {
    /** Face brightness as in Minecraft: top 1.0, north/south 0.8, east/west 0.6. */
    private static final float NS = 0.8f;
    private static final float EW = 0.6f;
    /** How far below the lowest known column the outer skirt goes. */
    private static final int SKIRT_DEPTH = 6;

    private TerrainMesher() {
    }

    /** Heights and base colors sampled on a square grid of n×n cells, each step blocks wide. */
    record Snapshot(int originX, int originZ, int step, int n, short[] heights, int[] colors, long dataKey) {
        boolean known(int i, int j) {
            return i >= 0 && j >= 0 && i < n && j < n && heights[j * n + i] != MapRegion.UNKNOWN_HEIGHT;
        }

        int height(int i, int j) {
            return heights[j * n + i];
        }

        int color(int i, int j) {
            return colors[j * n + i];
        }
    }

    /** Owns native memory until uploaded; mesh is null when nothing in the area is explored. */
    record Built(Snapshot snapshot, ByteBufferBuilder buffer, MeshData mesh) {
        /** After VertexBuffer.upload, which already closed the mesh. */
        void freeAfterUpload() {
            buffer.close();
        }

        /** When the result is thrown away without uploading. */
        void discard() {
            if (mesh != null) {
                mesh.close();
            }
            buffer.close();
        }
    }

    static Built build(Snapshot s) {
        int floor = Integer.MAX_VALUE;
        for (short h : s.heights) {
            if (h != MapRegion.UNKNOWN_HEIGHT) {
                floor = Math.min(floor, h);
            }
        }
        int base = floor == Integer.MAX_VALUE ? 0 : floor - SKIRT_DEPTH;

        ByteBufferBuilder buffer = new ByteBufferBuilder(1 << 20);
        BufferBuilder b = new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        int step = s.step;
        for (int j = 0; j < s.n; j++) {
            for (int i = 0; i < s.n; i++) {
                if (!s.known(i, j)) {
                    continue;
                }
                float top = s.height(i, j) + 1;
                int c = s.color(i, j);
                float x0 = i * step;
                float x1 = x0 + step;
                float z0 = j * step;
                float z1 = z0 + step;

                quad(b, c, x0, top, z0, x0, top, z1, x1, top, z1, x1, top, z0);

                // Walls down to each lower neighbor; unknown neighbors and the area edge get a skirt.
                float east = wallBottom(s, i + 1, j, base);
                if (east < top) {
                    quad(b, MapShading.scale(c, EW), x1, top, z0, x1, top, z1, x1, east, z1, x1, east, z0);
                }
                float west = wallBottom(s, i - 1, j, base);
                if (west < top) {
                    quad(b, MapShading.scale(c, EW), x0, top, z1, x0, top, z0, x0, west, z0, x0, west, z1);
                }
                float south = wallBottom(s, i, j + 1, base);
                if (south < top) {
                    quad(b, MapShading.scale(c, NS), x1, top, z1, x0, top, z1, x0, south, z1, x1, south, z1);
                }
                float north = wallBottom(s, i, j - 1, base);
                if (north < top) {
                    quad(b, MapShading.scale(c, NS), x0, top, z0, x1, top, z0, x1, north, z0, x0, north, z0);
                }
            }
        }
        return new Built(s, buffer, b.build());
    }

    private static float wallBottom(Snapshot s, int i, int j, int base) {
        return s.known(i, j) ? s.height(i, j) + 1 : base;
    }

    private static void quad(BufferBuilder b, int argb, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        b.addVertex(ax, ay, az).setColor(argb);
        b.addVertex(bx, by, bz).setColor(argb);
        b.addVertex(cx, cy, cz).setColor(argb);
        b.addVertex(dx, dy, dz).setColor(argb);
    }
}
