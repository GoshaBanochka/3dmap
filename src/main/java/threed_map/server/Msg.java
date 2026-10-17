package threed_map.server;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Server-side messages. Players may join without the mod (no lang file),
 * so every key carries an English fallback.
 */
public final class Msg {
    private Msg() {
    }

    public static MutableComponent tr(String key, String fallback, Object... args) {
        return Component.translatableWithFallback("threed_map." + key, fallback, args);
    }
}
