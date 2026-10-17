package threed_map.client.map.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import threed_map.common.mark.MarkIcon;

import java.util.EnumMap;
import java.util.Map;

/** Mark icons are vanilla item sprites, so they match the rest of the game. */
public final class MarkIcons {
    /** Half size of an icon on the map at scale 1, for hit tests. */
    public static final int RADIUS = 8;
    private static final Map<MarkIcon, ItemStack> STACKS = new EnumMap<>(MarkIcon.class);

    static {
        STACKS.put(MarkIcon.PIN, new ItemStack(Items.NAME_TAG));
        STACKS.put(MarkIcon.HOME, new ItemStack(Items.OAK_DOOR));
        STACKS.put(MarkIcon.STAR, new ItemStack(Items.NETHER_STAR));
        STACKS.put(MarkIcon.CAVE, new ItemStack(Items.IRON_PICKAXE));
        STACKS.put(MarkIcon.PORTAL, new ItemStack(Items.ENDER_EYE));
        STACKS.put(MarkIcon.FARM, new ItemStack(Items.WHEAT));
        STACKS.put(MarkIcon.SHOP, new ItemStack(Items.EMERALD));
        STACKS.put(MarkIcon.DANGER, new ItemStack(Items.TNT));
    }

    private MarkIcons() {
    }

    public static ItemStack item(MarkIcon icon) {
        return STACKS.get(icon);
    }

    /** Type name shown under the mark name, e.g. "Home". */
    public static MutableComponent typeName(MarkIcon icon) {
        return Component.translatable("threed_map.icon." + icon.getSerializedName());
    }

    /** Draws the icon centered on (x, y), scaled (1 = 16 GUI pixels). */
    public static void draw(GuiGraphics g, MarkIcon icon, float x, float y, float scale) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1);
        g.renderItem(item(icon), -8, -8);
        pose.popPose();
    }
}
