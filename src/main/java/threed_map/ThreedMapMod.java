package threed_map;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;
import threed_map.common.net.ModPayloads;
import threed_map.server.ServerConfig;

@Mod(ThreedMapMod.MODID)
public class ThreedMapMod {
    public static final String MODID = "threed_map";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ThreedMapMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(ModPayloads::register);
        modContainer.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
