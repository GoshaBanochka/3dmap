package threed_map.common.mark;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * A single map mark. Immutable; edits produce a new instance with the same id.
 */
public record Mark(
        UUID id,
        String name,
        String description,
        int color,
        MarkIcon icon,
        BlockPos pos,
        ResourceKey<Level> dimension,
        UUID owner,
        String ownerName,
        long createdAt,
        Visibility visibility
) {
    /** Hard caps for text on the wire; the configurable limits are always below these. */
    public static final int NAME_HARD_LIMIT = 64;
    public static final int DESCRIPTION_HARD_LIMIT = 512;
    private static final int OWNER_NAME_LIMIT = 16;

    public static final Codec<Mark> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(Mark::id),
            Codec.STRING.fieldOf("name").forGetter(Mark::name),
            // Optional: marks saved before descriptions existed load with an empty one.
            Codec.STRING.optionalFieldOf("description", "").forGetter(Mark::description),
            Codec.INT.fieldOf("color").forGetter(Mark::color),
            MarkIcon.CODEC.optionalFieldOf("icon", MarkIcon.PIN).forGetter(Mark::icon),
            BlockPos.CODEC.fieldOf("pos").forGetter(Mark::pos),
            Level.RESOURCE_KEY_CODEC.fieldOf("dimension").forGetter(Mark::dimension),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(Mark::owner),
            Codec.STRING.fieldOf("owner_name").forGetter(Mark::ownerName),
            Codec.LONG.fieldOf("created_at").forGetter(Mark::createdAt),
            Visibility.CODEC.fieldOf("visibility").forGetter(Mark::visibility)
    ).apply(i, Mark::new));

    public static final StreamCodec<FriendlyByteBuf, Mark> STREAM_CODEC = StreamCodec.of(Mark::write, Mark::read);

    private static void write(FriendlyByteBuf buf, Mark m) {
        buf.writeUUID(m.id);
        buf.writeUtf(m.name, NAME_HARD_LIMIT);
        buf.writeUtf(m.description, DESCRIPTION_HARD_LIMIT);
        buf.writeInt(m.color);
        buf.writeEnum(m.icon);
        buf.writeBlockPos(m.pos);
        buf.writeResourceKey(m.dimension);
        buf.writeUUID(m.owner);
        buf.writeUtf(m.ownerName, OWNER_NAME_LIMIT);
        buf.writeVarLong(m.createdAt);
        buf.writeEnum(m.visibility);
    }

    private static Mark read(FriendlyByteBuf buf) {
        return new Mark(
                buf.readUUID(),
                buf.readUtf(NAME_HARD_LIMIT),
                buf.readUtf(DESCRIPTION_HARD_LIMIT),
                buf.readInt(),
                buf.readEnum(MarkIcon.class),
                buf.readBlockPos(),
                buf.readResourceKey(Registries.DIMENSION),
                buf.readUUID(),
                buf.readUtf(OWNER_NAME_LIMIT),
                buf.readVarLong(),
                buf.readEnum(Visibility.class));
    }

    public boolean isPublic() {
        return visibility == Visibility.PUBLIC;
    }

    public boolean isVisibleTo(UUID player) {
        return isPublic() || owner.equals(player);
    }

    /** First 8 hex digits of the id, used to address marks in commands. */
    public String shortId() {
        return id.toString().substring(0, 8);
    }

    public Mark withName(String name) {
        return new Mark(id, name, description, color, icon, pos, dimension, owner, ownerName, createdAt, visibility);
    }

    public Mark withDescription(String description) {
        return new Mark(id, name, description, color, icon, pos, dimension, owner, ownerName, createdAt, visibility);
    }

    public Mark withVisibility(Visibility visibility) {
        return new Mark(id, name, description, color, icon, pos, dimension, owner, ownerName, createdAt, visibility);
    }

    public Mark withStyle(int color, MarkIcon icon) {
        return new Mark(id, name, description, color, icon, pos, dimension, owner, ownerName, createdAt, visibility);
    }

    public Mark withPos(BlockPos pos) {
        return new Mark(id, name, description, color, icon, pos, dimension, owner, ownerName, createdAt, visibility);
    }
}
