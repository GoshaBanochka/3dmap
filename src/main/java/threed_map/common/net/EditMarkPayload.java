package threed_map.common.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkIcon;
import threed_map.common.mark.Visibility;

import java.util.UUID;

/** C2S: replace the editable fields of a mark. Dimension, owner and date cannot change. */
public record EditMarkPayload(UUID id, String name, String description, int color, MarkIcon icon, BlockPos pos,
                              Visibility visibility) implements CustomPacketPayload {
    public static final Type<EditMarkPayload> TYPE = new Type<>(ThreedMapMod.id("edit_mark"));

    public static final StreamCodec<FriendlyByteBuf, EditMarkPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUUID(p.id);
                buf.writeUtf(p.name, Mark.NAME_HARD_LIMIT);
                buf.writeUtf(p.description, Mark.DESCRIPTION_HARD_LIMIT);
                buf.writeInt(p.color);
                buf.writeEnum(p.icon);
                buf.writeBlockPos(p.pos);
                buf.writeEnum(p.visibility);
            },
            buf -> new EditMarkPayload(buf.readUUID(), buf.readUtf(Mark.NAME_HARD_LIMIT),
                    buf.readUtf(Mark.DESCRIPTION_HARD_LIMIT), buf.readInt(),
                    buf.readEnum(MarkIcon.class), buf.readBlockPos(), buf.readEnum(Visibility.class)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
