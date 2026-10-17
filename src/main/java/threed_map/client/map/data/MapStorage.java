package threed_map.client.map.data;

import threed_map.ThreedMapMod;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Disk cache of map regions. All file I/O runs on one background thread; callers pass copies of arrays.
 * <p>
 * r.X.Z.dat: gzip(magic, version, AREA ints color, AREA shorts height)
 * r.X.Z.lod: gzip(magic, version, LOD_SIZE² ints shaded color)
 */
public final class MapStorage {
    private static final int MAGIC = 0x334D4150; // "3MAP"
    private static final int VERSION = 1;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "threed_map-io");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private MapStorage() {
    }

    public record RegionData(int[] colors, short[] heights) {
    }

    static Path regionFile(Path dir, int rx, int rz) {
        return dir.resolve("r." + rx + "." + rz + ".dat");
    }

    static Path lodFile(Path dir, int rx, int rz) {
        return dir.resolve("r." + rx + "." + rz + ".lod");
    }

    static CompletableFuture<RegionData> loadRegion(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            try (DataInputStream in = open(file)) {
                ByteBuffer buf = ByteBuffer.wrap(in.readNBytes(MapRegion.AREA * 6));
                int[] colors = new int[MapRegion.AREA];
                short[] heights = new short[MapRegion.AREA];
                buf.asIntBuffer().get(colors);
                buf.position(MapRegion.AREA * 4);
                buf.asShortBuffer().get(heights);
                return new RegionData(colors, heights);
            } catch (IOException | RuntimeException e) {
                ThreedMapMod.LOGGER.warn("Discarding unreadable map region {}: {}", file, e.toString());
                return null;
            }
        }, IO);
    }

    static CompletableFuture<int[]> loadLod(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            try (DataInputStream in = open(file)) {
                int[] lod = new int[MapRegion.LOD_SIZE * MapRegion.LOD_SIZE];
                ByteBuffer.wrap(in.readNBytes(lod.length * 4)).asIntBuffer().get(lod);
                return lod;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }, IO);
    }

    static void save(Path dir, int rx, int rz, int[] colors, short[] heights, int[] lod) {
        IO.execute(() -> {
            try {
                Files.createDirectories(dir);
                ByteBuffer data = ByteBuffer.allocate(MapRegion.AREA * 6);
                data.asIntBuffer().put(colors);
                data.position(MapRegion.AREA * 4);
                data.asShortBuffer().put(heights);
                write(regionFile(dir, rx, rz), data.array());

                ByteBuffer lodBuf = ByteBuffer.allocate(lod.length * 4);
                lodBuf.asIntBuffer().put(lod);
                write(lodFile(dir, rx, rz), lodBuf.array());
            } catch (IOException e) {
                ThreedMapMod.LOGGER.error("Failed to save map region {} {} in {}", rx, rz, dir, e);
            }
        });
    }

    /** Blocks until all queued writes are done (used on disconnect, before the JVM may exit). */
    public static void flush() {
        try {
            CompletableFuture.runAsync(() -> {
            }, IO).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            ThreedMapMod.LOGGER.warn("Map cache flush did not finish: {}", e.toString());
        }
    }

    private static DataInputStream open(Path file) throws IOException {
        DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file), 65536));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            in.close();
            throw new IOException("bad header");
        }
        return in;
    }

    /** Writes to a temp file and moves it over the target so a crash never leaves a half-written region. */
    private static void write(Path target, byte[] payload) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw, 65536))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.write(payload);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
