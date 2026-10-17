package threed_map.client.map.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import threed_map.client.map.data.MapDimension;
import threed_map.client.map.data.MapRegion;

/** Draws explored regions into a screen rectangle. Shared by the map screen and (later) the minimap. */
public final class MapRenderer {
    private MapRenderer() {
    }

    /**
     * @param scale      GUI pixels per block
     * @param fullDetail use 512² region textures; otherwise zoomed-out previews
     */
    public static void draw(GuiGraphics g, MapDimension dim, MapTextures textures,
                            double centerX, double centerZ, double scale, boolean fullDetail,
                            int x0, int y0, int w, int h) {
        textures.beginFrame(dim);
        double halfW = w / 2.0 / scale;
        double halfH = h / 2.0 / scale;
        int rxMin = (int) Math.floor(centerX - halfW) >> MapRegion.SHIFT;
        int rxMax = (int) Math.floor(centerX + halfW) >> MapRegion.SHIFT;
        int rzMin = (int) Math.floor(centerZ - halfH) >> MapRegion.SHIFT;
        int rzMax = (int) Math.floor(centerZ + halfH) >> MapRegion.SHIFT;

        g.enableScissor(x0, y0, x0 + w, y0 + h);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        PoseStack pose = g.pose();
        for (int rz = rzMin; rz <= rzMax; rz++) {
            for (int rx = rxMin; rx <= rxMax; rx++) {
                if (!dim.isExplored(rx, rz)) {
                    continue;
                }
                ResourceLocation id = null;
                int size = MapRegion.SIZE;
                if (fullDetail) {
                    MapRegion region = dim.regionForRender(rx, rz);
                    if (region != null) {
                        id = textures.full(region);
                    }
                }
                if (id == null) {
                    // Zoomed out, or full detail not ready yet: fall back to the preview.
                    id = textures.lod(rx, rz);
                    size = MapRegion.LOD_SIZE;
                }
                if (id == null) {
                    continue;
                }
                double sx = x0 + w / 2.0 + ((double) (rx << MapRegion.SHIFT) - centerX) * scale;
                double sy = y0 + h / 2.0 + ((double) (rz << MapRegion.SHIFT) - centerZ) * scale;
                float texelScale = (float) (scale * MapRegion.SIZE / size);
                pose.pushPose();
                pose.translate(sx, sy, 0);
                pose.scale(texelScale, texelScale, 1);
                g.blit(id, 0, 0, size, size, 0, 0, size, size, size, size);
                pose.popPose();
            }
        }
        RenderSystem.disableBlend();
        g.disableScissor();
    }
}
