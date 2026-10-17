package threed_map.common.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import threed_map.ThreedMapMod;

import java.util.UUID;

/** S2C: a mark was deleted or is no longer visible to the receiver. */
public record MarkRemovePayload(UUID id) implements CustomPacketPayload {
    public static final Type<MarkRemovePayload> TYPE = new Type<>(ThreedMapMod.id("mark_remove"));

    public static final StreamCodec<ByteBuf, MarkRemovePayload> STREAM_CODEC =
            UUIDUtil.STREAM_CODEC.map(MarkRemovePayload::new, MarkRemovePayload::id);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
