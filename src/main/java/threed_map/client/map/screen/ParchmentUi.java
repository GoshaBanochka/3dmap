package threed_map.client.map.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Cartographer look built from vanilla parts: parchment (the paper of the vanilla map item) in a dark
 * wooden frame with brass corners. Everything is drawn with fills plus the map_background texture.
 */
final class ParchmentUi {
    static final int TEXT = 0xFF3F2E1E;
    static final int TEXT_DIM = 0xFF7A6448;
    static final int TEXT_DISABLED = 0xFFA89778;
    static final int TEXT_PRIVATE = 0xFF8A5A00;
    static final int LINE = 0xFF9C8360;
    static final int PAPER = 0xFFE8DCC0;
    static final int PAPER_DARK = 0xFFDCCDA8;
    static final int ROW_HOVER = 0xFFE2D4B2;
    static final int ROW_SELECTED = 0xFFD3C196;
    private static final int FRAME_DARK = 0xFF2B1D12;
    private static final int FRAME_WOOD = 0xFF8A6A42;
    private static final int BRASS = 0xFFCFAE6B;

    private static final ResourceLocation PAPER_TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/map/map_background.png");

    private ParchmentUi() {
    }

    /** Plain parchment: the inner part of the vanilla map paper, stretched (no dark paper edge). */
    static void paper(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PAPER);
        g.blit(PAPER_TEXTURE, x, y, w, h, 15f, 15f, 70, 70, 100, 100);
    }

    /** Framed parchment panel with brass corner studs. */
    static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, FRAME_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, FRAME_WOOD);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, FRAME_DARK);
        paper(g, x + 4, y + 4, w - 8, h - 8);
        stud(g, x + 1, y + 1);
        stud(g, x + w - 4, y + 1);
        stud(g, x + 1, y + h - 4);
        stud(g, x + w - 4, y + h - 4);
    }

    private static void stud(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 3, y + 3, BRASS);
        g.fill(x + 2, y + 1, x + 3, y + 3, FRAME_DARK);
        g.fill(x + 1, y + 2, x + 3, y + 3, FRAME_DARK);
    }

    /** Small sign with a thin frame and end bars, for the title and the coordinate readout. */
    static void plaque(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, FRAME_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, FRAME_WOOD);
        paper(g, x + 2, y + 2, w - 4, h - 4);
        g.fill(x + 4, y + 3, x + 5, y + h - 3, LINE);
        g.fill(x + w - 5, y + 3, x + w - 4, y + h - 3, LINE);
    }

    /** Sunken parchment field (search box, description). */
    static void field(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, LINE);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PAPER_DARK);
    }

    /** "— Title —" centered in a width. */
    static void heading(GuiGraphics g, net.minecraft.client.gui.Font font, String text, int x, int y, int w) {
        String s = "— " + text + " —";
        g.drawString(font, s, x + (w - font.width(s)) / 2, y, TEXT, false);
    }

    static void separator(GuiGraphics g, int x, int y, int w) {
        g.fill(x, y, x + w, y + 1, LINE);
    }

    /** 7×5 pixel eye; crossed out when closed. */
    static void eye(GuiGraphics g, int x, int y, boolean open, int color) {
        g.fill(x + 2, y, x + 5, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + 5, y + 1, x + 6, y + 2, color);
        g.fill(x, y + 2, x + 1, y + 3, color);
        g.fill(x + 6, y + 2, x + 7, y + 3, color);
        g.fill(x + 1, y + 3, x + 2, y + 4, color);
        g.fill(x + 5, y + 3, x + 6, y + 4, color);
        g.fill(x + 2, y + 4, x + 5, y + 5, color);
        if (open) {
            g.fill(x + 3, y + 2, x + 4, y + 3, color);
        } else {
            for (int i = 0; i < 7; i++) {
                g.fill(x + i, y + 5 - (i * 6 / 7) - 1, x + i + 1, y + 5 - (i * 6 / 7), color);
            }
        }
    }

    /** 7×7 pixel magnifying glass. */
    static void magnifier(GuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 4, y + 1, color);
        g.fill(x, y + 1, x + 1, y + 4, color);
        g.fill(x + 4, y + 1, x + 5, y + 4, color);
        g.fill(x + 1, y + 4, x + 4, y + 5, color);
        g.fill(x + 4, y + 4, x + 5, y + 5, color);
        g.fill(x + 5, y + 5, x + 6, y + 6, color);
        g.fill(x + 6, y + 6, x + 7, y + 7, color);
    }
}
