package threed_map.client.map.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import threed_map.client.ClientMarkPrefs;
import threed_map.client.ClientMarkStore;
import threed_map.common.mark.Mark;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Left panel: "— Marks · N —", a search field (an EditBox owned by the screen, placed by searchBounds),
 * and the dimension's marks nearest first. Each row has the icon, name, distance and an eye that hides
 * the mark on this player's map.
 */
final class MarkListPanel {
    static final int ROW = 18;
    private static final int SCROLLBAR = 4;
    private static final int EYE_W = 11;
    private static final int HEADER_H = 36;

    private int x;
    private int y;
    private int w;
    private int h;
    private double scroll;
    private List<Mark> entries = List.of();
    private int total;

    void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    /** {x, y, w, h} for the search EditBox, inside the field drawn by render(). */
    int[] searchBounds() {
        return new int[]{x + 18, y + 22, w - 26, 10};
    }

    // Rows area.
    int rowsX() {
        return x + 6;
    }

    int rowsY() {
        return y + HEADER_H;
    }

    int rowsW() {
        return w - 12;
    }

    int rowsH() {
        return h - HEADER_H - 6;
    }

    /** Rebuilds the entry list; call once per frame before rendering or hit tests. */
    void refresh(ResourceKey<Level> dimension, String query) {
        Vec3 player = Minecraft.getInstance().player.position();
        String q = query.strip().toLowerCase(Locale.ROOT);
        List<Mark> inDimension = ClientMarkStore.all().stream()
                .filter(m -> m.dimension().equals(dimension))
                .toList();
        total = inDimension.size();
        entries = inDimension.stream()
                .filter(m -> q.isEmpty() || m.name().toLowerCase(Locale.ROOT).contains(q)
                        || m.description().toLowerCase(Locale.ROOT).contains(q))
                .sorted(Comparator.comparingDouble(m -> distanceSq(m, player)))
                .toList();
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    int size() {
        return entries.size();
    }

    private double maxScroll() {
        return Math.max(0, entries.size() * ROW - rowsH());
    }

    private int indexAt(double mx, double my) {
        if (mx < rowsX() || mx >= rowsX() + rowsW() || my < rowsY() || my >= rowsY() + rowsH()) {
            return -1;
        }
        int index = (int) ((my - rowsY() + scroll) / ROW);
        return index < entries.size() ? index : -1;
    }

    Mark markAt(double mx, double my) {
        int i = indexAt(mx, my);
        return i < 0 ? null : entries.get(i);
    }

    /** Whether the point is on the eye toggle of a row. */
    boolean onEye(double mx, double my) {
        return indexAt(mx, my) >= 0 && mx >= eyeX() - 2;
    }

    private int eyeX() {
        boolean scrollable = maxScroll() > 0;
        return rowsX() + rowsW() - EYE_W - (scrollable ? SCROLLBAR + 1 : 0) + 2;
    }

    void scrollBy(double rows) {
        scroll = Mth.clamp(scroll - rows * ROW, 0, maxScroll());
    }

    void render(GuiGraphics g, Font font, int mouseX, int mouseY, UUID selected) {
        ParchmentUi.panel(g, x, y, w, h);
        ParchmentUi.heading(g, font, Component.translatable("threed_map.list.title", total).getString(), x, y + 8, w);
        ParchmentUi.field(g, x + 6, y + 19, w - 12, 13);
        ParchmentUi.magnifier(g, x + 9, y + 22, ParchmentUi.TEXT_DIM);

        if (entries.isEmpty()) {
            List<FormattedCharSequence> lines = font.split(Component.translatable(
                    total == 0 ? "threed_map.list.empty" : "threed_map.list.no_results"), rowsW() - 8);
            int ty = rowsY() + 6;
            for (FormattedCharSequence line : lines) {
                g.drawString(font, line, x + (w - font.width(line)) / 2, ty, ParchmentUi.TEXT_DIM, false);
                ty += font.lineHeight + 1;
            }
            return;
        }
        Vec3 player = Minecraft.getInstance().player.position();
        int hoveredIndex = indexAt(mouseX, mouseY);
        boolean scrollable = maxScroll() > 0;
        int rowW = rowsW() - (scrollable ? SCROLLBAR + 1 : 0);
        g.enableScissor(rowsX(), rowsY(), rowsX() + rowsW(), rowsY() + rowsH());
        for (int i = 0; i < entries.size(); i++) {
            int ry = rowsY() + i * ROW - (int) scroll;
            if (ry + ROW < rowsY() || ry > rowsY() + rowsH()) {
                continue;
            }
            Mark mark = entries.get(i);
            boolean hidden = ClientMarkPrefs.isHidden(mark.id());
            if (mark.id().equals(selected)) {
                g.fill(rowsX(), ry, rowsX() + rowW, ry + ROW, ParchmentUi.LINE);
                g.fill(rowsX() + 1, ry + 1, rowsX() + rowW - 1, ry + ROW - 1, ParchmentUi.ROW_SELECTED);
            } else if (i == hoveredIndex) {
                g.fill(rowsX(), ry, rowsX() + rowW, ry + ROW, ParchmentUi.ROW_HOVER);
            }
            g.fill(rowsX() + 1, ry + 2, rowsX() + 3, ry + ROW - 2, 0xFF000000 | mark.color());
            MarkIcons.draw(g, mark.icon(), rowsX() + 13, ry + ROW / 2f, 1);

            int textColor = hidden ? ParchmentUi.TEXT_DISABLED
                    : mark.isPublic() ? ParchmentUi.TEXT : ParchmentUi.TEXT_PRIVATE;
            String distance = Component.translatable("threed_map.list.distance",
                    (int) Math.sqrt(distanceSq(mark, player))).getString();
            int eyeX = eyeX();
            int distX = eyeX - 4 - font.width(distance);
            int nameMax = distX - (rowsX() + 23) - 4;
            String name = font.plainSubstrByWidth(mark.name(), nameMax);
            if (!name.equals(mark.name())) {
                name = font.plainSubstrByWidth(mark.name(), nameMax - font.width("…")) + "…";
            }
            g.drawString(font, name, rowsX() + 23, ry + 5, textColor, false);
            g.drawString(font, distance, distX, ry + 5, ParchmentUi.TEXT_DIM, false);
            boolean eyeHover = i == hoveredIndex && mouseX >= eyeX - 2;
            ParchmentUi.eye(g, eyeX, ry + 7, !hidden, eyeHover ? ParchmentUi.TEXT : ParchmentUi.TEXT_DIM);
        }
        g.disableScissor();
        if (scrollable) {
            int trackX = rowsX() + rowsW() - SCROLLBAR;
            int thumbH = Math.max(10, (int) (rowsH() * (double) rowsH() / (entries.size() * ROW)));
            int thumbY = rowsY() + (int) ((rowsH() - thumbH) * (scroll / maxScroll()));
            g.fill(trackX, rowsY(), trackX + SCROLLBAR, rowsY() + rowsH(), ParchmentUi.PAPER_DARK);
            g.fill(trackX, thumbY, trackX + SCROLLBAR, thumbY + thumbH, ParchmentUi.LINE);
        }
    }

    static double distanceSq(Mark m, Vec3 p) {
        double dx = m.pos().getX() + 0.5 - p.x;
        double dz = m.pos().getZ() + 0.5 - p.z;
        return dx * dx + dz * dz;
    }
}
