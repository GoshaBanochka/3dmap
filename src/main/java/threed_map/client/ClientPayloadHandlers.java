package threed_map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import threed_map.ThreedMapMod;
import threed_map.common.net.MarkErrorPayload;
import threed_map.common.net.MarkRemovePayload;
import threed_map.common.net.MarkUpsertPayload;
import threed_map.common.net.MarksSyncPayload;
import threed_map.common.net.ServerRulesPayload;

/** Payload handlers run on the client main thread (NeoForge default). */
@EventBusSubscriber(modid = ThreedMapMod.MODID, value = Dist.CLIENT)
public final class ClientPayloadHandlers {
    private ClientPayloadHandlers() {
    }

    public static void onSync(MarksSyncPayload payload) {
        ClientMarkStore.replaceAll(payload.marks());
    }

    public static void onUpsert(MarkUpsertPayload payload) {
        ClientMarkStore.put(payload.mark());
    }

    public static void onRemove(MarkRemovePayload payload) {
        ClientMarkStore.remove(payload.id());
    }

    public static void onRules(ServerRulesPayload payload) {
        ClientRules.set(payload);
    }

    public static void onError(MarkErrorPayload payload) {
        showError(payload.message());
    }

    public static void showError(Component message) {
        SystemToast.addOrUpdate(Minecraft.getInstance().getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.translatable("threed_map.toast.title"), message);
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientMarkStore.clear();
        ClientRules.reset();
    }
}
