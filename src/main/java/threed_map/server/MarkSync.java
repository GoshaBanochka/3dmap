package threed_map.server;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;
import threed_map.common.net.MarkRemovePayload;
import threed_map.common.net.MarkUpsertPayload;
import threed_map.common.net.MarksSyncPayload;
import threed_map.common.net.ServerRulesPayload;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * Sends marks to clients. Invariant: a client only ever receives public marks and its own private ones.
 */
@EventBusSubscriber(modid = ThreedMapMod.MODID)
public final class MarkSync {
    private MarkSync() {
    }

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player, ServerConfig.rules());
            sendFullSync(player);
        }
    }

    static void broadcastRules(MinecraftServer server) {
        ServerRulesPayload rules = ServerConfig.rules();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            send(player, rules);
        }
    }

    public static void sendFullSync(ServerPlayer player) {
        List<Mark> visible = MarkStorage.get(player.server).all().stream()
                .filter(m -> m.isVisibleTo(player.getUUID()))
                .toList();
        send(player, new MarksSyncPayload(visible));
    }

    /** Broadcasts a created or edited mark. {@code previous} is null for a new mark. */
    static void onChanged(MinecraftServer server, Mark previous, Mark current) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            boolean seesNow = current.isVisibleTo(player.getUUID());
            boolean sawBefore = previous != null && previous.isVisibleTo(player.getUUID());
            if (seesNow) {
                send(player, new MarkUpsertPayload(current));
            } else if (sawBefore) {
                // Became private: everyone except the owner must forget it.
                send(player, new MarkRemovePayload(current.id()));
            }
        }
    }

    static void onRemoved(MinecraftServer server, Mark removed) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (removed.isVisibleTo(player.getUUID())) {
                send(player, new MarkRemovePayload(removed.id()));
            }
        }
    }

    /** Where outgoing payloads go. Replaced only by game tests to record traffic. */
    static BiConsumer<ServerPlayer, CustomPacketPayload> sink = MarkSync::sendToClient;

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        sink.accept(player, payload);
    }

    private static void sendToClient(ServerPlayer player, CustomPacketPayload payload) {
        // Players without the mod did not negotiate our channels.
        if (player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }
}
