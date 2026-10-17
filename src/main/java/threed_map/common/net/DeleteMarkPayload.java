package threed_map.common.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import threed_map.ThreedMapMod;

import java.util.UUID;

/** C2S: delete a mark. */
public record DeleteMarkPayload(UUID id) implements CustomPacketPayload {
    public static final Type<DeleteMarkPayload> TYPE = new Type<>(ThreedMapMod.id("delete_mark"));

    public static final StreamCodec<ByteBuf, DeleteMarkPayload> STREAM_CODEC =
            UUIDUtil.STREAM_CODEC.map(DeleteMarkPayload::new, DeleteMarkPayload::id);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
