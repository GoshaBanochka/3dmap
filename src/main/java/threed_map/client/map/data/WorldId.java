package threed_map.client.map.data;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Locale;

/** Folder names for the map cache: one per server (or singleplayer world) and per dimension. */
public final class WorldId {
    private static final String DEFAULT_PORT = ":25565";

    private WorldId() {
    }

    /** Folder of one server or singleplayer world; dimensions are subfolders. */
    public static Path worldDir(Minecraft mc, String worldId) {
        return mc.gameDirectory.toPath().resolve("threed_map").resolve(worldId);
    }

    public static Path cacheDir(Minecraft mc, String worldId, ResourceKey<Level> dimension) {
        return worldDir(mc, worldId)
                .resolve(sanitize(dimension.location().getNamespace() + "_" + dimension.location().getPath()));
    }

    /** Human-readable name for the map title: the world name in singleplayer, the server's list name otherwise. */
    public static String displayName(Minecraft mc) {
        if (mc.hasSingleplayerServer()) {
            return mc.getSingleplayerServer().getWorldData().getLevelName();
        }
        ServerData server = mc.getCurrentServer();
        return server != null ? server.name : "";
    }

    public static String current(Minecraft mc) {
        if (mc.hasSingleplayerServer()) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "sp_" + sanitize(root.getFileName().toString());
        }
        ServerData server = mc.getCurrentServer();
        if (server != null) {
            String address = server.ip.toLowerCase(Locale.ROOT);
            if (address.endsWith(DEFAULT_PORT)) {
                address = address.substring(0, address.length() - DEFAULT_PORT.length());
            }
            return (server.isRealm() ? "realms_" : "mp_") + sanitize(address);
        }
        return "unknown";
    }

    static String sanitize(String s) {
        // Keep letters of any script: world folders like "Мир" and "Дом" must not collide.
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}._-]", "_");
    }
}
