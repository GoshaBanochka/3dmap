package threed_map.common.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import threed_map.client.ClientPayloadHandlers;
import threed_map.server.ServerPayloadHandlers;

public final class ModPayloads {
    /** Bump on any payload format change; a mismatched client is treated like one without the mod. */
    private static final String PROTOCOL_VERSION = "3";

    private ModPayloads() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Optional: players without the mod may still join; the server just skips them.
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();

        // Client handlers are referenced only inside lambda bodies, so the client
        // class is never loaded on a dedicated server.
        registrar.playToClient(MarksSyncPayload.TYPE, MarksSyncPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.onSync(payload));
        registrar.playToClient(MarkUpsertPayload.TYPE, MarkUpsertPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.onUpsert(payload));
        registrar.playToClient(MarkRemovePayload.TYPE, MarkRemovePayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.onRemove(payload));
        registrar.playToClient(MarkErrorPayload.TYPE, MarkErrorPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.onError(payload));
        registrar.playToClient(ServerRulesPayload.TYPE, ServerRulesPayload.STREAM_CODEC,
                (payload, context) -> ClientPayloadHandlers.onRules(payload));

        registrar.playToServer(CreateMarkPayload.TYPE, CreateMarkPayload.STREAM_CODEC, ServerPayloadHandlers::onCreate);
        registrar.playToServer(EditMarkPayload.TYPE, EditMarkPayload.STREAM_CODEC, ServerPayloadHandlers::onEdit);
        registrar.playToServer(DeleteMarkPayload.TYPE, DeleteMarkPayload.STREAM_CODEC, ServerPayloadHandlers::onDelete);
    }
}
