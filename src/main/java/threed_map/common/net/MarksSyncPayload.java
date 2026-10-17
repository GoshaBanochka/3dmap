package threed_map.common.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;

import java.util.List;

/** S2C: full set of marks visible to the receiving player. Replaces the client's store. */
public record MarksSyncPayload(List<Mark> marks) implements CustomPacketPayload {
    public static final Type<MarksSyncPayload> TYPE = new Type<>(ThreedMapMod.id("marks_sync"));

    public static final StreamCodec<FriendlyByteBuf, MarksSyncPayload> STREAM_CODEC =
            Mark.STREAM_CODEC.apply(ByteBufCodecs.list()).map(MarksSyncPayload::new, MarksSyncPayload::marks);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
