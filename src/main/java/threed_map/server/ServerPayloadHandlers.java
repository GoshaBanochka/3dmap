package threed_map.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import threed_map.ThreedMapMod;
import threed_map.common.net.CreateMarkPayload;
import threed_map.common.net.DeleteMarkPayload;
import threed_map.common.net.EditMarkPayload;
import threed_map.common.net.MarkErrorPayload;

/** GUI requests from clients. All checks happen in MarkService, exactly as for commands. */
@EventBusSubscriber(modid = ThreedMapMod.MODID)
public final class ServerPayloadHandlers {
    private ServerPayloadHandlers() {
    }

    @FunctionalInterface
    private interface Action {
        void run(CommandSourceStack source) throws MarkException;
    }

    public static void onCreate(CreateMarkPayload p, IPayloadContext ctx) {
        handle(ctx, source -> MarkService.create(source, p.name(), p.description(), p.visibility(), p.color(),
                p.icon(), p.pos(), p.dimension()));
    }

    public static void onEdit(EditMarkPayload p, IPayloadContext ctx) {
        handle(ctx, source -> MarkService.edit(source, p.id(), p.name(), p.description(), p.color(), p.icon(),
                p.pos(), p.visibility()));
    }

    public static void onDelete(DeleteMarkPayload p, IPayloadContext ctx) {
        handle(ctx, source -> MarkService.remove(source, p.id()));
    }

    private static void handle(IPayloadContext ctx, Action action) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        if (!MarkRateLimiter.tryAcquire(player.getUUID())) {
            ctx.reply(new MarkErrorPayload(Msg.tr("error.rate_limit", "Too many requests, wait a moment")));
            return;
        }
        try {
            action.run(player.createCommandSourceStack());
        } catch (MarkException e) {
            ctx.reply(new MarkErrorPayload(e.component()));
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        MarkRateLimiter.forget(event.getEntity().getUUID());
    }
}
