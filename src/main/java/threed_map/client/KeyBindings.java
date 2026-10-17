package threed_map.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import threed_map.ThreedMapMod;

@EventBusSubscriber(modid = ThreedMapMod.MODID, value = Dist.CLIENT)
public final class KeyBindings {
    private static final String CATEGORY = "key.categories.threed_map";

    public static final KeyMapping OPEN_MAP = new KeyMapping("key.threed_map.open_map",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, CATEGORY);

    private KeyBindings() {
    }

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MAP);
    }
}
