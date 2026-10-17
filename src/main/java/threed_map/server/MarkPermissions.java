package threed_map.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import threed_map.common.mark.Mark;
import threed_map.common.mark.PublicMarksPolicy;

/** Who may see, edit and publish marks. The OP level and public policy come from ServerConfig. */
public final class MarkPermissions {
    private MarkPermissions() {
    }

    public static boolean isOp(CommandSourceStack source) {
        return source.hasPermission(ServerConfig.opPermissionLevel());
    }

    /** Whether the source may see the mark at all. Others' private marks are hidden even from OPs. */
    public static boolean canSee(CommandSourceStack source, Mark mark) {
        ServerPlayer player = source.getPlayer();
        return mark.isPublic() || (player != null && mark.owner().equals(player.getUUID()));
    }

    public static boolean canEdit(CommandSourceStack source, Mark mark) {
        if (!canSee(source, mark)) {
            return false;
        }
        ServerPlayer player = source.getPlayer();
        return (player != null && mark.owner().equals(player.getUUID())) || isOp(source);
    }

    public static boolean canCreatePublic(CommandSourceStack source) {
        return ServerConfig.publicMarks() == PublicMarksPolicy.ALL || isOp(source);
    }
}
