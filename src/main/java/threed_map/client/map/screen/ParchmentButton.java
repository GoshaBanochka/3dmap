package threed_map.client.map.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * Parchment button: an item icon, a big glyph, or a text label (optionally with an icon on the left).
 * The message is always set (narration, tests); for icon-only buttons it is shown as the tooltip.
 */
public final class ParchmentButton extends AbstractButton {
    private static final int FRAME = 0xFF2B1D12;
    private static final int FACE = 0xFFD9C9A3;
    private static final int FACE_HOVER = 0xFFEADDBE;
    private static final int FACE_PRESSED = 0xFFC9B68A;
    private static final int FACE_DISABLED = 0xFFCDBF9E;

    private final Runnable onPress;
    private final boolean showLabel;
    private Supplier<ItemStack> icon = () -> ItemStack.EMPTY;
    private String glyph;
    private Supplier<Boolean> pressedLook = () -> false;

    private ParchmentButton(int x, int y, int w, int h, Component message, boolean showLabel, Runnable onPress) {
        super(x, y, w, h, message);
        this.showLabel = showLabel;
        this.onPress = onPress;
    }

    /** Square tool button with an item icon; the message becomes the tooltip. */
    static ParchmentButton tool(int x, int y, int size, Component tooltip, Supplier<ItemStack> icon, Runnable onPress) {
        ParchmentButton b = new ParchmentButton(x, y, size, size, tooltip, false, onPress);
        b.icon = icon;
        b.setTooltip(Tooltip.create(tooltip));
        return b;
    }

    /** Square tool button with a font glyph instead of an item. */
    static ParchmentButton glyph(int x, int y, int size, Component tooltip, String glyph, Runnable onPress) {
        ParchmentButton b = new ParchmentButton(x, y, size, size, tooltip, false, onPress);
        b.glyph = glyph;
        b.setTooltip(Tooltip.create(tooltip));
        return b;
    }

    /** Text button, optionally with a small glyph before the label. */
    static ParchmentButton text(int x, int y, int w, int h, Component label, String glyph, Runnable onPress) {
        ParchmentButton b = new ParchmentButton(x, y, w, h, label, true, onPress);
        b.glyph = glyph;
        return b;
    }

    /** Draws the button as held down while the condition holds (toggle buttons). */
    ParchmentButton pressedWhen(Supplier<Boolean> condition) {
        this.pressedLook = condition;
        return this;
    }

    void setIcon(Supplier<ItemStack> icon) {
        this.icon = icon;
    }

    @Override
    public void onPress() {
        onPress.run();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        boolean down = pressedLook.get();
        int face = !active ? FACE_DISABLED : down ? FACE_PRESSED : isHoveredOrFocused() ? FACE_HOVER : FACE;
        g.fill(x, y, x + width, y + height, FRAME);
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, face);
        // Bevel: light top-left, dark bottom-right (inverted when pressed).
        int light = down ? 0x40000000 : 0x60FFFFFF;
        int dark = down ? 0x60FFFFFF : 0x40000000;
        g.fill(x + 1, y + 1, x + width - 1, y + 2, light);
        g.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, dark);

        Font font = Minecraft.getInstance().font;
        int textColor = active ? ParchmentUi.TEXT : ParchmentUi.TEXT_DISABLED;
        ItemStack stack = icon.get();
        if (!showLabel) {
            if (!stack.isEmpty()) {
                g.renderItem(stack, x + (width - 16) / 2, y + (height - 16) / 2);
            } else if (glyph != null) {
                g.drawString(font, glyph, x + (width - font.width(glyph)) / 2 + 1, y + (height - 8) / 2 + 1, textColor, false);
            }
            return;
        }
        String label = getMessage().getString();
        String prefix = glyph == null ? "" : glyph + " ";
        int textW = font.width(prefix + label);
        String shown = textW > width - 6 ? font.plainSubstrByWidth(prefix + label, width - 6) : prefix + label;
        g.drawString(font, shown, x + (width - font.width(shown)) / 2, y + (height - 8) / 2 + 1, textColor, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
