package threed_map.client.map.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import threed_map.client.ClientMarkPrefs;
import threed_map.client.ClientMarkStore;
import threed_map.common.mark.Mark;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Marks of one dimension drawn over the map: the item icon with its name on a small parchment label
 * underlined in the mark's color. Marks the player hid are skipped.
 */
final class MarkLayer {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final int HIT_RADIUS = MarkIcons.RADIUS + 1;
    private static final int TOOLTIP_WIDTH = 200;
    /** Names are drawn under icons from this zoom (real px per block) upwards. */
    private static final double LABEL_ZOOM = 1.5;
    private static final int HIGHLIGHT = 0xFFCFAE6B;

    private MarkLayer() {
    }

    /** World to GUI pixels for a map drawn into a screen rectangle (2D top-down or 3D perspective). */
    interface Projection {
        /** Screen point {x, y} of a world position, or null when it cannot be shown. */
        double[] project(double x, double y, double z);

        int x0();

        int y0();

        int width();

        int height();

        default boolean contains(double sx, double sy) {
            return sx >= x0() && sy >= y0() && sx < x0() + width() && sy < y0() + height();
        }
    }

    /** Top-down projection of the 2D map. */
    record View(double centerX, double centerZ, double scale, int x0, int y0, int width, int height)
            implements Projection {
        @Override
        public double[] project(double x, double y, double z) {
            return new double[]{screenX(x), screenY(z)};
        }

        double screenX(double blockX) {
            return x0 + width / 2.0 + (blockX - centerX) * scale;
        }

        double screenY(double blockZ) {
            return y0 + height / 2.0 + (blockZ - centerZ) * scale;
        }

        double blockX(double screenX) {
            return centerX + (screenX - x0 - width / 2.0) / scale;
        }

        double blockZ(double screenY) {
            return centerZ + (screenY - y0 - height / 2.0) / scale;
        }
    }

    /** Where a mark's icon goes: above the top of its block. */
    private static double[] place(Projection p, Mark mark) {
        return p.project(mark.pos().getX() + 0.5, mark.pos().getY() + 1, mark.pos().getZ() + 0.5);
    }

    private static boolean shown(Mark mark, ResourceKey<Level> dimension) {
        return mark.dimension().equals(dimension) && !ClientMarkPrefs.isHidden(mark.id());
    }

    /** Whether names are drawn under icons on the 2D map at this zoom (real px per block). */
    static boolean labelsAtZoom(double zoom) {
        return zoom >= LABEL_ZOOM;
    }

    /** Draws all shown marks of the dimension, clipped to the view; highlighted ones are drawn last. */
    static void draw(GuiGraphics g, Font font, ResourceKey<Level> dimension, Projection view, boolean labels,
                     UUID hovered, UUID selected) {
        g.enableScissor(view.x0(), view.y0(), view.x0() + view.width(), view.y0() + view.height());
        List<Mark> top = new ArrayList<>(2);
        for (Mark mark : ClientMarkStore.all()) {
            if (!shown(mark, dimension)) {
                continue;
            }
            if (mark.id().equals(hovered) || mark.id().equals(selected)) {
                top.add(mark);
            } else {
                drawOne(g, font, view, mark, labels, false);
            }
        }
        top.forEach(mark -> drawOne(g, font, view, mark, labels, true));
        g.disableScissor();
    }

    private static void drawOne(GuiGraphics g, Font font, Projection view, Mark mark, boolean labels,
                                boolean highlighted) {
        double[] p = place(view, mark);
        if (p == null) {
            return;
        }
        int x = (int) Math.round(p[0]);
        int y = (int) Math.round(p[1]);
        if (x < view.x0() - 60 || y < view.y0() - 20 || x > view.x0() + view.width() + 60
                || y > view.y0() + view.height() + 30) {
            return; // off the map; the scissor clips partially visible ones
        }
        if (highlighted) {
            g.fill(x - 10, y - 10, x + 10, y + 10, HIGHLIGHT);
            g.fill(x - 9, y - 9, x + 9, y + 9, 0xFF2B1D12);
            g.fill(x - 8, y - 8, x + 8, y + 8, ParchmentUi.PAPER);
        }
        MarkIcons.draw(g, mark.icon(), x, y, 1);
        if (labels || highlighted) {
            drawLabel(g, font, mark, x, y + 11);
        }
    }

    /** Parchment name plate centered under the icon, underlined in the mark's color. */
    private static void drawLabel(GuiGraphics g, Font font, Mark mark, int cx, int top) {
        int w = font.width(mark.name()) + 6;
        int x = cx - w / 2;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200); // above item icons of neighboring marks
        g.fill(x, top, x + w, top + 12, 0xFF2B1D12);
        g.fill(x + 1, top + 1, x + w - 1, top + 11, ParchmentUi.PAPER);
        g.fill(x + 1, top + 9, x + w - 1, top + 11, 0xFF000000 | mark.color());
        g.drawString(font, mark.name(), x + 3, top + 1, mark.isPublic() ? ParchmentUi.TEXT : ParchmentUi.TEXT_PRIVATE,
                false);
        g.pose().popPose();
    }

    /** Closest shown mark within the hit radius of a screen point inside the view. */
    static Mark markAt(ResourceKey<Level> dimension, Projection view, double mouseX, double mouseY) {
        if (!view.contains(mouseX, mouseY)) {
            return null;
        }
        Mark best = null;
        double bestDist = HIT_RADIUS * HIT_RADIUS;
        for (Mark mark : ClientMarkStore.all()) {
            if (!shown(mark, dimension)) {
                continue;
            }
            double[] p = place(view, mark);
            if (p == null) {
                continue;
            }
            double dx = p[0] - mouseX;
            double dy = p[1] - mouseY;
            double d = dx * dx + dy * dy;
            if (d <= bestDist) {
                best = mark;
                bestDist = d;
            }
        }
        return best;
    }

    private static int luminance(int rgb) {
        return (((rgb >> 16) & 0xFF) * 299 + ((rgb >> 8) & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    static List<FormattedCharSequence> tooltip(Font font, Mark mark) {
        BlockPos p = mark.pos();
        List<FormattedCharSequence> out = new ArrayList<>();
        // The tooltip background is dark: very dark mark colors would make the name unreadable.
        int titleColor = luminance(mark.color()) < 70 ? 0xFFFFFF : mark.color();
        out.add(Component.literal(mark.name()).withStyle(Style.EMPTY.withColor(titleColor).withBold(true))
                .getVisualOrderText());
        out.add(MarkIcons.typeName(mark.icon()).withStyle(ChatFormatting.GRAY).getVisualOrderText());
        out.add(Component.literal("%d, %d, %d".formatted(p.getX(), p.getY(), p.getZ())).withStyle(ChatFormatting.GRAY)
                .getVisualOrderText());
        if (!mark.description().isEmpty()) {
            for (String paragraph : mark.description().split("\n", -1)) {
                if (paragraph.isEmpty()) {
                    out.add(FormattedCharSequence.EMPTY);
                } else {
                    out.addAll(font.split(Component.literal(paragraph).withStyle(ChatFormatting.WHITE), TOOLTIP_WIDTH));
                }
            }
        }
        out.add(Component.translatable("threed_map.mark.author", mark.ownerName()).withStyle(ChatFormatting.DARK_GRAY)
                .getVisualOrderText());
        out.add(Component.translatable("threed_map.mark.created_at", DATE.format(Instant.ofEpochMilli(mark.createdAt())))
                .withStyle(ChatFormatting.DARK_GRAY).getVisualOrderText());
        out.add((mark.isPublic()
                ? Component.translatable("threed_map.visibility.public_long").withStyle(ChatFormatting.GREEN)
                : Component.translatable("threed_map.visibility.private_long").withStyle(ChatFormatting.GOLD))
                .getVisualOrderText());
        return out;
    }
}
