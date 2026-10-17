package threed_map.server;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkLimits;
import threed_map.common.mark.PublicMarksPolicy;
import threed_map.common.net.ServerRulesPayload;

/**
 * Server config: config/threed_map-server.toml in the server folder (NeoForge 21.1 keeps SERVER configs there,
 * not per world). Edits to the file are picked up live and the new rules are sent to all players.
 */
@EventBusSubscriber(modid = ThreedMapMod.MODID)
public final class ServerConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static final ModConfigSpec.IntValue MAX_MARKS_PER_PLAYER = BUILDER
            .comment("How many marks (public and private together) one player may own.",
                    "Lowering it keeps existing marks; the player just cannot add new ones until under the limit.")
            .defineInRange("maxMarksPerPlayer", MarkLimits.MAX_MARKS_PER_PLAYER, 0, 10_000);

    static final ModConfigSpec.IntValue MAX_NAME_LENGTH = BUILDER
            .comment("Maximum length of a mark name, in characters.")
            .defineInRange("maxNameLength", MarkLimits.MAX_NAME_LENGTH, 1, Mark.NAME_HARD_LIMIT);

    static final ModConfigSpec.IntValue MAX_DESCRIPTION_LENGTH = BUILDER
            .comment("Maximum length of a mark description, in characters. 0 disables descriptions.")
            .defineInRange("maxDescriptionLength", MarkLimits.MAX_DESCRIPTION_LENGTH, 0, Mark.DESCRIPTION_HARD_LIMIT);

    static final ModConfigSpec.EnumValue<PublicMarksPolicy> PUBLIC_MARKS = BUILDER
            .comment("Who may create public marks or make a private mark public: ALL or OP_ONLY.",
                    "Existing public marks stay public; their owners can still edit or delete them.")
            .defineEnum("publicMarks", PublicMarksPolicy.ALL);

    static final ModConfigSpec.IntValue OP_PERMISSION_LEVEL = BUILDER
            .comment("Permission level that counts as OP: may edit or delete any public mark",
                    "and create public marks when publicMarks = OP_ONLY. 2 is the vanilla default for /op.")
            .defineInRange("opPermissionLevel", 2, 1, 4);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ServerConfig() {
    }

    public static int maxMarksPerPlayer() {
        return MAX_MARKS_PER_PLAYER.get();
    }

    public static int maxNameLength() {
        return MAX_NAME_LENGTH.get();
    }

    public static int maxDescriptionLength() {
        return MAX_DESCRIPTION_LENGTH.get();
    }

    public static PublicMarksPolicy publicMarks() {
        return PUBLIC_MARKS.get();
    }

    public static int opPermissionLevel() {
        return OP_PERMISSION_LEVEL.get();
    }

    public static ServerRulesPayload rules() {
        return new ServerRulesPayload(maxMarksPerPlayer(), maxNameLength(), maxDescriptionLength(), publicMarks(),
                opPermissionLevel());
    }

    @SubscribeEvent
    static void onReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        // The file watcher fires this on its own thread; send from the server thread.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> {
                ThreedMapMod.LOGGER.info("Map Marks config reloaded, sending new rules to players");
                MarkSync.broadcastRules(server);
            });
        }
    }
}
