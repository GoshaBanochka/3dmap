package threed_map.server;

import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkIcon;
import threed_map.common.mark.MarkLimits;
import threed_map.common.mark.PublicMarksPolicy;
import threed_map.common.mark.Visibility;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import threed_map.common.net.CreateMarkPayload;
import threed_map.common.net.DeleteMarkPayload;
import threed_map.common.net.EditMarkPayload;
import threed_map.common.net.MarkErrorPayload;
import threed_map.common.net.MarkRemovePayload;
import threed_map.common.net.MarkUpsertPayload;
import threed_map.common.net.MarksSyncPayload;
import threed_map.common.net.ServerRulesPayload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Server-side checks for stage 1: commands, permissions, sync recipients, validation and persistence.
 * Every test body runs synchronously and cleans up its mock players.
 */
@GameTestHolder(ThreedMapMod.MODID)
@PrefixGameTestTemplate(false)
@SuppressWarnings("removal")
public class MarkGameTests {
    private static final String TEMPLATE = "empty";

    /** Records sent payloads and collects chat output of command sources. */
    private static final class Probe implements AutoCloseable {
        final List<Sent> sent = new ArrayList<>();
        final List<ServerPlayer> players = new ArrayList<>();
        private final BiConsumer<ServerPlayer, CustomPacketPayload> previous = MarkSync.sink;
        private final GameTestHelper helper;

        Probe(GameTestHelper helper) {
            this.helper = helper;
            MarkSync.sink = (player, payload) -> sent.add(new Sent(player.getUUID(), payload));
        }

        ServerPlayer player() {
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            players.add(player);
            return player;
        }

        List<CustomPacketPayload> to(ServerPlayer player) {
            return sent.stream().filter(s -> s.player.equals(player.getUUID())).map(Sent::payload).toList();
        }

        @Override
        public void close() {
            MarkSync.sink = previous;
            MinecraftServer server = helper.getLevel().getServer();
            MarkStorage storage = MarkStorage.get(server);
            for (ServerPlayer player : players) {
                storage.all().stream().filter(m -> m.owner().equals(player.getUUID()))
                        .map(Mark::id).toList().forEach(storage::remove);
                server.getPlayerList().remove(player);
            }
        }
    }

    private record Sent(UUID player, CustomPacketPayload payload) {
    }

    /** Captures what a command prints, so tests can assert on failures. */
    private static final class Output implements CommandSource {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            lines.add(component.getString());
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    private static CommandSourceStack source(ServerPlayer player, Output out, int permission) {
        return player.createCommandSourceStack().withSource(out).withPermission(permission);
    }

    private static void run(CommandSourceStack source, String command) {
        source.getServer().getCommands().performPrefixedCommand(source, command);
    }

    private static List<Mark> ownedBy(GameTestHelper helper, ServerPlayer player) {
        return MarkStorage.get(helper.getLevel().getServer()).all().stream()
                .filter(m -> m.owner().equals(player.getUUID())).toList();
    }

    private static Mark single(GameTestHelper helper, ServerPlayer player, String name) {
        List<Mark> found = ownedBy(helper, player).stream().filter(m -> m.name().equals(name)).toList();
        helper.assertTrue(found.size() == 1, "expected one mark '" + name + "', got " + found.size());
        return found.get(0);
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }

    @GameTest(template = TEMPLATE)
    public static void addCommandCreatesPublicAndPrivate(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            Output out = new Output();
            run(source(a, out, 0), "mark add \"Главная база\" public");
            run(source(a, out, 0), "mark add Shahta");
            run(source(a, out, 0), "mark add Farm private");

            Mark base = single(helper, a, "Главная база");
            check(helper, base.isPublic(), "quoted name with 'public' must be public");
            check(helper, base.dimension() == Level.OVERWORLD, "dimension must be the player's");
            check(helper, base.ownerName().equals(a.getGameProfile().getName()), "owner name stored");
            check(helper, Math.abs(base.createdAt() - System.currentTimeMillis()) < 60_000, "creation date set");
            check(helper, !single(helper, a, "Shahta").isPublic(), "default visibility must be private");
            check(helper, !single(helper, a, "Farm").isPublic(), "explicit private");
            check(helper, out.lines.stream().noneMatch(l -> l.contains("Unknown") || l.contains("error")),
                    "unexpected output: " + out.lines);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void broadcastRespectsVisibility(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            ServerPlayer b = probe.player();
            run(source(a, new Output(), 0), "mark add Public1 public");
            run(source(a, new Output(), 0), "mark add Secret");
            Mark pub = single(helper, a, "Public1");
            Mark secret = single(helper, a, "Secret");

            check(helper, containsUpsert(probe.to(a), pub) && containsUpsert(probe.to(a), secret),
                    "owner must receive both marks");
            check(helper, containsUpsert(probe.to(b), pub), "other player must receive public mark");
            check(helper, !mentions(probe.to(b), secret.id()), "other player must never receive a private mark");

            // Login sync: B gets only public ones, A gets both.
            probe.sent.clear();
            MarkSync.sendFullSync(b);
            MarkSync.sendFullSync(a);
            List<Mark> bSync = syncOf(probe.to(b));
            List<Mark> aSync = syncOf(probe.to(a));
            check(helper, bSync.contains(pub) && !bSync.contains(secret), "B login sync: " + names(bSync));
            check(helper, aSync.contains(pub) && aSync.contains(secret), "A login sync: " + names(aSync));
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void makingPrivateRemovesFromOthers(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            ServerPlayer b = probe.player();
            run(source(a, new Output(), 0), "mark add Base public");
            Mark base = single(helper, a, "Base");
            probe.sent.clear();

            run(source(a, new Output(), 0), "mark visibility Base private");
            check(helper, !single(helper, a, "Base").isPublic(), "mark must become private");
            check(helper, probe.to(b).stream().anyMatch(p -> p instanceof MarkRemovePayload r && r.id().equals(base.id())),
                    "B must get a remove packet");
            check(helper, probe.to(b).stream().noneMatch(p -> p instanceof MarkUpsertPayload),
                    "B must not get the private mark");
            check(helper, probe.to(a).stream().anyMatch(p -> p instanceof MarkUpsertPayload u && !u.mark().isPublic()),
                    "owner gets the updated mark");

            probe.sent.clear();
            run(source(a, new Output(), 0), "mark visibility Base public");
            check(helper, containsUpsert(probe.to(b), single(helper, a, "Base")), "public again: B receives it");

            probe.sent.clear();
            run(source(a, new Output(), 0), "mark remove Base");
            check(helper, ownedBy(helper, a).isEmpty(), "mark removed from storage");
            check(helper, probe.to(b).stream().anyMatch(p -> p instanceof MarkRemovePayload), "B told about removal");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void permissions(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            ServerPlayer b = probe.player();
            run(source(a, new Output(), 0), "mark add Base public");
            run(source(a, new Output(), 0), "mark add Mine");
            String mineId = single(helper, a, "Mine").shortId();

            Output bOut = new Output();
            run(source(b, bOut, 0), "mark rename Base Hacked");
            run(source(b, bOut, 0), "mark remove Base");
            check(helper, single(helper, a, "Base").name().equals("Base"), "non-owner must not edit");
            check(helper, bOut.lines.size() == 2, "non-owner gets two errors: " + bOut.lines);

            Output opOut = new Output();
            run(source(b, opOut, 2), "mark rename Base \"Renamed by OP\"");
            check(helper, ownedBy(helper, a).stream().anyMatch(m -> m.name().equals("Renamed by OP")),
                    "OP may edit any public mark: " + opOut.lines);
            run(source(b, opOut, 2), "mark remove Mine");
            run(source(b, opOut, 2), "mark remove " + mineId);
            check(helper, ownedBy(helper, a).stream().anyMatch(m -> m.name().equals("Mine")),
                    "OP must not reach others' private marks");

            Output listOut = new Output();
            run(source(b, listOut, 2), "mark list");
            check(helper, listOut.lines.stream().noneMatch(l -> l.contains("Mine")),
                    "list must not leak private marks: " + listOut.lines);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void validation(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            Output out = new Output();
            run(source(a, out, 0), "mark add \"" + "x".repeat(ServerConfig.maxNameLength() + 1) + "\"");
            run(source(a, out, 0), "mark add \"   \"");
            check(helper, ownedBy(helper, a).isEmpty(), "too long / blank names rejected: " + out.lines);

            run(source(a, out, 0), "mark add \"§cRed   name\"");
            check(helper, ownedBy(helper, a).stream().anyMatch(m -> m.name().equals("cRed name")),
                    "formatting stripped, spaces collapsed: " + names(ownedBy(helper, a)));

            for (int i = ownedBy(helper, a).size(); i < ServerConfig.maxMarksPerPlayer(); i++) {
                run(source(a, out, 0), "mark add m" + i);
            }
            check(helper, ownedBy(helper, a).size() == ServerConfig.maxMarksPerPlayer(), "fill up to limit");
            out.lines.clear();
            run(source(a, out, 0), "mark add overflow");
            check(helper, ownedBy(helper, a).size() == ServerConfig.maxMarksPerPlayer(), "limit enforced");
            check(helper, out.lines.stream().anyMatch(l -> l.contains("limit")), "limit message: " + out.lines);

            Output console = new Output();
            CommandSourceStack consoleSource = helper.getLevel().getServer().createCommandSourceStack()
                    .withSource(console);
            run(consoleSource, "mark add FromConsole");
            check(helper, console.lines.stream().anyMatch(l -> l.contains("Only players")),
                    "console cannot create: " + console.lines);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void persistsAcrossReload(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        try {
            Path dir = Files.createTempDirectory("threed_map_marks_test");
            Mark pub = new Mark(UUID.randomUUID(), "Главная база", "Сундуки слева,\nпечи справа", 0x1E88E5,
                    MarkIcon.HOME, new BlockPos(10, 64, -20), Level.NETHER, UUID.randomUUID(), "Alice", 1_700_000_000_000L, Visibility.PUBLIC);
            Mark priv = new Mark(UUID.randomUUID(), "Шахта", "", 0xE53935, MarkIcon.CAVE, new BlockPos(-5, -40, 7),
                    Level.OVERWORLD, UUID.randomUUID(), "Bob", 1_700_000_100_000L, Visibility.PRIVATE);

            DimensionDataStorage first = new DimensionDataStorage(dir.toFile(), server.getFixerUpper(), server.registryAccess());
            MarkStorage written = first.computeIfAbsent(MarkStorage.FACTORY, MarkStorage.FILE_ID);
            written.put(pub);
            written.put(priv);
            first.save();
            // NeoForge writes saved data on an IO worker; the real server waits for it on shutdown.
            IOUtilities.waitUntilIOWorkerComplete();
            check(helper, Files.exists(dir.resolve(MarkStorage.FILE_ID + ".dat")), "file written");

            DimensionDataStorage second = new DimensionDataStorage(dir.toFile(), server.getFixerUpper(), server.registryAccess());
            MarkStorage read = second.computeIfAbsent(MarkStorage.FACTORY, MarkStorage.FILE_ID);
            check(helper, List.copyOf(read.all()).equals(List.of(pub, priv)), "reloaded marks: " + read.all());
        } catch (Exception e) {
            helper.fail("I/O failure: " + e);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void payloadCodecsRoundTrip(GameTestHelper helper) {
        Mark mark = new Mark(UUID.randomUUID(), "Портал в Незер", "д".repeat(Mark.DESCRIPTION_HARD_LIMIT), 0x8E24AA,
                MarkIcon.PORTAL, new BlockPos(-30_000_000, 319, 29_999_999),
                Level.END, UUID.randomUUID(), "SixteenCharsName", Long.MAX_VALUE, Visibility.PRIVATE);
        MarksSyncPayload sync = new MarksSyncPayload(List.of(mark, mark.withVisibility(Visibility.PUBLIC)));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        MarksSyncPayload.STREAM_CODEC.encode(buf, sync);
        MarkUpsertPayload.STREAM_CODEC.encode(buf, new MarkUpsertPayload(mark));
        MarkRemovePayload.STREAM_CODEC.encode(buf, new MarkRemovePayload(mark.id()));

        check(helper, MarksSyncPayload.STREAM_CODEC.decode(buf).equals(sync), "sync payload round trip");
        check(helper, MarkUpsertPayload.STREAM_CODEC.decode(buf).mark().equals(mark), "upsert payload round trip");
        check(helper, MarkRemovePayload.STREAM_CODEC.decode(buf).id().equals(mark.id()), "remove payload round trip");
        check(helper, buf.readableBytes() == 0, "no trailing bytes");
        buf.release();
        helper.succeed();
    }

    /** Minimal payload context: the sender plus a record of replies. */
    private static final class FakeContext implements IPayloadContext {
        final ServerPlayer player;
        final List<CustomPacketPayload> replies = new ArrayList<>();

        FakeContext(ServerPlayer player) {
            this.player = player;
        }

        @Override
        public void reply(CustomPacketPayload payload) {
            replies.add(payload);
        }

        String lastError() {
            return replies.isEmpty() || !(replies.get(replies.size() - 1) instanceof MarkErrorPayload e)
                    ? null : e.message().getString();
        }

        @Override
        public ICommonPacketListener listener() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Player player() {
            return player;
        }

        @Override
        public CompletableFuture<Void> enqueueWork(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletableFuture<T> enqueueWork(Supplier<T> task) {
            return CompletableFuture.completedFuture(task.get());
        }

        @Override
        public PacketFlow flow() {
            return PacketFlow.SERVERBOUND;
        }

        @Override
        public void handle(CustomPacketPayload payload) {
        }

        @Override
        public void finishCurrentTask(ConfigurationTask.Type type) {
        }
    }

    @GameTest(template = TEMPLATE)
    public static void guiPayloadsAreValidated(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            ServerPlayer b = probe.player();
            FakeContext ctxA = new FakeContext(a);
            FakeContext ctxB = new FakeContext(b);
            BlockPos pos = new BlockPos(5, 70, -5);

            ServerPayloadHandlers.onCreate(new CreateMarkPayload("GUI mark", "", 0xFF123456, MarkIcon.STAR, pos,
                    Level.OVERWORLD, Visibility.PUBLIC), ctxA);
            Mark created = single(helper, a, "GUI mark");
            check(helper, ctxA.replies.isEmpty(), "valid create gives no error: " + ctxA.lastError());
            check(helper, created.color() == 0x123456, "alpha stripped from color");
            check(helper, created.icon() == MarkIcon.STAR && created.pos().equals(pos), "icon and position kept");
            check(helper, containsUpsert(probe.to(b), created), "public GUI mark broadcast");

            ServerPayloadHandlers.onCreate(new CreateMarkPayload("x".repeat(40), "", 0, MarkIcon.PIN, pos,
                    Level.OVERWORLD, Visibility.PRIVATE), ctxA);
            check(helper, ctxA.lastError() != null && ctxA.lastError().contains("too long"),
                    "long name rejected: " + ctxA.lastError());

            ServerPayloadHandlers.onCreate(new CreateMarkPayload("Far", "", 0, MarkIcon.PIN, new BlockPos(40_000_000, 64, 0),
                    Level.OVERWORLD, Visibility.PRIVATE), ctxA);
            check(helper, ctxA.lastError() != null && ctxA.lastError().contains("position"),
                    "outside world border rejected: " + ctxA.lastError());

            ServerPayloadHandlers.onEdit(new EditMarkPayload(created.id(), "Stolen", "", 0, MarkIcon.PIN, pos,
                    Visibility.PRIVATE), ctxB);
            check(helper, ctxB.lastError() != null, "non-owner edit rejected");
            check(helper, single(helper, a, "GUI mark").isPublic(), "mark unchanged after rejected edit");

            BlockPos moved = new BlockPos(6, 80, -6);
            ServerPayloadHandlers.onEdit(new EditMarkPayload(created.id(), "Moved", "Под мостом", 0x00FF00,
                    MarkIcon.HOME, moved, Visibility.PRIVATE), ctxA);
            Mark edited = single(helper, a, "Moved");
            check(helper, edited.pos().equals(moved) && edited.icon() == MarkIcon.HOME && !edited.isPublic()
                    && edited.description().equals("Под мостом")
                    && edited.createdAt() == created.createdAt() && edited.id().equals(created.id()),
                    "owner edit applies all fields, keeps id and date: " + edited);
            check(helper, probe.to(b).stream().anyMatch(p -> p instanceof MarkRemovePayload r && r.id().equals(created.id())),
                    "B told to forget the now private mark");

            ServerPayloadHandlers.onDelete(new DeleteMarkPayload(created.id()), ctxB);
            check(helper, ctxB.lastError() != null && ctxB.lastError().contains("not found"),
                    "other's private mark looks nonexistent: " + ctxB.lastError());
            ServerPayloadHandlers.onDelete(new DeleteMarkPayload(created.id()), ctxA);
            check(helper, ownedBy(helper, a).isEmpty(), "owner delete works");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void descriptions(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            FakeContext ctx = new FakeContext(a);
            ServerPayloadHandlers.onCreate(new CreateMarkPayload("Desc", "  Вход за водопадом  \r\n\n\n\n§cСундук\u0007 ",
                    0, MarkIcon.PIN, BlockPos.ZERO, Level.OVERWORLD, Visibility.PRIVATE), ctx);
            check(helper, single(helper, a, "Desc").description().equals("Вход за водопадом\n\ncСундук"),
                    "description cleaned: '" + single(helper, a, "Desc").description() + "'");

            ServerPayloadHandlers.onCreate(new CreateMarkPayload("Long", "x".repeat(MarkLimits.MAX_DESCRIPTION_LENGTH + 1),
                    0, MarkIcon.PIN, BlockPos.ZERO, Level.OVERWORLD, Visibility.PRIVATE), ctx);
            check(helper, ctx.lastError() != null && ctx.lastError().contains("too long"), "long description rejected");
            ServerPayloadHandlers.onCreate(new CreateMarkPayload("Lines", "a\n".repeat(MarkLimits.MAX_DESCRIPTION_LINES + 1),
                    0, MarkIcon.PIN, BlockPos.ZERO, Level.OVERWORLD, Visibility.PRIVATE), ctx);
            check(helper, ctx.lastError() != null && ctx.lastError().contains("lines"), "too many lines rejected");
            check(helper, ownedBy(helper, a).size() == 1, "rejected marks not stored");

            run(source(a, new Output(), 0), "mark describe Desc Портал в деревню");
            check(helper, single(helper, a, "Desc").description().equals("Портал в деревню"), "/mark describe sets text");
            run(source(a, new Output(), 0), "mark describe Desc");
            check(helper, single(helper, a, "Desc").description().isEmpty(), "/mark describe without text clears");
            MarkRateLimiter.forget(a.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void guiRequestsAreRateLimited(GameTestHelper helper) {
        try (Probe probe = new Probe(helper)) {
            ServerPlayer a = probe.player();
            FakeContext ctx = new FakeContext(a);
            for (int i = 0; i < 15; i++) {
                ServerPayloadHandlers.onCreate(new CreateMarkPayload("spam" + i, "", 0, MarkIcon.PIN, BlockPos.ZERO,
                        Level.OVERWORLD, Visibility.PRIVATE), ctx);
            }
            check(helper, ownedBy(helper, a).size() == 10, "only 10 requests per window: " + ownedBy(helper, a).size());
            check(helper, ctx.lastError() != null && ctx.lastError().contains("Too many"),
                    "rate limit message: " + ctx.lastError());
            MarkRateLimiter.forget(a.getUUID());
        }
        helper.succeed();
    }

    /** Sets config values for one test body and restores them afterwards. */
    private static final class ConfigOverride implements AutoCloseable {
        private final int maxMarks = ServerConfig.MAX_MARKS_PER_PLAYER.get();
        private final int maxName = ServerConfig.MAX_NAME_LENGTH.get();
        private final int maxDescription = ServerConfig.MAX_DESCRIPTION_LENGTH.get();
        private final PublicMarksPolicy policy = ServerConfig.PUBLIC_MARKS.get();
        private final int opLevel = ServerConfig.OP_PERMISSION_LEVEL.get();

        @Override
        public void close() {
            ServerConfig.MAX_MARKS_PER_PLAYER.set(maxMarks);
            ServerConfig.MAX_NAME_LENGTH.set(maxName);
            ServerConfig.MAX_DESCRIPTION_LENGTH.set(maxDescription);
            ServerConfig.PUBLIC_MARKS.set(policy);
            ServerConfig.OP_PERMISSION_LEVEL.set(opLevel);
        }
    }

    @GameTest(template = TEMPLATE)
    public static void configLimits(GameTestHelper helper) {
        try (Probe probe = new Probe(helper); ConfigOverride ignored = new ConfigOverride()) {
            ServerConfig.MAX_MARKS_PER_PLAYER.set(2);
            ServerConfig.MAX_NAME_LENGTH.set(5);
            ServerConfig.MAX_DESCRIPTION_LENGTH.set(4);
            ServerPlayer a = probe.player();
            Output out = new Output();

            run(source(a, out, 0), "mark add abcdef");
            check(helper, ownedBy(helper, a).isEmpty() && out.lines.stream().anyMatch(l -> l.contains("max 5")),
                    "configured name length enforced: " + out.lines);

            FakeContext ctx = new FakeContext(a);
            ServerPayloadHandlers.onCreate(new CreateMarkPayload("one", "12345", 0, MarkIcon.PIN, BlockPos.ZERO,
                    Level.OVERWORLD, Visibility.PRIVATE), ctx);
            check(helper, ctx.lastError() != null && ctx.lastError().contains("max 4"),
                    "configured description length enforced: " + ctx.lastError());

            run(source(a, out, 0), "mark add one");
            run(source(a, out, 0), "mark add two");
            out.lines.clear();
            run(source(a, out, 0), "mark add three");
            check(helper, ownedBy(helper, a).size() == 2 && out.lines.stream().anyMatch(l -> l.contains("(2)")),
                    "configured mark limit enforced: " + out.lines);

            ServerRulesPayload rules = ServerConfig.rules();
            check(helper, rules.maxMarksPerPlayer() == 2 && rules.maxNameLength() == 5
                    && rules.maxDescriptionLength() == 4, "rules payload reflects the config: " + rules);
            MarkRateLimiter.forget(a.getUUID());
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void configPublicPolicyAndOpLevel(GameTestHelper helper) {
        try (Probe probe = new Probe(helper); ConfigOverride ignored = new ConfigOverride()) {
            ServerPlayer a = probe.player();
            ServerPlayer b = probe.player();
            run(source(a, new Output(), 0), "mark add Old public"); // created while everyone may publish

            ServerConfig.PUBLIC_MARKS.set(PublicMarksPolicy.OP_ONLY);
            ServerConfig.OP_PERMISSION_LEVEL.set(3);

            Output out = new Output();
            run(source(a, out, 0), "mark add New public");
            check(helper, ownedBy(helper, a).stream().noneMatch(m -> m.name().equals("New"))
                    && out.lines.stream().anyMatch(l -> l.contains("not allowed")), "non-OP cannot publish: " + out.lines);
            run(source(a, out, 0), "mark add Mine");
            check(helper, !single(helper, a, "Mine").isPublic(), "non-OP can still create private marks");

            run(source(a, out, 2), "mark add Lvl2 public");
            check(helper, ownedBy(helper, a).stream().noneMatch(m -> m.name().equals("Lvl2")),
                    "level 2 is not OP when opPermissionLevel = 3");
            run(source(a, out, 3), "mark add Lvl3 public");
            check(helper, single(helper, a, "Lvl3").isPublic(), "level 3 may publish when opPermissionLevel = 3");

            run(source(a, out, 0), "mark rename Old Older");
            check(helper, single(helper, a, "Older").isPublic(), "owner may still edit an existing public mark");
            run(source(a, out, 0), "mark visibility Older private");
            run(source(a, out, 0), "mark visibility Older public");
            check(helper, !single(helper, a, "Older").isPublic(), "but cannot make it public again");

            run(source(b, out, 2), "mark rename Lvl3 Hacked");
            check(helper, ownedBy(helper, a).stream().anyMatch(m -> m.name().equals("Lvl3")),
                    "level 2 cannot edit others' public marks when opPermissionLevel = 3");
            run(source(b, out, 3), "mark rename Lvl3 Fixed");
            check(helper, ownedBy(helper, a).stream().anyMatch(m -> m.name().equals("Fixed")),
                    "level 3 can edit others' public marks");

            // Rules reach clients on login and on config reload.
            probe.sent.clear();
            MarkSync.onLogin(new PlayerEvent.PlayerLoggedInEvent(b));
            List<CustomPacketPayload> login = probe.to(b);
            check(helper, !login.isEmpty() && login.get(0) instanceof ServerRulesPayload r
                    && r.publicMarks() == PublicMarksPolicy.OP_ONLY && r.opPermissionLevel() == 3,
                    "rules sent first on login: " + login);
            probe.sent.clear();
            MarkSync.broadcastRules(helper.getLevel().getServer());
            check(helper, probe.to(a).stream().anyMatch(p -> p instanceof ServerRulesPayload)
                    && probe.to(b).stream().anyMatch(p -> p instanceof ServerRulesPayload), "rules broadcast to everyone");
        }
        helper.succeed();
    }

    private static boolean containsUpsert(List<CustomPacketPayload> payloads, Mark mark) {
        return payloads.stream().anyMatch(p -> p instanceof MarkUpsertPayload u && u.mark().equals(mark));
    }

    private static boolean mentions(List<CustomPacketPayload> payloads, UUID id) {
        return payloads.stream().anyMatch(p ->
                (p instanceof MarkUpsertPayload u && u.mark().id().equals(id))
                        || (p instanceof MarkRemovePayload r && r.id().equals(id))
                        || (p instanceof MarksSyncPayload s && s.marks().stream().anyMatch(m -> m.id().equals(id))));
    }

    private static List<Mark> syncOf(List<CustomPacketPayload> payloads) {
        return payloads.stream().filter(p -> p instanceof MarksSyncPayload).map(p -> ((MarksSyncPayload) p).marks())
                .reduce((x, y) -> y).orElse(List.of());
    }

    private static List<String> names(List<Mark> marks) {
        return marks.stream().map(Mark::name).toList();
    }
}
