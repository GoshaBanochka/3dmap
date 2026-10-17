package threed_map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import threed_map.common.mark.Mark;
import threed_map.common.net.CreateMarkPayload;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Marks received from the server for the current connection. Accessed on the client main thread only. */
public final class ClientMarkStore {
    private static final Map<UUID, Mark> MARKS = new LinkedHashMap<>();

    private ClientMarkStore() {
    }

    static void replaceAll(List<Mark> marks) {
        MARKS.clear();
        marks.forEach(m -> MARKS.put(m.id(), m));
    }

    static void put(Mark mark) {
        MARKS.put(mark.id(), mark);
    }

    static void remove(UUID id) {
        MARKS.remove(id);
    }

    static void clear() {
        MARKS.clear();
    }

    public static Collection<Mark> all() {
        return Collections.unmodifiableCollection(MARKS.values());
    }

    /** Mirrors MarkPermissions.canEdit for the GUI; the server checks again. */
    public static boolean canEdit(Mark mark) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        return mark.owner().equals(player.getUUID()) || (mark.isPublic() && ClientRules.isOp());
    }

    /** Whether the server runs this mod; without it the map still works but marks cannot be edited. */
    public static boolean serverSupportsMarks() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(CreateMarkPayload.TYPE);
    }
}
