package threed_map.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkColors;
import threed_map.common.mark.MarkIcon;
import threed_map.common.mark.MarkLimits;
import threed_map.common.mark.Visibility;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The only entry point that mutates marks. Commands and GUI network handlers both go through it,
 * so validation, permissions, persistence and sync happen in one place. Limits come from ServerConfig.
 */
public final class MarkService {
    private MarkService() {
    }

    public static Mark create(CommandSourceStack source, String rawName, String rawDescription, Visibility visibility,
                              int color, MarkIcon icon, BlockPos pos, ResourceKey<Level> dimension) throws MarkException {
        ServerPlayer player = requirePlayer(source);
        MinecraftServer server = source.getServer();
        MarkStorage storage = MarkStorage.get(server);

        String name = validateName(rawName);
        String description = validateDescription(rawDescription);
        if (visibility == Visibility.PUBLIC && !MarkPermissions.canCreatePublic(source)) {
            throw new MarkException(Msg.tr("error.public_forbidden", "You are not allowed to create public marks"));
        }
        int maxMarks = ServerConfig.maxMarksPerPlayer();
        if (storage.countOwnedBy(player.getUUID()) >= maxMarks) {
            throw new MarkException(Msg.tr("error.limit", "Mark limit reached (%s)", maxMarks));
        }
        BlockPos validPos = validatePosition(server, pos, dimension);

        Mark mark = new Mark(UUID.randomUUID(), name, description, MarkColors.sanitize(color), icon, validPos, dimension,
                player.getUUID(), player.getGameProfile().getName(), System.currentTimeMillis(), visibility);
        storage.put(mark);
        MarkSync.onChanged(server, null, mark);
        return mark;
    }

    public static Mark rename(CommandSourceStack source, UUID id, String rawName) throws MarkException {
        String name = validateName(rawName);
        return update(source, id, requireEditable(source, id).withName(name));
    }

    public static Mark setDescription(CommandSourceStack source, UUID id, String rawDescription) throws MarkException {
        String description = validateDescription(rawDescription);
        return update(source, id, requireEditable(source, id).withDescription(description));
    }

    public static Mark setVisibility(CommandSourceStack source, UUID id, Visibility visibility) throws MarkException {
        Mark mark = requireEditable(source, id);
        if (visibility == Visibility.PUBLIC && !mark.isPublic() && !MarkPermissions.canCreatePublic(source)) {
            throw new MarkException(Msg.tr("error.public_forbidden", "You are not allowed to create public marks"));
        }
        return update(source, id, mark.withVisibility(visibility));
    }

    public static Mark setStyle(CommandSourceStack source, UUID id, int color, MarkIcon icon) throws MarkException {
        return update(source, id, requireEditable(source, id).withStyle(MarkColors.sanitize(color), icon));
    }

    /** Full edit from the GUI: all checks first, then a single store and broadcast. */
    public static Mark edit(CommandSourceStack source, UUID id, String rawName, String rawDescription, int color,
                            MarkIcon icon, BlockPos pos, Visibility visibility) throws MarkException {
        Mark mark = requireEditable(source, id);
        String name = validateName(rawName);
        String description = validateDescription(rawDescription);
        if (visibility == Visibility.PUBLIC && !mark.isPublic() && !MarkPermissions.canCreatePublic(source)) {
            throw new MarkException(Msg.tr("error.public_forbidden", "You are not allowed to create public marks"));
        }
        BlockPos validPos = validatePosition(source.getServer(), pos, mark.dimension());
        Mark updated = mark.withName(name).withDescription(description).withStyle(MarkColors.sanitize(color), icon)
                .withPos(validPos).withVisibility(visibility);
        return update(source, id, updated);
    }

    public static Mark remove(CommandSourceStack source, UUID id) throws MarkException {
        requireEditable(source, id);
        MinecraftServer server = source.getServer();
        Mark removed = MarkStorage.get(server).remove(id);
        MarkSync.onRemoved(server, removed);
        return removed;
    }

    /** Marks the source can see: all public ones plus its own private ones. */
    public static List<Mark> visibleTo(CommandSourceStack source) {
        return MarkStorage.get(source.getServer()).all().stream()
                .filter(m -> MarkPermissions.canSee(source, m))
                .toList();
    }

    public static List<Mark> editableBy(CommandSourceStack source) {
        return MarkStorage.get(source.getServer()).all().stream()
                .filter(m -> MarkPermissions.canEdit(source, m))
                .toList();
    }

    /**
     * Resolves a command argument to one editable mark: exact short id first, then name (case-insensitive).
     */
    public static Mark resolveEditable(CommandSourceStack source, String query) throws MarkException {
        List<Mark> editable = editableBy(source);
        String q = query.toLowerCase(Locale.ROOT);
        List<Mark> byId = editable.stream().filter(m -> m.shortId().equals(q) || m.id().toString().equals(q)).toList();
        if (byId.size() == 1) {
            return byId.get(0);
        }
        List<Mark> byName = editable.stream().filter(m -> m.name().equalsIgnoreCase(query)).toList();
        if (byName.isEmpty()) {
            throw new MarkException(Msg.tr("error.not_found", "No mark \"%s\" that you can edit", query));
        }
        if (byName.size() > 1) {
            throw new MarkException(Msg.tr("error.ambiguous",
                    "Several marks are named \"%s\"; use the id from /mark list", query));
        }
        return byName.get(0);
    }

    private static Mark update(CommandSourceStack source, UUID id, Mark updated) {
        MinecraftServer server = source.getServer();
        MarkStorage storage = MarkStorage.get(server);
        Mark previous = storage.get(id);
        storage.put(updated);
        MarkSync.onChanged(server, previous, updated);
        return updated;
    }

    private static Mark requireEditable(CommandSourceStack source, UUID id) throws MarkException {
        Mark mark = MarkStorage.get(source.getServer()).get(id);
        // Same message for "missing" and "someone else's private mark": do not leak existence.
        if (mark == null || !MarkPermissions.canSee(source, mark)) {
            throw new MarkException(Msg.tr("error.not_found_id", "Mark not found"));
        }
        if (!MarkPermissions.canEdit(source, mark)) {
            throw new MarkException(Msg.tr("error.not_owner", "You can only edit your own marks"));
        }
        return mark;
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) throws MarkException {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            throw new MarkException(Msg.tr("error.player_only", "Only players can create marks"));
        }
        return player;
    }

    static String validateName(String raw) throws MarkException {
        // Drop formatting codes and control characters; clients must not inject styling.
        StringBuilder sb = new StringBuilder(raw.length());
        raw.codePoints()
                .filter(cp -> cp != '§' && !Character.isISOControl(cp))
                .forEach(sb::appendCodePoint);
        String name = sb.toString().strip().replaceAll("\\s+", " ");
        if (name.isEmpty()) {
            throw new MarkException(Msg.tr("error.name_empty", "Mark name cannot be empty"));
        }
        int maxLength = ServerConfig.maxNameLength();
        if (name.length() > maxLength) {
            throw new MarkException(Msg.tr("error.name_long", "Mark name is too long (max %s)", maxLength));
        }
        return name;
    }

    /**
     * Description may be empty and may contain line breaks; other control characters and formatting codes are
     * dropped, trailing spaces trimmed and runs of blank lines collapsed.
     */
    static String validateDescription(String raw) throws MarkException {
        StringBuilder sb = new StringBuilder(raw.length());
        raw.replace("\r\n", "\n").codePoints()
                .filter(cp -> cp == '\n' || (cp != '§' && !Character.isISOControl(cp)))
                .forEach(sb::appendCodePoint);
        String text = sb.toString().replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
        int maxLength = ServerConfig.maxDescriptionLength();
        if (text.length() > maxLength) {
            throw new MarkException(Msg.tr("error.description_long", "Description is too long (max %s)",
                    maxLength));
        }
        if (text.chars().filter(c -> c == '\n').count() >= MarkLimits.MAX_DESCRIPTION_LINES) {
            throw new MarkException(Msg.tr("error.description_lines", "Description has too many lines (max %s)",
                    MarkLimits.MAX_DESCRIPTION_LINES));
        }
        return text;
    }

    /** Rejects unknown dimensions and positions outside the world border; clamps Y into build height. */
    private static BlockPos validatePosition(MinecraftServer server, BlockPos pos, ResourceKey<Level> dimension)
            throws MarkException {
        Level level = server.getLevel(dimension);
        if (level == null || !level.getWorldBorder().isWithinBounds(pos)) {
            throw new MarkException(Msg.tr("error.bad_pos", "Invalid mark position"));
        }
        int y = Math.clamp(pos.getY(), level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return new BlockPos(pos.getX(), y, pos.getZ());
    }
}
