package threed_map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import threed_map.common.mark.MarkLimits;
import threed_map.common.mark.PublicMarksPolicy;
import threed_map.common.net.ServerRulesPayload;

/** The connected server's mark rules, for the GUI only; the server enforces them regardless. */
public final class ClientRules {
    private static final ServerRulesPayload DEFAULTS = new ServerRulesPayload(MarkLimits.MAX_MARKS_PER_PLAYER,
            MarkLimits.MAX_NAME_LENGTH, MarkLimits.MAX_DESCRIPTION_LENGTH, PublicMarksPolicy.ALL, 2);

    private static ServerRulesPayload rules = DEFAULTS;

    private ClientRules() {
    }

    static void set(ServerRulesPayload payload) {
        rules = payload;
    }

    static void reset() {
        rules = DEFAULTS;
    }

    public static int maxMarksPerPlayer() {
        return rules.maxMarksPerPlayer();
    }

    public static int maxNameLength() {
        return rules.maxNameLength();
    }

    public static int maxDescriptionLength() {
        return rules.maxDescriptionLength();
    }

    public static boolean isOp() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && player.hasPermissions(rules.opPermissionLevel());
    }

    public static boolean canCreatePublic() {
        return rules.publicMarks() == PublicMarksPolicy.ALL || isOp();
    }

    /** Marks the local player owns, public and private (the client always receives all of them). */
    public static int ownedMarks() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player == null ? 0
                : (int) ClientMarkStore.all().stream().filter(m -> m.owner().equals(player.getUUID())).count();
    }

    public static boolean atMarkLimit() {
        return ownedMarks() >= maxMarksPerPlayer();
    }
}
