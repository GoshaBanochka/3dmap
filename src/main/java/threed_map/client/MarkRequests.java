package threed_map.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import threed_map.common.mark.Mark;
import threed_map.common.net.DeleteMarkPayload;

/** Sends mark requests from the GUI; the server answers with an update or a MarkErrorPayload. */
public final class MarkRequests {
    private MarkRequests() {
    }

    public static void send(CustomPacketPayload payload) {
        if (ClientMarkStore.serverSupportsMarks()) {
            PacketDistributor.sendToServer(payload);
        } else {
            ClientPayloadHandlers.showError(Component.translatable("threed_map.error.no_server_mod"));
        }
    }

    public static void delete(Mark mark) {
        send(new DeleteMarkPayload(mark.id()));
    }
}
