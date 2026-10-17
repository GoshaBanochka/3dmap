package threed_map.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;
import threed_map.ThreedMapMod;
import threed_map.client.map.MapClient;
import threed_map.client.map.data.ChunkScanner;
import threed_map.client.map.data.MapDimension;
import threed_map.client.map.data.MapRegion;
import threed_map.client.map.data.MapShading;
import threed_map.client.map.screen.MapScreen;
import threed_map.client.map.screen.MarkEditScreen;
import threed_map.client.map.three.Map3DRenderer;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkColors;
import threed_map.common.mark.MarkIcon;
import threed_map.common.mark.Visibility;
import threed_map.common.net.CreateMarkPayload;

import net.minecraft.client.MouseHandler;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Scripted client run for runAutotestClient (system property threed_map.autotest=true).
 * First run: creates a world, explores, takes map screenshots, then drives the mark GUI like a player
 * (simulated clicks and typing). Second run: reopens the world and checks the disk cache and saved marks.
 * Results are logged with an [AUTOTEST] prefix; screenshots go to run-autotest/screenshots.
 */
@EventBusSubscriber(modid = ThreedMapMod.MODID, value = Dist.CLIENT)
public final class AutoTestClient {
    private static final boolean ENABLED = Boolean.getBoolean("threed_map.autotest");
    private static final String WORLD = "autotest";

    private static final TreeMap<Integer, Runnable> STEPS = new TreeMap<>();
    private static boolean started;
    private static int inWorldTicks;
    private static int failures;

    private AutoTestClient() {
    }

    private static void log(String msg, Object... args) {
        ThreedMapMod.LOGGER.info("[AUTOTEST] " + msg, args);
    }

    private static void check(boolean ok, String what, Object... args) {
        if (!ok) {
            failures++;
        }
        log((ok ? "PASS " : "FAIL ") + what, args);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!started && mc.screen instanceof TitleScreen) {
            started = true;
            startWorld(mc);
            return;
        }
        if (mc.player == null || mc.level == null) {
            return;
        }
        Runnable step = STEPS.remove(++inWorldTicks);
        if (step != null) {
            try {
                step.run();
            } catch (RuntimeException e) {
                failures++;
                log("FAIL step at tick {} threw {}", inWorldTicks, e.toString());
                ThreedMapMod.LOGGER.error("[AUTOTEST]", e);
            }
        }
    }

    private static void at(int tick, Runnable step) {
        STEPS.put(tick, step);
    }

    private static void startWorld(Minecraft mc) {
        if (mc.getLevelSource().levelExists(WORLD)) {
            log("reopening existing world");
            scheduleSecondRun(mc);
            mc.createWorldOpenFlows().openWorld(WORLD, () -> log("FAIL could not open world"));
        } else {
            log("creating world");
            scheduleFirstRun(mc);
            LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                    new GameRules(), WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(12345L, true, false),
                    WorldPresets::createNormalWorldDimensions, new TitleScreen());
        }
    }

    // ---------------------------------------------------------------- first run

    private static void scheduleFirstRun(Minecraft mc) {
        at(1, () -> {
            // The vanilla "Move with WASD" tutorial toast would cover the top right of every screenshot.
            mc.getTutorial().setStep(TutorialSteps.NONE);
            command(mc, "gamerule doDaylightCycle false");
        });
        at(200, () -> {
            stats(mc, "spawn explored");
            command(mc, "mark add AutoTest public");
        });
        at(230, () -> {
            check(findMark("AutoTest").isPresent(), "mark created by command reached ClientMarkStore");
            command(mc, "tp @s ~400 ~40 ~");
        });
        at(450, () -> openMap(mc, 0));
        at(480, () -> screenshot(mc, "map_near"));
        at(490, () -> zoom(mc, -9));
        at(530, () -> screenshot(mc, "map_far"));
        at(540, () -> {
            mc.setScreen(null);
            command(mc, "execute in minecraft:the_nether run tp @s 0 70 0");
        });
        at(840, () -> openMap(mc, 0));
        at(870, () -> screenshot(mc, "map_nether"));
        at(880, () -> {
            stats(mc, "nether explored");
            mc.setScreen(null);
            command(mc, "execute in minecraft:overworld run tp @s 200 120 0");
        });
        scheduleGuiChecks(mc, 980);
        scheduleConfigChecks(mc, 1160);
        schedule3dChecks(mc, 1300);
        scheduleUiChecks(mc, 1560);
        at(1700, () -> {
            stats(mc, "back in overworld");
            finish(mc, "first run");
        });
    }

    private static final int CLICK_DX = 40;
    private static final int CLICK_DY = 20;
    private static int[] expectedClick;

    /** Row of a mark in the list, using the same order as MarkListPanel: nearest to the player first. */
    private static int listRowOf(Minecraft mc, String name) {
        Vec3 p = mc.player.position();
        List<Mark> sorted = ClientMarkStore.all().stream()
                .filter(m -> m.dimension().equals(mc.level.dimension()))
                .sorted(Comparator.comparingDouble(m -> Math.pow(m.pos().getX() + 0.5 - p.x, 2)
                        + Math.pow(m.pos().getZ() + 0.5 - p.z, 2)))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).name().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException("mark not in list: " + name);
    }

    /** Create, inspect, edit and delete a mark only through the GUI, using the map, the list and the editor. */
    private static void scheduleGuiChecks(Minecraft mc, int t) {
        at(t, () -> command(mc, "mark add \"Тайник\""));
        at(t + 10, () -> {
            openMap(mc, 9); // back to 2 px/block after the earlier zoom-out
            MapScreen map = (MapScreen) mc.screen;
            int[] a = map.mapArea();
            // Expected block from the screen's own projection (GUI scale differs between machines).
            expectedClick = new int[]{
                    (int) Math.floor(map.centerX() + CLICK_DX / map.currentScale()),
                    (int) Math.floor(map.centerZ() + CLICK_DY / map.currentScale())};
            log("map zoom: {} gui px per block", map.currentScale());
            map.mouseClicked(a[0] + a[2] / 2.0 + CLICK_DX, a[1] + a[3] / 2.0 + CLICK_DY, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            check(mc.screen instanceof MarkEditScreen, "right click on empty map opens the create screen");
        });
        at(t + 15, () -> {
            Screen s = mc.screen;
            type(s, "Ферма GUI");
            MultiLineEditBox desc = widget(s, MultiLineEditBox.class, 0);
            s.setFocused(desc);
            type(s, "Вход за водопадом");
            s.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
            type(s, "Сундук слева");
            press(picker(s, MarkColors.PALETTE.length + MarkIcon.STAR.ordinal())); // icon: star
            press(button(s, "threed_map.visibility.private_long"));            // visibility -> public
        });
        at(t + 20, () -> screenshot(mc, "edit_new"));
        at(t + 25, () -> {
            press(button(mc.screen, "threed_map.edit.create"));
            check(mc.screen instanceof MapScreen, "Create returns to the map");
        });
        at(t + 45, () -> {
            Optional<Mark> mark = findMark("Ферма GUI");
            check(mark.isPresent(), "GUI mark created with cyrillic name");
            mark.ifPresent(m -> {
                check(m.icon() == MarkIcon.STAR && m.visibility() == Visibility.PUBLIC, "icon and visibility from GUI");
                check(m.description().equals("Вход за водопадом\nСундук слева"), "description from GUI: '{}'",
                        m.description());
                check(Math.abs(m.pos().getX() - expectedClick[0]) <= 1 && Math.abs(m.pos().getZ() - expectedClick[1]) <= 1,
                        "mark placed where clicked: {} vs {} {}", m.pos().toShortString(), expectedClick[0],
                        expectedClick[1]);
                hoverMark(mc, m);
            });
        });
        at(t + 55, () -> screenshot(mc, "map_tooltip"));
        at(t + 60, () -> {
            int row = listRowOf(mc, "Ферма GUI");
            MapScreen map = (MapScreen) mc.screen;
            Mark farm = findMark("Ферма GUI").orElseThrow();
            clickListRow(map, row, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(Math.abs(map.centerX() - (farm.pos().getX() + 0.5)) < 0.01
                            && Math.abs(map.centerZ() - (farm.pos().getZ() + 0.5)) < 0.01,
                    "click in the list centers the map on the mark");
            check(farm.id().equals(map.selectedId()), "click in the list selects the mark (details panel)");
            hoverListRow(mc, map, row);
        });
        at(t + 65, () -> screenshot(mc, "map_list_selected"));
        at(t + 70, () -> {
            clickListRow((MapScreen) mc.screen, listRowOf(mc, "Ферма GUI"), GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            check(mc.screen instanceof MarkEditScreen, "right click in the list opens the edit screen");
            Screen s = mc.screen;
            // EditBox reads Ctrl from the real keyboard, so clear with Backspace instead of Ctrl+A.
            for (int i = 0; i < 40; i++) {
                s.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
            }
            type(s, "Ферма 2");
        });
        at(t + 75, () -> screenshot(mc, "edit_existing"));
        at(t + 80, () -> press(button(mc.screen, "threed_map.edit.save")));
        at(t + 100, () -> {
            check(findMark("Ферма 2").isPresent() && findMark("Ферма GUI").isEmpty(), "rename through GUI");
            check(findMark("Ферма 2").map(m -> m.description().equals("Вход за водопадом\nСундук слева")).orElse(false),
                    "description kept on rename");
            Mark m = findMark("Ферма 2").orElseThrow();
            rightClickMark(mc, m);
            check(mc.screen instanceof MarkEditScreen, "right click on own mark on the map opens the edit screen");
            press(button(mc.screen, "threed_map.edit.delete"));
            check(mc.screen instanceof ConfirmScreen, "Delete asks for confirmation");
        });
        at(t + 105, () -> screenshot(mc, "confirm_delete"));
        at(t + 110, () -> {
            mc.screen.children().stream()
                    .filter(c -> c instanceof Button b && b.getMessage().equals(CommonComponents.GUI_YES))
                    .findFirst().map(Button.class::cast).orElseThrow().onPress();
            check(mc.screen instanceof MapScreen, "confirm returns to the map");
        });
        at(t + 130, () -> {
            check(findMark("Ферма 2").isEmpty(), "delete through GUI");
            // A request the server must reject: the GUI cannot produce it, a modified client could.
            PacketDistributor.sendToServer(new CreateMarkPayload("x".repeat(40), "", 0, MarkIcon.PIN, BlockPos.ZERO,
                    Level.OVERWORLD, Visibility.PRIVATE));
            command(mc, "mark describe \"Тайник\" Под ёлкой, три блока вниз");
        });
        at(t + 150, () -> {
            boolean toast = mc.getToasts().getToast(SystemToast.class, SystemToast.SystemToastId.PERIODIC_NOTIFICATION) != null;
            check(toast, "server rejection shown as a toast");
            check(findMark("x".repeat(40)).isEmpty(), "rejected mark not created");
            mc.setScreen(null);
            openMap(mc, 0);
            hoverMark(mc, findMark("Тайник").orElseThrow());
        });
        at(t + 160, () -> screenshot(mc, "map_private_tooltip"));
        at(t + 170, () -> mc.setScreen(null));
    }


    /** Details panel, hiding marks, search and the list toggle of the cartographer screen. */
    private static void scheduleUiChecks(Minecraft mc, int t) {
        at(t, () -> openMap(mc, 0));
        // The list is filled when the screen renders: click after the first frames.
        at(t + 3, () -> {
            clickListRow((MapScreen) mc.screen, listRowOf(mc, "Тайник"), GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(findMark("Тайник").orElseThrow().id().equals(((MapScreen) mc.screen).selectedId()),
                    "list click selects the mark");
        });
        at(t + 10, () -> screenshot(mc, "map_ui"));
        at(t + 15, () -> {
            Mark m = findMark("Тайник").orElseThrow();
            press(button(mc.screen, "threed_map.details.hide"));
            check(ClientMarkPrefs.isHidden(m.id()), "'Hide' in the details panel hides the mark");
            press(button(mc.screen, "threed_map.details.show"));
            check(!ClientMarkPrefs.isHidden(m.id()), "'Show' brings it back");

            MapScreen map = (MapScreen) mc.screen;
            int[] l = map.listArea();
            int row = listRowOf(mc, "Тайник");
            map.mouseClicked(l[0] + l[2] - 5, l[1] + row * 18 + 9, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(ClientMarkPrefs.isHidden(m.id()), "eye in the list hides the mark");
        });
        at(t + 20, () -> screenshot(mc, "map_hidden"));
        at(t + 25, () -> {
            MapScreen map = (MapScreen) mc.screen;
            int[] l = map.listArea();
            map.mouseClicked(l[0] + l[2] - 5, l[1] + listRowOf(mc, "Тайник") * 18 + 9, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            check(!ClientMarkPrefs.isHidden(findMark("Тайник").orElseThrow().id()), "eye again shows it");

            EditBox search = widget(map, EditBox.class, 0);
            map.setFocused(search);
            type(map, "тай");
            map.keyPressed(GLFW.GLFW_KEY_L, 0, 0); // typed into the box, must not toggle the list
            check(map.isListOpen(), "hotkeys are ignored while typing in the search box");
        });
        at(t + 30, () -> {
            MapScreen map = (MapScreen) mc.screen;
            check(map.listedMarks() == 1, "search 'тай' finds one mark: {}", map.listedMarks());
        });
        at(t + 35, () -> screenshot(mc, "map_search"));
        at(t + 38, () -> type(mc.screen, "zzz"));
        at(t + 41, () -> {
            MapScreen map = (MapScreen) mc.screen;
            check(map.listedMarks() == 0, "search 'тайzzz' finds nothing: {}", map.listedMarks());
        });
        at(t + 45, () -> {
            MapScreen map = (MapScreen) mc.screen;
            for (int i = 0; i < 10; i++) {
                map.keyPressed(GLFW.GLFW_KEY_BACKSPACE, 0, 0);
            }
            map.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0); // leaves the search box, keeps the map open
            check(mc.screen == map, "Escape in the search box does not close the map");
            map.keyPressed(GLFW.GLFW_KEY_L, 0, 0);
            check(!map.isListOpen(), "L hides the list");
        });
        at(t + 50, () -> screenshot(mc, "map_list_closed"));
        at(t + 55, () -> {
            ((MapScreen) mc.screen).keyPressed(GLFW.GLFW_KEY_L, 0, 0);
            check(((MapScreen) mc.screen).isListOpen(), "L shows it again");
            mc.setScreen(null);
        });
    }

    /** Switches the map to 3D, rotates, zooms and hovers a mark there, then switches back. */
    private static void schedule3dChecks(Minecraft mc, int t) {
        at(t, () -> {
            command(mc, "tp @s ~ ~ ~ 135 20"); // face south-west, so the arrow direction is visible
            openMap(mc, 0);
            press(button(mc.screen, "threed_map.tool.view"));
            check(((MapScreen) mc.screen).is3d(), "3D button switches the view");
        });
        at(t + 60, () -> {
            Map3DRenderer r = MapClient.renderer3d();
            int[] grid = r.meshGrid();
            check(r.hasMesh(), "3D mesh built");
            log("PERF 3D mesh {}x{} cells, step {}, built in {} ms", grid == null ? 0 : grid[0],
                    grid == null ? 0 : grid[0], grid == null ? 0 : grid[1], "%.1f".formatted(r.lastBuildMillis()));
        });
        at(t + 65, () -> screenshot(mc, "map_3d"));
        at(t + 70, () -> {
            MapScreen map = (MapScreen) mc.screen;
            int[] a = map.mapArea();
            double cx = a[0] + a[2] / 2.0;
            double cy = a[1] + a[3] / 2.0;
            map.mouseClicked(cx, cy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            map.mouseDragged(cx + 60, cy - 20, GLFW.GLFW_MOUSE_BUTTON_LEFT, 60, -20);
            map.mouseReleased(cx + 60, cy - 20, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        });
        at(t + 80, () -> screenshot(mc, "map_3d_rotated"));
        at(t + 85, () -> {
            MapScreen map = (MapScreen) mc.screen;
            int[] a = map.mapArea();
            map.mouseScrolled(a[0] + a[2] / 2.0, a[1] + a[3] / 2.0, 0, -8); // zoom out: bigger area, coarser step
        });
        at(t + 145, () -> {
            int[] grid = MapClient.renderer3d().meshGrid();
            check(grid != null && grid[1] > 1, "zoomed out mesh uses a coarser step: {}",
                    grid == null ? "none" : grid[1]);
            log("PERF 3D far mesh step {} built in {} ms", grid == null ? 0 : grid[1],
                    "%.1f".formatted(MapClient.renderer3d().lastBuildMillis()));
        });
        at(t + 150, () -> screenshot(mc, "map_3d_far"));
        at(t + 155, () -> {
            MapScreen map = (MapScreen) mc.screen;
            int[] a = map.mapArea();
            map.mouseScrolled(a[0] + a[2] / 2.0, a[1] + a[3] / 2.0, 0, 8);
        });
        at(t + 215, () -> {
            // After the zoom settled and the mesh was rebuilt, so last frame's projection is current.
            int[] a = ((MapScreen) mc.screen).mapArea();
            Mark m = findMark("Тайник").orElseThrow();
            double[] p = MapClient.renderer3d().project(m.pos().getX() + 0.5, m.pos().getY() + 1,
                    m.pos().getZ() + 0.5, a[0], a[1], a[2], a[3]);
            check(p != null, "mark projects into the 3D view");
            if (p != null) {
                moveMouse(mc, p);
            }
        });
        at(t + 225, () -> screenshot(mc, "map_3d_tooltip"));
        at(t + 230, () -> {
            press(button(mc.screen, "threed_map.tool.view"));
            check(!((MapScreen) mc.screen).is3d(), "2D button switches back");
            mc.setScreen(null);
        });
    }

    /** Edits the real config file of the world and checks that the new rules reach the GUI live. */
    private static void scheduleConfigChecks(Minecraft mc, int t) {
        at(t, () -> {
            int owned = ClientRules.ownedMarks();
            log("owned marks: {}", owned);
            setConfigValue(mc, "maxMarksPerPlayer", Integer.toString(owned));
        });
        at(t + 60, () -> {
            check(ClientRules.maxMarksPerPlayer() == ClientRules.ownedMarks(),
                    "config file edit reached the client: limit {}", ClientRules.maxMarksPerPlayer());
            openMap(mc, 0);
            MapScreen map = (MapScreen) mc.screen;
            int[] a = map.mapArea();
            map.mouseClicked(a[0] + a[2] / 2.0 + 30, a[1] + a[3] / 2.0 - 30, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            check(mc.screen instanceof MapScreen, "at the limit the create screen does not open");
            check(!button(mc.screen, "threed_map.tool.mark_here").active, "'Mark here' disabled at the limit");
        });
        at(t + 70, () -> screenshot(mc, "map_limit"));
        at(t + 80, () -> {
            mc.setScreen(null);
            setConfigValue(mc, "maxMarksPerPlayer", "50");
        });
        at(t + 130, () -> check(ClientRules.maxMarksPerPlayer() == 50, "limit restored live: {}",
                ClientRules.maxMarksPerPlayer()));
    }

    private static void setConfigValue(Minecraft mc, String key, String value) {
        // NeoForge 21.1 keeps SERVER configs in the global config folder.
        Path file = mc.gameDirectory.toPath().resolve("config").resolve("threed_map-server.toml");
        try {
            String text = Files.readString(file);
            String updated = text.replaceAll("(?m)^(\\s*" + key + "\\s*=\\s*).*$", "$1" + value);
            check(!updated.equals(text) || text.contains(key + " = " + value), "config key {} found in {}", key, file);
            Files.writeString(file, updated);
            log("config {} = {}", key, value);
        } catch (IOException e) {
            check(false, "config file {} writable: {}", file, e.toString());
        }
    }

    // ---------------------------------------------------------------- second run

    private static void scheduleSecondRun(Minecraft mc) {
        at(5, () -> {
            stats(mc, "right after reopening");
            openMap(mc, -9);
        });
        at(40, () -> {
            check(findMark("AutoTest").isPresent() && findMark("Тайник").isPresent(),
                    "marks survived the restart ({} marks)", ClientMarkStore.all().size());
            check(findMark("Тайник").map(m -> m.description().equals("Под ёлкой, три блока вниз")).orElse(false),
                    "description survived the restart");
        });
        at(60, () -> screenshot(mc, "map_reopen_far"));
        at(65, () -> benchmark(mc));
        at(70, () -> {
            cacheFiles(mc);
            finish(mc, "second run");
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Steps scheduled after the stop would silently never run: count them as failures. */
    private static void finish(Minecraft mc, String run) {
        if (!STEPS.isEmpty()) {
            check(false, "{} steps scheduled after the stop never ran (ticks {})", STEPS.size(), STEPS.keySet());
        }
        log("{} done: {} failures, stopping", run, failures);
        mc.stop();
    }

    private static Optional<Mark> findMark(String name) {
        return ClientMarkStore.all().stream().filter(m -> m.name().equals(name)).findFirst();
    }

    private static void command(Minecraft mc, String command) {
        log("/{}", command);
        mc.player.connection.sendCommand(command);
    }

    private static void openMap(Minecraft mc, int zoomSteps) {
        mc.setScreen(new MapScreen());
        if (zoomSteps != 0) {
            zoom(mc, zoomSteps);
        }
    }

    private static void zoom(Minecraft mc, int steps) {
        if (mc.screen instanceof MapScreen screen) {
            int[] a = screen.mapArea();
            screen.mouseScrolled(a[0] + a[2] / 2.0, a[1] + a[3] / 2.0, 0, steps);
        }
    }

    private static void type(Screen screen, String text) {
        text.codePoints().forEach(cp -> screen.charTyped((char) cp, 0));
    }

    private static void click(Screen screen, double x, double y) {
        screen.mouseClicked(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        screen.mouseReleased(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** GUI position of a mark on the map at the current center and zoom. */
    private static double[] markOnScreen(Minecraft mc, Mark m) {
        MapScreen map = (MapScreen) mc.screen;
        int[] a = map.mapArea();
        double scale = map.currentScale();
        return new double[]{
                a[0] + a[2] / 2.0 + (m.pos().getX() + 0.5 - map.centerX()) * scale,
                a[1] + a[3] / 2.0 + (m.pos().getZ() + 0.5 - map.centerZ()) * scale};
    }

    private static void rightClickMark(Minecraft mc, Mark m) {
        double[] p = markOnScreen(mc, m);
        mc.screen.mouseClicked(p[0], p[1], GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    /** Matches MarkListPanel: rows of 18 px below a 1 px border. */
    private static double[] listRow(MapScreen map, int row) {
        int[] l = map.listArea();
        return new double[]{l[0] + l[2] / 2.0, l[1] + row * 18 + 9};
    }

    private static void clickListRow(MapScreen map, int row, int button) {
        double[] p = listRow(map, row);
        map.mouseClicked(p[0], p[1], button);
        map.mouseReleased(p[0], p[1], button);
    }

    private static void hoverListRow(Minecraft mc, MapScreen map, int row) {
        moveMouse(mc, listRow(map, row));
    }

    /** n-th child widget of the given type. */
    private static <T> T widget(Screen s, Class<T> type, int index) {
        return s.children().stream().filter(type::isInstance).map(type::cast).skip(index).findFirst().orElseThrow();
    }

    /** Color swatches come first (12), then icons (8); Picker is private to MarkEditScreen. */
    private static AbstractButton picker(Screen s, int index) {
        return s.children().stream()
                .filter(c -> c instanceof AbstractButton && c.getClass().getSimpleName().equals("Picker"))
                .map(AbstractButton.class::cast).skip(index).findFirst().orElseThrow();
    }

    /** Any button (vanilla or parchment) whose message has the translation key; visible ones first. */
    private static AbstractButton button(Screen s, String translationKey) {
        return s.children().stream()
                .filter(c -> c instanceof AbstractButton b && b.getMessage().getContents() instanceof TranslatableContents tc
                        && tc.getKey().equals(translationKey))
                .map(AbstractButton.class::cast)
                .sorted(java.util.Comparator.comparing(b -> !b.visible))
                .findFirst().orElseThrow(() -> new IllegalStateException("no button " + translationKey));
    }

    private static void press(AbstractButton b) {
        b.onPress();
    }

    private static void hoverMark(Minecraft mc, Mark m) {
        moveMouse(mc, markOnScreen(mc, m));
    }

    /**
     * Puts the game's mouse position at a GUI point so the next frame renders hover effects and tooltips.
     * glfwSetCursorPos does not reach MouseHandler while the window is unfocused, so set its fields directly.
     */
    private static void moveMouse(Minecraft mc, double[] p) {
        double gs = mc.getWindow().getGuiScale();
        try {
            for (String field : new String[]{"xpos", "ypos"}) {
                Field f = MouseHandler.class.getDeclaredField(field);
                f.setAccessible(true);
                f.setDouble(mc.mouseHandler, (field.equals("xpos") ? p[0] : p[1]) * gs);
            }
        } catch (ReflectiveOperationException e) {
            check(false, "could not move mouse: {}", e.toString());
        }
    }

    private static void screenshot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "autotest_" + name + ".png", mc.getMainRenderTarget(),
                msg -> log("screenshot {}: {}", name, msg.getString()));
    }

    private static void stats(Minecraft mc, String label) {
        MapDimension dim = MapClient.current();
        log("{}: dim={} loadedRegions={} pendingChunks={} player={}", label,
                dim == null ? "null" : dim.dimension.location(),
                dim == null ? 0 : dim.loadedRegionCount(), MapClient.pendingChunks(),
                mc.player.blockPosition().toShortString());
    }

    /** Times a direct scan of loaded chunks around the player and one preview build. */
    private static void benchmark(Minecraft mc) {
        MapDimension dim = MapClient.current();
        ChunkScanner scanner = new ChunkScanner();
        int cx = mc.player.chunkPosition().x;
        int cz = mc.player.chunkPosition().z;
        int chunks = 0;
        long start = System.nanoTime();
        for (int dz = -6; dz <= 6; dz++) {
            for (int dx = -6; dx <= 6; dx++) {
                LevelChunk chunk = mc.level.getChunkSource().getChunk(cx + dx, cz + dz, ChunkStatus.FULL, false);
                if (chunk != null && !chunk.isEmpty() && scanner.scan(mc.level, chunk, dim)) {
                    chunks++;
                }
            }
        }
        long scanNanos = System.nanoTime() - start;
        log("PERF scanned {} chunks, {} ms per chunk", chunks,
                chunks == 0 ? "n/a" : "%.3f".formatted(scanNanos / 1e6 / chunks));

        MapRegion region = dim.loaded(mc.player.getBlockX() >> MapRegion.SHIFT, mc.player.getBlockZ() >> MapRegion.SHIFT);
        if (region != null) {
            start = System.nanoTime();
            MapShading.buildLod(dim, region);
            log("PERF preview build of one region {} ms", "%.2f".formatted((System.nanoTime() - start) / 1e6));
        }
    }

    private static void cacheFiles(Minecraft mc) {
        Path root = mc.gameDirectory.toPath().resolve("threed_map");
        try (Stream<Path> files = Files.walk(root)) {
            long count = files.filter(Files::isRegularFile).count();
            check(count > 0, "map cache has {} files", count);
        } catch (IOException e) {
            check(false, "map cache dir {} readable", root);
        }
    }
}
