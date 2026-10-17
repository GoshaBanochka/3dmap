package threed_map.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import threed_map.ThreedMapMod;
import threed_map.client.ClientMarkPrefs;
import threed_map.client.KeyBindings;
import threed_map.client.map.data.MapDimension;
import threed_map.client.map.data.MapStorage;
import threed_map.client.map.data.ScanQueue;
import threed_map.client.map.data.WorldId;
import threed_map.client.map.render.MapTextures;
import threed_map.client.map.screen.MapScreen;
import threed_map.client.map.three.Map3DRenderer;

/**
 * Client map lifecycle: which server and dimension we are mapping, feeding chunks to the scanner,
 * saving on disconnect, and the open-map key.
 */
@EventBusSubscriber(modid = ThreedMapMod.MODID, value = Dist.CLIENT)
public final class MapClient {
    private static final ScanQueue SCAN = new ScanQueue();
    private static final MapTextures TEXTURES = new MapTextures();
    private static final Map3DRenderer RENDERER_3D = new Map3DRenderer();
    private static final int TEXTURE_EVICT_INTERVAL_TICKS = 100;

    private static String worldId;
    private static MapDimension current;
    private static int ticks;

    private MapClient() {
    }

    /** Map data of the player's current dimension, or null when not in a world. */
    public static MapDimension current() {
        return current;
    }

    public static MapTextures textures() {
        return TEXTURES;
    }

    public static Map3DRenderer renderer3d() {
        return RENDERER_3D;
    }

    public static int pendingChunks() {
        return SCAN.size();
    }

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk chunk) {
            ChunkPos pos = chunk.getPos();
            SCAN.enqueue(pos.x, pos.z);
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }
        if (worldId == null) {
            worldId = WorldId.current(mc);
            ClientMarkPrefs.open(WorldId.worldDir(mc, worldId));
        }
        if (current == null || current.dimension != level.dimension()) {
            switchDimension(mc, level);
        }
        SCAN.tick(level, current, player.chunkPosition().x, player.chunkPosition().z,
                mc.options.getEffectiveRenderDistance());
        current.tick(player.getBlockX(), player.getBlockZ());
        if (++ticks % TEXTURE_EVICT_INTERVAL_TICKS == 0) {
            TEXTURES.evictIdle();
        }

        while (KeyBindings.OPEN_MAP.consumeClick()) {
            if (mc.screen == null) {
                mc.setScreen(new MapScreen());
            }
        }
    }

    private static void switchDimension(Minecraft mc, ClientLevel level) {
        if (current != null) {
            current.close();
        }
        TEXTURES.clear();
        RENDERER_3D.close();
        // The scan queue is kept: chunks of the new dimension may already be in it. Stale positions
        // just find no chunk, and the periodic rescan covers anything loaded before the switch.
        current = new MapDimension(level.dimension(), WorldId.cacheDir(mc, worldId, level.dimension()));
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (current != null) {
            current.close();
            current = null;
        }
        TEXTURES.clear();
        RENDERER_3D.close();
        SCAN.clear();
        worldId = null;
        ClientMarkPrefs.open(null);
        // The game may be exiting: wait for pending writes, the IO thread is a daemon.
        MapStorage.flush();
    }
}
