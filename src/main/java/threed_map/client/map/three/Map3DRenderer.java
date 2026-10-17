package threed_map.client.map.three;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import threed_map.ThreedMapMod;
import threed_map.client.map.data.MapDimension;
import threed_map.client.map.data.MapRegion;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 3D terrain view of the explored map. The mesh covers a square around the view center whose size and
 * cell step grow with camera distance. It is rebuilt on a worker thread when the view moves far enough
 * or the map data changes, and drawn into a screen rectangle with its own perspective. Render thread only.
 */
public final class Map3DRenderer {
    /** Upper bound for cells per side: at most ~65k columns, a few hundred thousand vertices. */
    private static final int MAX_CELLS = 256;
    /** Mesh side in blocks per unit of camera distance. */
    private static final float SIDE_PER_DISTANCE = 2.5f;
    private static final int MIN_SIDE = 128;
    private static final int MAX_SIDE = 2048;
    private static final long CHECK_INTERVAL_MS = 250;

    private static final ExecutorService MESHER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "threed_map-mesher");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private VertexBuffer vbo;
    private TerrainMesher.Snapshot current;
    private CompletableFuture<TerrainMesher.Built> pending;
    private long lastCheckMs;
    private volatile long lastBuildNanos;

    /** Matrices of the current frame, also used to project marks and the player. */
    private Matrix4f lastView;
    private Matrix4f viewProjection;
    private int originX;
    private int originZ;

    /**
     * Per-frame setup: takes a finished mesh, schedules a rebuild if needed, computes the matrices.
     * Call before render() and project().
     */
    public void setupFrame(MapDimension dim, OrbitCamera camera, double centerX, double centerZ, float targetY,
                           int w, int h) {
        acceptFinished();
        maybeRebuild(dim, camera, centerX, centerZ);
        if (current != null) {
            originX = current.originX();
            originZ = current.originZ();
        } else {
            originX = (int) Math.floor(centerX);
            originZ = (int) Math.floor(centerZ);
        }
        Matrix4f view = camera.view((float) (centerX - originX), targetY, (float) (centerZ - originZ));
        viewProjection = camera.projection(w / (float) h).mul(view);
        lastView = view;
    }

    /** Screen position {x, y} of a world point inside the given rectangle, or null if behind the camera. */
    public double[] project(double wx, double wy, double wz, int x0, int y0, int w, int h) {
        if (viewProjection == null) {
            return null;
        }
        double[] n = OrbitCamera.project(viewProjection, (float) (wx - originX), (float) wy, (float) (wz - originZ));
        return n == null ? null : new double[]{x0 + n[0] * w, y0 + n[1] * h};
    }

    /** Worker time of the last mesh build, for diagnostics. */
    public double lastBuildMillis() {
        return lastBuildNanos / 1e6;
    }

    /** {cells per side, block step} of the shown mesh, or null. */
    public int[] meshGrid() {
        return current == null ? null : new int[]{current.n(), current.step()};
    }

    /** Whether a mesh is shown (false until the first build finishes or when nothing is explored). */
    public boolean hasMesh() {
        return vbo != null && current != null;
    }

    /** Draws the terrain into the GUI rectangle (in GUI pixels). Call setupFrame first. */
    public void render(GuiGraphics g, OrbitCamera camera, int x0, int y0, int w, int h) {
        if (!hasMesh() || lastView == null) {
            return;
        }
        // Flush what the GUI has batched so far; the panel must not end up on top of the terrain.
        g.flush();
        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        double gs = window.getGuiScale();
        int vx = (int) Math.round(x0 * gs);
        int vw = (int) Math.round(w * gs);
        int vh = (int) Math.round(h * gs);
        int vy = window.getHeight() - (int) Math.round(y0 * gs) - vh;

        Matrix4f projection = camera.projection(w / (float) h);
        RenderSystem.viewport(vx, vy, vw, vh);
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableCull();
        RenderSystem.disableBlend();
        vbo.bind();
        vbo.drawWithShader(lastView, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.enableCull();
        // Restore the GUI state: full viewport and a clean depth buffer so later widgets draw on top.
        RenderSystem.viewport(0, 0, window.getWidth(), window.getHeight());
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
    }

    private void acceptFinished() {
        if (pending == null || !pending.isDone()) {
            return;
        }
        TerrainMesher.Built built = pending.getNow(null);
        pending = null;
        if (built == null) {
            return; // failed; logged by the future
        }
        if (built.mesh() == null) {
            built.discard();
            if (vbo != null) {
                vbo.close();
                vbo = null;
            }
        } else {
            if (vbo == null) {
                vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
            }
            vbo.bind();
            vbo.upload(built.mesh());
            VertexBuffer.unbind();
            built.freeAfterUpload();
        }
        current = built.snapshot();
    }

    private void maybeRebuild(MapDimension dim, OrbitCamera camera, double centerX, double centerZ) {
        long now = System.currentTimeMillis();
        if (pending != null || now - lastCheckMs < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheckMs = now;

        int side = Math.clamp(Math.round(camera.distance() * SIDE_PER_DISTANCE), MIN_SIDE, MAX_SIDE);
        int step = 1;
        while (side / step > MAX_CELLS) {
            step *= 2;
        }
        int n = side / step;
        int grid = step * 16;
        int ox = Math.floorDiv((int) Math.floor(centerX) - side / 2, grid) * grid;
        int oz = Math.floorDiv((int) Math.floor(centerZ) - side / 2, grid) * grid;
        boolean sameGrid = current != null && current.step() == step && current.n() == n;
        boolean near = sameGrid && Math.abs(current.originX() - ox) <= side / 6
                && Math.abs(current.originZ() - oz) <= side / 6;
        if (near) {
            // Keep the current origin so the mesh does not jump; rebuild only if the map data changed.
            ox = current.originX();
            oz = current.originZ();
        }
        long key = dataKey(dim, ox, oz, n * step);
        if (near && key == current.dataKey()) {
            return;
        }
        TerrainMesher.Snapshot snapshot = snapshot(dim, ox, oz, step, n, key);
        pending = CompletableFuture.supplyAsync(() -> {
                    long start = System.nanoTime();
                    TerrainMesher.Built built = TerrainMesher.build(snapshot);
                    lastBuildNanos = System.nanoTime() - start;
                    return built;
                }, MESHER)
                .exceptionally(e -> {
                    ThreedMapMod.LOGGER.error("3D map mesh build failed", e);
                    return null;
                });
    }

    /** Changes when any region in the area loads, unloads or changes; also requests missing regions. */
    private static long dataKey(MapDimension dim, int ox, int oz, int size) {
        long key = 17;
        for (int rz = oz >> MapRegion.SHIFT; rz <= (oz + size - 1) >> MapRegion.SHIFT; rz++) {
            for (int rx = ox >> MapRegion.SHIFT; rx <= (ox + size - 1) >> MapRegion.SHIFT; rx++) {
                MapRegion region = dim.isExplored(rx, rz) ? dim.regionForRender(rx, rz) : null;
                key = key * 31 + (region == null ? -1 : region.version());
            }
        }
        return key;
    }

    private static TerrainMesher.Snapshot snapshot(MapDimension dim, int ox, int oz, int step, int n, long key) {
        short[] heights = new short[n * n];
        int[] colors = new int[n * n];
        Arrays.fill(heights, (short) MapRegion.UNKNOWN_HEIGHT);
        int half = step / 2;
        MapRegion region = null;
        for (int j = 0; j < n; j++) {
            int wz = oz + j * step + half;
            for (int i = 0; i < n; i++) {
                int wx = ox + i * step + half;
                int rx = wx >> MapRegion.SHIFT;
                int rz = wz >> MapRegion.SHIFT;
                if (region == null || region.rx != rx || region.rz != rz) {
                    region = dim.loaded(rx, rz);
                    if (region == null) {
                        continue;
                    }
                }
                int lx = wx & MapRegion.MASK;
                int lz = wz & MapRegion.MASK;
                int color = region.color(lx, lz);
                if ((color >>> 24) != 0) {
                    heights[j * n + i] = (short) region.height(lx, lz);
                    colors[j * n + i] = color;
                }
            }
        }
        return new TerrainMesher.Snapshot(ox, oz, step, n, heights, colors, key);
    }

    /** Frees GPU and native memory. The instance can be used again afterwards (it rebuilds). */
    public void close() {
        if (vbo != null) {
            vbo.close();
            vbo = null;
        }
        current = null;
        viewProjection = null;
        lastView = null;
        if (pending != null) {
            // Free the result whenever the worker finishes.
            pending.thenAccept(built -> {
                if (built != null) {
                    RenderSystem.recordRenderCall(built::discard);
                }
            });
            pending = null;
        }
    }
}
