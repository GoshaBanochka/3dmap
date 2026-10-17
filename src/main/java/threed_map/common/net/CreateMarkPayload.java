package threed_map.common.net;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkIcon;
import threed_map.common.mark.Visibility;

/** C2S: create a mark from the map GUI. Everything here is untrusted and re-validated by the server. */
public record CreateMarkPayload(String name, String description, int color, MarkIcon icon, BlockPos pos,
                                ResourceKey<Level> dimension, Visibility visibility) implements CustomPacketPayload {
    public static final Type<CreateMarkPayload> TYPE = new Type<>(ThreedMapMod.id("create_mark"));

    public static final StreamCodec<FriendlyByteBuf, CreateMarkPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.name, Mark.NAME_HARD_LIMIT);
                buf.writeUtf(p.description, Mark.DESCRIPTION_HARD_LIMIT);
                buf.writeInt(p.color);
                buf.writeEnum(p.icon);
                buf.writeBlockPos(p.pos);
                buf.writeResourceKey(p.dimension);
                buf.writeEnum(p.visibility);
            },
            buf -> new CreateMarkPayload(buf.readUtf(Mark.NAME_HARD_LIMIT), buf.readUtf(Mark.DESCRIPTION_HARD_LIMIT),
                    buf.readInt(), buf.readEnum(MarkIcon.class), buf.readBlockPos(),
                    buf.readResourceKey(Registries.DIMENSION), buf.readEnum(Visibility.class)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
