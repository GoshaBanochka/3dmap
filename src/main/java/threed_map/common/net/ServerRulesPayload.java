package threed_map.common.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import threed_map.ThreedMapMod;
import threed_map.common.mark.PublicMarksPolicy;

/**
 * S2C: the server's mark rules from its config, so the GUI can show limits and disable what is not allowed.
 * Sent on login and whenever the config file is reloaded. The server still enforces every rule itself.
 */
public record ServerRulesPayload(int maxMarksPerPlayer, int maxNameLength, int maxDescriptionLength,
                                 PublicMarksPolicy publicMarks, int opPermissionLevel) implements CustomPacketPayload {
    public static final Type<ServerRulesPayload> TYPE = new Type<>(ThreedMapMod.id("server_rules"));

    public static final StreamCodec<FriendlyByteBuf, ServerRulesPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.maxMarksPerPlayer);
                buf.writeVarInt(p.maxNameLength);
                buf.writeVarInt(p.maxDescriptionLength);
                buf.writeEnum(p.publicMarks);
                buf.writeVarInt(p.opPermissionLevel);
            },
            buf -> new ServerRulesPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readEnum(PublicMarksPolicy.class), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
