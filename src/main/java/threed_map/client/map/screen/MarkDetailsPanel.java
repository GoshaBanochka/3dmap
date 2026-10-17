package threed_map.client.map.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.Level;
import threed_map.common.mark.Mark;

import java.time.Instant;
import java.util.List;

/**
 * Right panel with the selected mark: big icon, name and type, coordinates, dimension and distance,
 * description, author. The action buttons are widgets of the screen, placed via buttonsTop().
 */
final class MarkDetailsPanel {
    static final int BUTTON_H = 16;
    private static final int ICON_BOX = 30;
    /** Author, date and visibility lines above the buttons. */
    private static final int META_H = 34;

    private int x;
    private int y;
    private int w;
    private int h;

    void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    boolean contains(double mx, double my) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    int x() {
        return x;
    }

    int y() {
        return y;
    }

    int width() {
        return w;
    }

    /** Top of the two button rows at the bottom of the panel. */
    int buttonsTop() {
        return y + h - 6 - 2 * BUTTON_H - 3;
    }

    static Component dimensionName(ResourceKey<Level> dimension) {
        if (dimension.equals(Level.OVERWORLD) || dimension.equals(Level.NETHER) || dimension.equals(Level.END)) {
            return Component.translatable("threed_map.dim." + dimension.location().getPath());
        }
        return Component.literal(dimension.location().getPath());
    }

    void render(GuiGraphics g, Font font, Mark mark) {
        ParchmentUi.panel(g, x, y, w, h);
        int left = x + 8;
        int top = y + 8;
        int inner = w - 16;

        ParchmentUi.field(g, left, top, ICON_BOX, ICON_BOX);
        MarkIcons.draw(g, mark.icon(), left + ICON_BOX / 2f, top + ICON_BOX / 2f, 1.5f);
        g.fill(left + 1, top + ICON_BOX - 3, left + ICON_BOX - 1, top + ICON_BOX - 1, 0xFF000000 | mark.color());

        int textX = left + ICON_BOX + 5;
        int textW = x + w - 18 - textX; // leave room for the close button
        List<FormattedCharSequence> nameLines = font.split(Component.literal(mark.name()), textW);
        int ty = top + 2;
        for (int i = 0; i < Math.min(2, nameLines.size()); i++) {
            g.drawString(font, nameLines.get(i), textX, ty, ParchmentUi.TEXT, false);
            ty += 10;
        }
        g.drawString(font, MarkIcons.typeName(mark.icon()), textX, Math.max(ty, top + 13), ParchmentUi.TEXT_DIM, false);

        int cy = top + ICON_BOX + 6;
        g.drawString(font, "X %d   Y %d   Z %d".formatted(mark.pos().getX(), mark.pos().getY(), mark.pos().getZ()),
                left, cy, ParchmentUi.TEXT, false);
        cy += 11;
        int distance = (int) Math.sqrt(MarkListPanel.distanceSq(mark, Minecraft.getInstance().player.position()));
        Component where = Component.empty().append(dimensionName(mark.dimension())).append(" · ")
                .append(Component.translatable("threed_map.list.distance", distance));
        g.drawString(font, where, left, cy, ParchmentUi.TEXT_DIM, false);
        cy += 12;
        ParchmentUi.separator(g, left, cy, inner);
        cy += 5;

        g.drawString(font, Component.translatable("threed_map.edit.description"), left, cy, ParchmentUi.TEXT_DIM, false);
        cy += 11;
        int descBottom = buttonsTop() - META_H - 6;
        ParchmentUi.field(g, left, cy, inner, Math.max(14, descBottom - cy));
        int dy = cy + 3;
        if (mark.description().isEmpty()) {
            g.drawString(font, "—", left + 4, dy, ParchmentUi.TEXT_DISABLED, false);
        } else {
            for (String paragraph : mark.description().split("\n", -1)) {
                for (FormattedCharSequence line : font.split(Component.literal(paragraph), inner - 8)) {
                    if (dy + 9 > descBottom) {
                        break;
                    }
                    g.drawString(font, line, left + 4, dy, ParchmentUi.TEXT, false);
                    dy += 10;
                }
            }
        }

        // One line each: in a narrow panel they would overlap side by side.
        int metaY = buttonsTop() - META_H;
        g.drawString(font, Component.translatable("threed_map.mark.author", mark.ownerName()), left, metaY,
                ParchmentUi.TEXT_DIM, false);
        g.drawString(font, MarkLayer.DATE.format(Instant.ofEpochMilli(mark.createdAt())), left, metaY + 10,
                ParchmentUi.TEXT_DISABLED, false);
        g.drawString(font, Component.translatable(mark.isPublic()
                        ? "threed_map.visibility.public_long" : "threed_map.visibility.private_long"),
                left, metaY + 20, mark.isPublic() ? ParchmentUi.TEXT_DIM : ParchmentUi.TEXT_PRIVATE, false);
    }
}
