package threed_map.client.map.screen;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapDecoration;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import threed_map.client.ClientMarkPrefs;
import threed_map.client.ClientMarkStore;
import threed_map.client.ClientPayloadHandlers;
import threed_map.client.ClientRules;
import threed_map.client.KeyBindings;
import threed_map.client.MarkRequests;
import threed_map.client.map.MapClient;
import threed_map.client.map.data.MapDimension;
import threed_map.client.map.data.MapRegion;
import threed_map.client.map.data.WorldId;
import threed_map.client.map.render.MapRenderer;
import threed_map.client.map.three.Map3DRenderer;
import threed_map.client.map.three.OrbitCamera;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkColors;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Cartographer's map: the explored world fills the screen, parchment panels float over it.
 * Left: tool bar and the list of marks with search; right: the selected mark; top: world and dimension;
 * bottom: cursor position with a scale bar and a compass rose.
 * 2D: drag to pan, wheel to zoom, left click a mark to select it, right click to create or edit.
 * 3D: left drag to rotate, right drag to pan, wheel to zoom.
 */
public class MapScreen extends Screen {
    /** Zoom in real screen pixels per block. */
    private static final double MIN_ZOOM = 1.0 / 8;
    private static final double MAX_ZOOM = 16;
    private static final double ZOOM_STEP = 1.2;
    /** Below this zoom the map uses the 4× downsampled previews. */
    private static final double FULL_DETAIL_ZOOM = 1;
    private static final double ROTATE_SPEED = 0.6;
    private static final double ZOOM_3D_STEP = 1.15;
    private static final double CLICK_SLOP = 3;

    private static final int EDGE = 4;
    private static final int TOOL = 20;
    private static final int TOOL_GAP = 2;
    private static final int DECORATION_SIZE = 8;
    private static final int SKY_TOP = 0xFF78A7FF;
    private static final int SKY_BOTTOM = 0xFFC3D8FF;
    private static final int[] SCALE_STEPS = {1, 2, 5};

    // Remembered between openings.
    private static double zoom = 2;
    private static boolean mode3d;
    private static boolean listOpen = true;
    private static final OrbitCamera CAMERA = new OrbitCamera();

    private double centerX;
    private double centerZ;
    /** Height the 3D camera looks at; follows the terrain under the center smoothly. */
    private float targetY = Float.NaN;
    private UUID selected;
    private String query = "";
    private boolean dragging;
    private boolean panning;
    private double pressX;
    private double pressY;
    private double dragDistance;

    private final MarkListPanel list = new MarkListPanel();
    private final MarkDetailsPanel details = new MarkDetailsPanel();
    private EditBox searchBox;
    private ParchmentButton markHereButton;
    private final List<ParchmentButton> detailButtons = new ArrayList<>();
    private ParchmentButton editButton;
    private ParchmentButton deleteButton;
    private ParchmentButton hideButton;
    private int toolbarBottom;

    /** Projection used in the last frame; mouse handlers use it to find marks under the cursor. */
    private MarkLayer.Projection projection;
    /** Mark under the cursor (on the map or in the list) in the last frame. */
    private Mark hovered;

    public MapScreen() {
        super(Component.translatable("threed_map.screen.map"));
        // Centered once here, not in init(): init() runs again when returning from the mark editor.
        centerOnPlayer();
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        detailButtons.clear();
        int ty = EDGE;
        addTool(ty, "threed_map.tool.list", () -> new ItemStack(Items.WRITABLE_BOOK), this::toggleList)
                .pressedWhen(() -> listOpen);
        ty += TOOL + TOOL_GAP;
        markHereButton = addTool(ty, "threed_map.tool.mark_here", () -> new ItemStack(Items.OAK_SIGN), this::markHere);
        ty += TOOL + TOOL_GAP;
        addTool(ty, "threed_map.tool.center", () -> new ItemStack(Items.COMPASS), this::centerOnPlayer);
        ty += TOOL + TOOL_GAP;
        addRenderableWidget(ParchmentButton.glyph(EDGE, ty, TOOL, Component.translatable("threed_map.tool.zoom_in"),
                "+", () -> zoomBy(1)));
        ty += TOOL + TOOL_GAP;
        addRenderableWidget(ParchmentButton.glyph(EDGE, ty, TOOL, Component.translatable("threed_map.tool.zoom_out"),
                "−", () -> zoomBy(-1)));
        ty += TOOL + TOOL_GAP;
        addTool(ty, "threed_map.tool.view", () -> new ItemStack(mode3d ? Items.FILLED_MAP : Items.GRASS_BLOCK),
                this::toggle3d).pressedWhen(() -> mode3d);
        ty += TOOL + TOOL_GAP;
        addRenderableWidget(ParchmentButton.glyph(EDGE, ty, TOOL, Component.translatable("threed_map.tool.close"),
                "✕", this::onClose));
        toolbarBottom = ty + TOOL;

        int panelTop = EDGE;
        int panelH = height - 2 * EDGE;
        int listX = EDGE + TOOL + 4;
        int listW = Mth.clamp(width / 5, 112, 150);
        list.setBounds(listX, panelTop, listW, panelH);
        int[] sb = list.searchBounds();
        searchBox = new EditBox(font, sb[0], sb[1], sb[2], sb[3], Component.translatable("threed_map.list.search"));
        searchBox.setBordered(false);
        searchBox.setTextColor(ParchmentUi.TEXT);
        searchBox.setHint(Component.translatable("threed_map.list.search").withColor(ParchmentUi.TEXT_DISABLED & 0xFFFFFF));
        searchBox.setMaxLength(32);
        searchBox.setValue(query);
        searchBox.setResponder(v -> query = v);
        addRenderableWidget(searchBox);

        int detailsW = Mth.clamp(width / 5, 120, 160);
        details.setBounds(width - EDGE - detailsW, panelTop, detailsW, panelH);
        int bx = details.x() + 6;
        int bw = (detailsW - 12 - 3) / 2;
        int by = details.buttonsTop();
        int bh = MarkDetailsPanel.BUTTON_H;
        editButton = detailButton(ParchmentButton.text(bx, by, bw, bh,
                Component.translatable("threed_map.details.edit"), "✎", () -> withSelected(this::openEditor)));
        deleteButton = detailButton(ParchmentButton.text(bx + bw + 3, by, bw, bh,
                Component.translatable("threed_map.details.delete"), "✖", () -> withSelected(this::confirmDelete)));
        hideButton = detailButton(ParchmentButton.text(bx, by + bh + 3, bw, bh,
                Component.translatable("threed_map.details.hide"), null, () -> withSelected(m -> {
                    ClientMarkPrefs.toggleHidden(m.id());
                    updateButtons();
                })));
        detailButton(ParchmentButton.text(bx + bw + 3, by + bh + 3, bw, bh,
                Component.translatable("threed_map.details.show_on_map"), "◎", () -> withSelected(this::centerOn)));
        detailButton(ParchmentButton.glyph(details.x() + detailsW - 18, details.y() + 6, 12,
                Component.translatable("threed_map.details.close"), "✕", () -> selected = null));
        updateButtons();
    }

    private ParchmentButton addTool(int y, String key, java.util.function.Supplier<ItemStack> icon, Runnable action) {
        return addRenderableWidget(ParchmentButton.tool(EDGE, y, TOOL, Component.translatable(key), icon, action));
    }

    private ParchmentButton detailButton(ParchmentButton b) {
        detailButtons.add(b);
        return addRenderableWidget(b);
    }

    private void updateButtons() {
        markHereButton.active = ClientMarkStore.serverSupportsMarks() && !ClientRules.atMarkLimit();
        searchBox.visible = listOpen;
        Mark mark = selectedMark();
        detailButtons.forEach(b -> b.visible = mark != null);
        if (mark != null) {
            boolean editable = ClientMarkStore.serverSupportsMarks() && ClientMarkStore.canEdit(mark);
            editButton.active = editable;
            deleteButton.active = editable;
            hideButton.setMessage(Component.translatable(ClientMarkPrefs.isHidden(mark.id())
                    ? "threed_map.details.show" : "threed_map.details.hide"));
        }
    }

    // ---------------------------------------------------------------- actions

    private void toggleList() {
        listOpen = !listOpen;
        updateButtons();
    }

    private void toggle3d() {
        mode3d = !mode3d;
        targetY = Float.NaN;
    }

    private void zoomBy(int steps) {
        if (mode3d) {
            CAMERA.zoom(Math.pow(ZOOM_3D_STEP, -steps));
        } else {
            zoom = Math.clamp(zoom * Math.pow(ZOOM_STEP, steps), MIN_ZOOM, MAX_ZOOM);
        }
    }

    private void centerOnPlayer() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            centerX = player.getX();
            centerZ = player.getZ();
        }
    }

    private void centerOn(Mark mark) {
        centerX = mark.pos().getX() + 0.5;
        centerZ = mark.pos().getZ() + 0.5;
    }

    private void select(Mark mark) {
        selected = mark.id();
        updateButtons();
    }

    private Mark selectedMark() {
        if (selected == null) {
            return null;
        }
        return ClientMarkStore.all().stream().filter(m -> m.id().equals(selected)).findFirst().orElse(null);
    }

    private void withSelected(java.util.function.Consumer<Mark> action) {
        Mark mark = selectedMark();
        if (mark != null) {
            action.accept(mark);
        }
    }

    private void markHere() {
        openCreator(minecraft.player.blockPosition());
    }

    private void openCreator(BlockPos pos) {
        MapDimension dim = MapClient.current();
        if (dim == null) {
            return;
        }
        if (!ClientMarkStore.serverSupportsMarks()) {
            ClientPayloadHandlers.showError(Component.translatable("threed_map.error.no_server_mod"));
            return;
        }
        if (ClientRules.atMarkLimit()) {
            ClientPayloadHandlers.showError(Component.translatable("threed_map.error.limit", ClientRules.maxMarksPerPlayer()));
            return;
        }
        minecraft.setScreen(MarkEditScreen.create(this, dim.dimension, pos, MarkColors.random(minecraft.player.getRandom())));
    }

    private void openEditor(Mark mark) {
        if (!ClientMarkStore.serverSupportsMarks()) {
            ClientPayloadHandlers.showError(Component.translatable("threed_map.error.no_server_mod"));
        } else if (ClientMarkStore.canEdit(mark)) {
            selected = mark.id();
            minecraft.setScreen(MarkEditScreen.edit(this, mark));
        } else {
            ClientPayloadHandlers.showError(Component.translatable("threed_map.error.not_owner"));
        }
    }

    private void confirmDelete(Mark mark) {
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                MarkRequests.delete(mark);
                selected = null;
            }
            minecraft.setScreen(this);
        }, Component.translatable("threed_map.edit.delete_title"),
                Component.translatable("threed_map.edit.delete_question", mark.name())));
    }

    // ---------------------------------------------------------------- read-only accessors for the client test

    public double currentScale() {
        return scale();
    }

    public double centerX() {
        return centerX;
    }

    public double centerZ() {
        return centerZ;
    }

    public boolean is3d() {
        return mode3d;
    }

    public boolean isListOpen() {
        return listOpen;
    }

    public UUID selectedId() {
        return selected;
    }

    /** Rows in the list after the search filter (as of the last frame). */
    public int listedMarks() {
        return list.size();
    }

    /** {x, y, width, height} of the map view in GUI pixels (the whole screen). */
    public int[] mapArea() {
        return new int[]{0, 0, width, height};
    }

    /** {x, y, width, height} of the list rows in GUI pixels; rows are MarkListPanel.ROW high. */
    public int[] listArea() {
        return new int[]{list.rowsX(), list.rowsY(), list.rowsW(), list.rowsH()};
    }

    // ---------------------------------------------------------------- rendering

    /** GUI pixels per block. */
    private double scale() {
        return zoom / minecraft.getWindow().getGuiScale();
    }

    private MarkLayer.View view() {
        return new MarkLayer.View(centerX, centerZ, scale(), 0, 0, width, height);
    }

    /** Projection of the 3D view onto the screen. */
    private record Projection3D(Map3DRenderer renderer, int x0, int y0, int width, int height)
            implements MarkLayer.Projection {
        @Override
        public double[] project(double x, double y, double z) {
            return renderer.project(x, y, z, x0, y0, width, height);
        }
    }

    private boolean overPanel(double mx, double my) {
        boolean overToolbar = mx < EDGE + TOOL + 2 && my < toolbarBottom + 2;
        return overToolbar || (listOpen && list.contains(mx, my)) || (selectedMark() != null && details.contains(mx, my));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        MapDimension dim = MapClient.current();
        ParchmentUi.paper(g, 0, 0, width, height);
        if (dim == null) {
            return;
        }
        if (mode3d) {
            projection = render3d(g, dim);
        } else {
            MarkLayer.View view = view();
            MapRenderer.draw(g, dim, MapClient.textures(), centerX, centerZ, view.scale(), zoom >= FULL_DETAIL_ZOOM,
                    0, 0, width, height);
            projection = view;
        }
        list.refresh(dim.dimension, query);
        Mark selectedMark = selectedMark();
        if (selected != null && selectedMark == null) {
            selected = null; // deleted or no longer visible
        }
        updateButtons();

        boolean overUi = overPanel(mouseX, mouseY);
        Mark onMap = overUi ? null : MarkLayer.markAt(dim.dimension, projection, mouseX, mouseY);
        Mark inList = listOpen ? list.markAt(mouseX, mouseY) : null;
        hovered = onMap != null ? onMap : inList;

        // The map layer goes below z = 0: item icons render 150 above their pose, and the panels and
        // widgets drawn afterwards at z = 0 must cover them.
        g.pose().pushPose();
        g.pose().translate(0, 0, -400);
        drawPlayer(g, projection, partialTick);
        MarkLayer.draw(g, font, dim.dimension, projection, mode3d || MarkLayer.labelsAtZoom(zoom),
                hovered == null ? null : hovered.id(), selected);
        g.pose().popPose();

        // Map furniture first, so the side panels cover it if the window is narrow.
        drawTitle(g, dim);
        drawFooter(g, dim, mouseX, mouseY, overUi);
        drawCompass(g);
        if (listOpen) {
            list.render(g, font, mouseX, mouseY, selected);
        }
        if (selectedMark != null) {
            details.render(g, font, selectedMark);
        }
    }

    private MarkLayer.Projection render3d(GuiGraphics g, MapDimension dim) {
        g.fillGradient(0, 0, width, height, SKY_TOP, SKY_BOTTOM);
        int ground = dim.heightAt((int) Math.floor(centerX), (int) Math.floor(centerZ));
        float wanted = ground != MapRegion.UNKNOWN_HEIGHT ? ground + 1
                : !Float.isNaN(targetY) ? targetY : (float) minecraft.player.getY();
        targetY = Float.isNaN(targetY) ? wanted : targetY + (wanted - targetY) * 0.15f;

        Map3DRenderer renderer = MapClient.renderer3d();
        renderer.setupFrame(dim, CAMERA, centerX, centerZ, targetY, width, height);
        renderer.render(g, CAMERA, 0, 0, width, height);
        if (!renderer.hasMesh()) {
            g.drawCenteredString(font, Component.translatable("threed_map.screen.building_3d"),
                    width / 2, height / 2 - 4, 0xFFFFFFFF);
        }
        return new Projection3D(renderer, 0, 0, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (hovered != null) {
            g.renderTooltip(font, MarkLayer.tooltip(font, hovered), mouseX, mouseY);
        }
    }

    /** Free map area between the panels, for the title, footer and compass. */
    private int freeLeft() {
        return listOpen ? list.rowsX() + list.rowsW() + 10 : EDGE + TOOL + 6;
    }

    private int freeRight() {
        return selectedMark() != null ? details.x() - 4 : width - EDGE;
    }

    private void drawTitle(GuiGraphics g, MapDimension dim) {
        String world = WorldId.displayName(minecraft);
        String text = (world.isEmpty() ? "" : world + "  ·  ") + MarkDetailsPanel.dimensionName(dim.dimension).getString();
        int free = freeRight() - freeLeft();
        if (font.width(text) + 20 > free) {
            text = font.plainSubstrByWidth(text, Math.max(0, free - 20 - font.width("…"))) + "…";
        }
        int w = Math.min(font.width(text) + 20, free);
        int cx = (freeLeft() + freeRight()) / 2;
        ParchmentUi.plaque(g, cx - w / 2, EDGE, w, 15);
        g.drawString(font, text, cx - font.width(text) / 2, EDGE + 4, ParchmentUi.TEXT, false);
    }

    private void drawFooter(GuiGraphics g, MapDimension dim, int mouseX, int mouseY, boolean overUi) {
        int x = freeLeft();
        int y = height - EDGE - 15;
        if (mode3d) {
            Component hint = Component.translatable("threed_map.screen.hint_3d");
            ParchmentUi.plaque(g, x, y, font.width(hint) + 16, 15);
            g.drawString(font, hint, x + 8, y + 4, ParchmentUi.TEXT, false);
            return;
        }
        MarkLayer.View view = view();
        String coords;
        if (!overUi) {
            int bx = (int) Math.floor(view.blockX(mouseX));
            int bz = (int) Math.floor(view.blockZ(mouseY));
            coords = "X %d  Z %d".formatted(bx, bz);
        } else {
            coords = "X %d  Z %d".formatted((int) Math.floor(centerX), (int) Math.floor(centerZ));
        }
        // Scale bar: a round number of blocks (1, 2, 5 × 10^k) about 60 GUI px long.
        double scale = view.scale();
        int blocks = 1;
        outer:
        for (int pow = 1; pow <= 100_000; pow *= 10) {
            for (int s : SCALE_STEPS) {
                if (s * pow * scale >= 50) {
                    blocks = s * pow;
                    break outer;
                }
            }
        }
        int barW = (int) Math.round(blocks * scale);
        String barText = Component.translatable("threed_map.list.distance", blocks).getString();
        int coordsW = Math.max(font.width(coords), font.width("X -00000  Z -00000"));
        int w = 8 + coordsW + 8 + barW + 5 + font.width(barText) + 8;
        // Space left of the compass; without room the scale bar goes first.
        int free = freeRight() - 52 - x;
        boolean withBar = w <= free;
        if (!withBar) {
            w = Math.min(8 + coordsW + 8, Math.max(0, free));
        }
        if (w < 40) {
            return;
        }
        ParchmentUi.plaque(g, x, y, w, 15);
        g.drawString(font, coords, x + 8, y + 4, ParchmentUi.TEXT, false);
        if (!withBar) {
            return;
        }
        int barX = x + 8 + coordsW + 8;
        g.fill(barX, y + 7, barX + barW, y + 8, ParchmentUi.TEXT);
        g.fill(barX, y + 4, barX + 1, y + 11, ParchmentUi.TEXT);
        g.fill(barX + barW - 1, y + 4, barX + barW, y + 11, ParchmentUi.TEXT);
        g.drawString(font, barText, barX + barW + 5, y + 4, ParchmentUi.TEXT, false);
    }

    /** Compass rose in the bottom right of the free area; turns with the 3D camera. */
    private void drawCompass(GuiGraphics g) {
        float cx = freeRight() - 24;
        float cy = height - EDGE - 24;
        float angle = mode3d ? -CAMERA.yaw() : 0;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(angle));
        needle(g, 0, 0xFF2B1D12, 0xFF8A6A42, 6);   // east-west, short
        needle(g, 90, 0xFF2B1D12, 0xFF8A6A42, 6);
        needle(g, 45, 0xFF9C8360, 0xFF9C8360, 4);  // diagonals, faint
        needle(g, 135, 0xFF9C8360, 0xFF9C8360, 4);
        needle(g, 0, 0xFFB02020, 0xFFE8DCC0, 14);  // north red, south pale
        g.fill(-2, -2, 2, 2, 0xFF2B1D12);
        pose.translate(0, -21, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(-angle)); // keep the letter upright
        String n = Component.translatable("threed_map.compass.north").getString();
        g.drawString(font, n, -font.width(n) / 2, -4, 0xFFB02020, false);
        pose.popPose();
    }

    /** Two thin diamonds along an axis: the first color points "up" (north at 0°), the second down. */
    private static void needle(GuiGraphics g, float degrees, int up, int down, float length) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.mulPose(Axis.ZP.rotationDegrees(degrees));
        diamond(g, 0, -length / 2, length / 2, up);
        diamond(g, 0, length / 2, length / 2, down);
        pose.popPose();
    }

    private static void diamond(GuiGraphics g, float x, float y, float halfLength, int color) {
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(0.28f, 1, 1);
        pose.mulPose(Axis.ZP.rotationDegrees(45));
        float s = halfLength * 0.7071f;
        pose.scale(s, s, 1);
        g.fill(-1, -1, 1, 1, color);
        pose.popPose();
    }

    /** Vanilla map arrow; clamped to the edge as the small off-map dot when the player is outside the view. */
    private void drawPlayer(GuiGraphics g, MarkLayer.Projection view, float partialTick) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        Vec3 pos = player.getPosition(partialTick);
        double[] p = view.project(pos.x, pos.y, pos.z);
        if (p == null) {
            return;
        }
        boolean inside = view.contains(p[0], p[1]);
        Holder<MapDecorationType> type = inside ? MapDecorationTypes.PLAYER : MapDecorationTypes.PLAYER_OFF_MAP;
        TextureAtlasSprite sprite = minecraft.getMapDecorationTextures()
                .get(new MapDecoration(type, (byte) 0, (byte) 0, (byte) 0, Optional.empty()));
        float half = DECORATION_SIZE / 2f;
        float x = (float) Mth.clamp(p[0], half, width - half);
        float y = (float) Mth.clamp(p[1], half, height - half);
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 100);
        if (inside) {
            // Yaw 0 faces south (+Z, down on screen); the sprite arrow points up, hence +180.
            // In 3D the camera yaw turns the whole map, so the arrow turns back by it.
            float cameraYaw = mode3d ? CAMERA.yaw() : 0;
            pose.mulPose(Axis.ZP.rotationDegrees(player.getViewYRot(partialTick) + 180 - cameraYaw));
        }
        pose.translate(-half, -half, 0);
        g.blit(0, 0, 0, DECORATION_SIZE, DECORATION_SIZE, sprite);
        pose.popPose();
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        setFocused(null); // a click outside the search box ends typing
        MapDimension dim = MapClient.current();
        if (dim == null) {
            return false;
        }
        if (listOpen && list.contains(mouseX, mouseY)) {
            Mark mark = list.markAt(mouseX, mouseY);
            if (mark != null) {
                if (list.onEye(mouseX, mouseY)) {
                    ClientMarkPrefs.toggleHidden(mark.id());
                } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    openEditor(mark);
                } else {
                    select(mark);
                    centerOn(mark);
                }
            }
            return true;
        }
        if (overPanel(mouseX, mouseY)) {
            return true;
        }
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = true;
            pressX = mouseX;
            pressY = mouseY;
            dragDistance = 0;
            return true;
        }
        if (button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            return false;
        }
        Mark mark = projection == null ? null : MarkLayer.markAt(dim.dimension, projection, mouseX, mouseY);
        if (mark != null) {
            openEditor(mark);
            return true;
        }
        if (mode3d) {
            panning = true; // creating marks needs a block under the cursor: done in 2D
            return true;
        }
        MarkLayer.View view = view();
        int bx = (int) Math.floor(view.blockX(mouseX));
        int bz = (int) Math.floor(view.blockZ(mouseY));
        int surface = dim.heightAt(bx, bz);
        int by = surface == MapRegion.UNKNOWN_HEIGHT ? minecraft.player.getBlockY() : surface + 1;
        openCreator(new BlockPos(bx, by, bz));
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && dragging) {
            dragging = false;
            // A press without movement is a click: select the mark under the cursor.
            MapDimension dim = MapClient.current();
            if (dragDistance < CLICK_SLOP && dim != null && projection != null) {
                Mark mark = MarkLayer.markAt(dim.dimension, projection, pressX, pressY);
                if (mark != null) {
                    select(mark);
                }
            }
        } else if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            panning = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragDistance += Math.abs(dragX) + Math.abs(dragY);
            if (mode3d) {
                CAMERA.rotate(dragX * ROTATE_SPEED, dragY * ROTATE_SPEED);
            } else {
                double scale = scale();
                centerX -= dragX / scale;
                centerZ -= dragY / scale;
            }
            return true;
        }
        if (panning && button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // Move the ground under the cursor along with it, roughly: speed grows with distance.
            double k = CAMERA.distance() / height * 1.2;
            double[] f = CAMERA.forward();
            double[] r = CAMERA.right();
            centerX += (f[0] * dragY - r[0] * dragX) * k;
            centerZ += (f[1] * dragY - r[1] * dragX) * k;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        if (listOpen && list.contains(mouseX, mouseY)) {
            list.scrollBy(scrollY);
            return true;
        }
        if (overPanel(mouseX, mouseY)) {
            return false;
        }
        if (mode3d) {
            CAMERA.zoom(Math.pow(ZOOM_3D_STEP, -scrollY));
            return true;
        }
        // Keep the block under the cursor in place.
        MarkLayer.View before = view();
        double wx = before.blockX(mouseX);
        double wz = before.blockZ(mouseY);
        zoom = Math.clamp(zoom * Math.pow(ZOOM_STEP, scrollY), MIN_ZOOM, MAX_ZOOM);
        double scale = scale();
        centerX = wx - (mouseX - width / 2.0) / scale;
        centerZ = wz - (mouseY - height / 2.0) / scale;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // While typing in the search box, letters belong to it; only Escape leaves the field.
        if (searchBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_ENTER) {
                setFocused(null);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        if (KeyBindings.OPEN_MAP.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> centerOnPlayer();
            case GLFW.GLFW_KEY_L -> toggleList();
            case GLFW.GLFW_KEY_N -> markHere();
            case GLFW.GLFW_KEY_V -> toggle3d();
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoomBy(1);
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoomBy(-1);
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
