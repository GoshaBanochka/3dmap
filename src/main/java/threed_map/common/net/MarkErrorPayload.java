package threed_map.common.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import threed_map.ThreedMapMod;

/** S2C: a GUI request was rejected. Shown as a toast, since chat is hidden behind the map. */
public record MarkErrorPayload(Component message) implements CustomPacketPayload {
    public static final Type<MarkErrorPayload> TYPE = new Type<>(ThreedMapMod.id("mark_error"));

    public static final StreamCodec<RegistryFriendlyByteBuf, MarkErrorPayload> STREAM_CODEC =
            ComponentSerialization.STREAM_CODEC.map(MarkErrorPayload::new, MarkErrorPayload::message);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
