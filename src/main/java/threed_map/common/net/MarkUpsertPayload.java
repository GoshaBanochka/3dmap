package threed_map.common.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;

/** S2C: a mark was created or changed. */
public record MarkUpsertPayload(Mark mark) implements CustomPacketPayload {
    public static final Type<MarkUpsertPayload> TYPE = new Type<>(ThreedMapMod.id("mark_upsert"));

    public static final StreamCodec<FriendlyByteBuf, MarkUpsertPayload> STREAM_CODEC =
            Mark.STREAM_CODEC.map(MarkUpsertPayload::new, MarkUpsertPayload::mark);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
