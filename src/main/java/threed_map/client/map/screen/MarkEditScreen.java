package threed_map.client.map.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import threed_map.client.ClientRules;
import threed_map.client.MarkRequests;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkColors;
import threed_map.common.mark.MarkIcon;
import threed_map.common.mark.Visibility;
import threed_map.common.net.CreateMarkPayload;
import threed_map.common.net.EditMarkPayload;

import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Create a mark (existing == null) or edit/delete one, on a parchment panel over the dimmed map:
 * name and description on the left, color, icon, position and visibility on the right.
 */
public class MarkEditScreen extends Screen {
    private static final int PAD = 10;
    private static final int GAP = 10;
    private static final int MAX_COLUMN = 150;
    private static final int TITLE_H = 14;
    private static final int LABEL_H = 10;
    private static final int CONTENT_H = 126;
    private static final int SWATCH_ROWS = 2;
    private static final int BUTTON_H = 18;

    private final Screen parent;
    private final Mark existing;
    private final ResourceKey<Level> dimension;

    // State survives re-init (window resize).
    private String name;
    private String description;
    private int color;
    private MarkIcon icon;
    private Visibility visibility;
    private String x;
    private String y;
    private String z;

    private ParchmentButton saveButton;
    private ParchmentButton visibilityToggle;
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int column;

    private MarkEditScreen(Screen parent, Mark existing, ResourceKey<Level> dimension, BlockPos pos, String name,
                           String description, int color, MarkIcon icon, Visibility visibility) {
        super(Component.translatable(existing == null ? "threed_map.edit.title_new" : "threed_map.edit.title_edit"));
        this.parent = parent;
        this.existing = existing;
        this.dimension = dimension;
        this.name = name;
        this.description = description;
        this.color = color;
        this.icon = icon;
        this.visibility = visibility;
        this.x = Integer.toString(pos.getX());
        this.y = Integer.toString(pos.getY());
        this.z = Integer.toString(pos.getZ());
    }

    public static MarkEditScreen create(Screen parent, ResourceKey<Level> dimension, BlockPos pos, int color) {
        return new MarkEditScreen(parent, null, dimension, pos, "", "", color, MarkIcon.PIN, Visibility.PRIVATE);
    }

    public static MarkEditScreen edit(Screen parent, Mark mark) {
        return new MarkEditScreen(parent, mark, mark.dimension(), mark.pos(), mark.name(), mark.description(),
                mark.color(), mark.icon(), mark.visibility());
    }

    @Override
    protected void init() {
        column = Math.min(MAX_COLUMN, (width - 2 * PAD - GAP - 16) / 2);
        panelW = 2 * PAD + 2 * column + GAP;
        panelH = PAD + TITLE_H + CONTENT_H + 4 + LABEL_H + 2 + BUTTON_H + PAD;
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        int left = panelX + PAD;
        int right = left + column + GAP;
        int top = panelY + PAD + TITLE_H;

        // Left column: name and description.
        EditBox nameBox = new EditBox(font, left, top + LABEL_H, column, 18, Component.translatable("threed_map.edit.name"));
        // Not below the current length: an old name longer than a lowered limit must not be cut silently.
        nameBox.setMaxLength(Math.max(ClientRules.maxNameLength(), name.length()));
        nameBox.setValue(name);
        nameBox.setHint(Component.translatable("threed_map.edit.name_hint"));
        nameBox.setResponder(v -> {
            name = v;
            updateSave();
        });
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        int descTop = top + LABEL_H + 18 + 6 + LABEL_H;
        MultiLineEditBox descBox = new MultiLineEditBox(font, left, descTop, column, top + CONTENT_H - descTop,
                Component.translatable("threed_map.edit.description_hint"), Component.translatable("threed_map.edit.description"));
        descBox.setCharacterLimit(Math.max(ClientRules.maxDescriptionLength(), description.length()));
        descBox.setValue(description);
        descBox.setValueListener(v -> {
            description = v;
            updateSave();
        });
        if (ClientRules.maxDescriptionLength() == 0 && description.isEmpty()) {
            descBox.active = false;
            descBox.setTooltip(Tooltip.create(Component.translatable("threed_map.edit.description_disabled")));
        }
        addRenderableWidget(descBox);

        // Right column: color, icon, coordinates, visibility.
        int perRow = MarkColors.PALETTE.length / SWATCH_ROWS;
        int swatch = column / perRow;
        for (int i = 0; i < MarkColors.PALETTE.length; i++) {
            int c = MarkColors.PALETTE[i];
            addRenderableWidget(new Picker(right + (i % perRow) * swatch + 1, top + LABEL_H + 1 + (i / perRow) * 13,
                    swatch - 2, 11, () -> color == c, () -> color = c,
                    (g, px, py, w, h) -> g.fill(px + 1, py + 1, px + w - 1, py + h - 1, 0xFF000000 | c)));
        }

        MarkIcon[] icons = MarkIcon.values();
        int iconW = column / icons.length;
        int iconTop = top + LABEL_H + 26 + 4 + LABEL_H;
        for (int i = 0; i < icons.length; i++) {
            MarkIcon ic = icons[i];
            Picker picker = new Picker(right + i * iconW + 1, iconTop, iconW - 2, 18, () -> icon == ic,
                    () -> icon = ic, (g, px, py, w, h) -> MarkIcons.draw(g, ic, px + w / 2f, py + h / 2f, 0.9f));
            picker.setTooltip(Tooltip.create(MarkIcons.typeName(ic)));
            addRenderableWidget(picker);
        }

        int coordTop = iconTop + 18 + 4 + LABEL_H;
        int coordW = (column - 8) / 3;
        coordBox(right, coordTop, coordW, x, v -> x = v);
        coordBox(right + coordW + 4, coordTop, coordW, y, v -> y = v);
        coordBox(right + 2 * (coordW + 4), coordTop, coordW, z, v -> z = v);

        // A mark that is already public may stay public; making one public needs the permission.
        boolean alreadyPublic = existing != null && existing.isPublic();
        boolean mayPublish = ClientRules.canCreatePublic() || alreadyPublic;
        if (!mayPublish) {
            visibility = Visibility.PRIVATE;
        }
        visibilityToggle = ParchmentButton.text(right, coordTop + 26, column, BUTTON_H, visibilityText(), null,
                this::cycleVisibility);
        if (!mayPublish) {
            visibilityToggle.active = false;
            visibilityToggle.setTooltip(Tooltip.create(Component.translatable("threed_map.edit.public_op_only")));
        }
        addRenderableWidget(visibilityToggle);

        // Bottom row: buttons across both columns.
        int buttonsY = panelY + panelH - PAD - BUTTON_H;
        int full = 2 * column + GAP;
        if (existing == null) {
            int half = (full - 4) / 2;
            saveButton = addRenderableWidget(ParchmentButton.text(left, buttonsY, half, BUTTON_H,
                    Component.translatable("threed_map.edit.create"), "✔", this::save));
            addRenderableWidget(ParchmentButton.text(left + half + 4, buttonsY, half, BUTTON_H,
                    Component.translatable("gui.cancel"), null, this::onClose));
        } else {
            int third = (full - 8) / 3;
            saveButton = addRenderableWidget(ParchmentButton.text(left, buttonsY, third, BUTTON_H,
                    Component.translatable("threed_map.edit.save"), "✔", this::save));
            addRenderableWidget(ParchmentButton.text(left + third + 4, buttonsY, third, BUTTON_H,
                    Component.translatable("threed_map.edit.delete"), "✖", this::confirmDelete));
            addRenderableWidget(ParchmentButton.text(left + 2 * (third + 4), buttonsY, third, BUTTON_H,
                    Component.translatable("gui.cancel"), null, this::onClose));
        }
        updateSave();
    }

    private Component visibilityText() {
        return Component.translatable(visibility == Visibility.PUBLIC
                ? "threed_map.visibility.public_long" : "threed_map.visibility.private_long");
    }

    private void cycleVisibility() {
        visibility = visibility == Visibility.PUBLIC ? Visibility.PRIVATE : Visibility.PUBLIC;
        visibilityToggle.setMessage(visibilityText());
    }

    private void coordBox(int bx, int by, int w, String value, Consumer<String> sink) {
        EditBox box = new EditBox(font, bx, by, w, 18, Component.empty());
        box.setMaxLength(9);
        box.setFilter(s -> s.matches("-?\\d{0,8}"));
        box.setValue(value);
        box.setResponder(v -> {
            sink.accept(v);
            updateSave();
        });
        addRenderableWidget(box);
    }

    private void updateSave() {
        if (saveButton != null) {
            saveButton.active = !name.isBlank() && name.strip().length() <= ClientRules.maxNameLength()
                    && description.strip().length() <= ClientRules.maxDescriptionLength() && parsePos() != null;
        }
    }

    private BlockPos parsePos() {
        try {
            return new BlockPos(Integer.parseInt(x), Integer.parseInt(y), Integer.parseInt(z));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void save() {
        BlockPos pos = parsePos();
        if (name.isBlank() || pos == null) {
            return;
        }
        MarkRequests.send(existing == null
                ? new CreateMarkPayload(name, description, color, icon, pos, dimension, visibility)
                : new EditMarkPayload(existing.id(), name, description, color, icon, pos, visibility));
        onClose();
    }

    private void confirmDelete() {
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                MarkRequests.delete(existing);
                minecraft.setScreen(parent);
            } else {
                minecraft.setScreen(this);
            }
        }, Component.translatable("threed_map.edit.delete_title"),
                Component.translatable("threed_map.edit.delete_question", existing.name())));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // The map stays visible behind the editor, dimmed.
        if (parent != null) {
            parent.renderBackground(g, -1, -1, partialTick);
        }
        g.fill(0, 0, width, height, 0x90000000);
        ParchmentUi.panel(g, panelX, panelY, panelW, panelH);
        int left = panelX + PAD;
        int right = left + column + GAP;
        int top = panelY + PAD + TITLE_H;
        ParchmentUi.heading(g, font, title.getString(), panelX, panelY + PAD - 2, panelW);

        label(g, "threed_map.edit.name", left, top);
        label(g, "threed_map.edit.description", left, top + LABEL_H + 24);
        label(g, "threed_map.edit.color", right, top);
        g.drawString(font, Component.translatable("threed_map.edit.icon").append(": ").append(MarkIcons.typeName(icon)),
                right, top + LABEL_H + 30, ParchmentUi.TEXT, false);
        g.drawString(font, "X / Y / Z", right, top + LABEL_H + 30 + LABEL_H + 22, ParchmentUi.TEXT, false);

        Component info = existing == null
                ? MarkDetailsPanel.dimensionName(dimension)
                : Component.translatable("threed_map.edit.info", existing.ownerName(),
                MarkLayer.DATE.format(Instant.ofEpochMilli(existing.createdAt())));
        g.drawString(font, info, left, top + CONTENT_H + 4, ParchmentUi.TEXT_DIM, false);
    }

    private void label(GuiGraphics g, String key, int lx, int ly) {
        g.drawString(font, Component.translatable(key), lx, ly, ParchmentUi.TEXT, false);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @FunctionalInterface
    private interface Painter {
        void paint(GuiGraphics g, int x, int y, int w, int h);
    }

    /** Small selectable tile (color swatch or icon) in a parchment field; the selected one gets a dark frame. */
    static final class Picker extends AbstractButton {
        private final BooleanSupplier selected;
        private final Runnable onSelect;
        private final Painter painter;

        Picker(int x, int y, int w, int h, BooleanSupplier selected, Runnable onSelect, Painter painter) {
            super(x, y, w, h, Component.empty());
            this.selected = selected;
            this.onSelect = onSelect;
            this.painter = painter;
        }

        @Override
        public void onPress() {
            onSelect.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean on = selected.getAsBoolean();
            g.fill(getX() - 1, getY() - 1, getX() + width + 1, getY() + height + 1,
                    on ? 0xFF2B1D12 : ParchmentUi.LINE);
            g.fill(getX(), getY(), getX() + width, getY() + height,
                    on ? ParchmentUi.ROW_SELECTED : isHovered() ? ParchmentUi.ROW_HOVER : ParchmentUi.PAPER_DARK);
            painter.paint(g, getX(), getY(), width, height);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
