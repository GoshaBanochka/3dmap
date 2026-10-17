package threed_map.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import threed_map.ThreedMapMod;
import threed_map.common.mark.Mark;
import threed_map.common.mark.MarkColors;
import threed_map.common.mark.MarkIcon;
import threed_map.common.mark.Visibility;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * /mark add &lt;name&gt; [public|private]
 * /mark list
 * /mark remove &lt;mark&gt;
 * /mark rename &lt;mark&gt; &lt;new name&gt;
 * /mark visibility &lt;mark&gt; public|private
 * /mark describe &lt;mark&gt; [text...]   (no text clears the description)
 * <p>
 * Names are Brigadier strings: one plain word, or "quoted" for spaces and non-latin letters.
 */
@EventBusSubscriber(modid = ThreedMapMod.MODID)
public final class MarkCommands {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private static final SuggestionProvider<CommandSourceStack> EDITABLE_MARKS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(
                    MarkService.editableBy(ctx.getSource()).stream()
                            .map(m -> StringArgumentType.escapeIfRequired(m.name())),
                    builder);

    private MarkCommands() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mark")
                .then(Commands.literal("add")
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> add(ctx, Visibility.PRIVATE))
                                .then(Commands.literal("public").executes(ctx -> add(ctx, Visibility.PUBLIC)))
                                .then(Commands.literal("private").executes(ctx -> add(ctx, Visibility.PRIVATE)))))
                .then(Commands.literal("list").executes(MarkCommands::list))
                .then(Commands.literal("remove")
                        .then(markArgument().executes(MarkCommands::remove)))
                .then(Commands.literal("rename")
                        .then(markArgument()
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(MarkCommands::rename))))
                .then(visibilityCommand())
                .then(Commands.literal("describe")
                        .then(markArgument()
                                .executes(ctx -> describe(ctx, ""))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(ctx -> describe(ctx, StringArgumentType.getString(ctx, "text")))))));
    }

    private static int describe(CommandContext<CommandSourceStack> ctx, String text) {
        CommandSourceStack source = ctx.getSource();
        try {
            Mark mark = MarkService.resolveEditable(source, StringArgumentType.getString(ctx, "mark"));
            Mark updated = MarkService.setDescription(source, mark.id(), text);
            source.sendSuccess(() -> Msg.tr("mark.updated", "Updated mark %s", describe(updated)), false);
            return 1;
        } catch (MarkException e) {
            source.sendFailure(e.component());
            return 0;
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> visibilityCommand() {
        return Commands.literal("visibility")
                .then(markArgument()
                        .then(Commands.literal("public").executes(ctx -> setVisibility(ctx, Visibility.PUBLIC)))
                        .then(Commands.literal("private").executes(ctx -> setVisibility(ctx, Visibility.PRIVATE))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> markArgument() {
        return Commands.argument("mark", StringArgumentType.string()).suggests(EDITABLE_MARKS);
    }

    private static int add(CommandContext<CommandSourceStack> ctx, Visibility visibility) {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Msg.tr("error.player_only", "Only players can create marks"));
            return 0;
        }
        try {
            Mark mark = MarkService.create(source, StringArgumentType.getString(ctx, "name"), "", visibility,
                    MarkColors.random(player.getRandom()), MarkIcon.PIN,
                    BlockPos.containing(source.getPosition()), player.level().dimension());
            source.sendSuccess(() -> Msg.tr("mark.created", "Created mark %s", describe(mark)), false);
            return 1;
        } catch (MarkException e) {
            source.sendFailure(e.component());
            return 0;
        }
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        List<Mark> marks = MarkService.visibleTo(source);
        if (marks.isEmpty()) {
            source.sendSuccess(() -> Msg.tr("mark.list_empty", "No marks"), false);
            return 0;
        }
        source.sendSuccess(() -> Msg.tr("mark.list_header", "Marks (%s):", marks.size()), false);
        for (Mark mark : marks) {
            source.sendSuccess(() -> describe(mark), false);
        }
        return marks.size();
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            Mark mark = MarkService.resolveEditable(source, StringArgumentType.getString(ctx, "mark"));
            MarkService.remove(source, mark.id());
            source.sendSuccess(() -> Msg.tr("mark.removed", "Removed mark %s", mark.name()), false);
            return 1;
        } catch (MarkException e) {
            source.sendFailure(e.component());
            return 0;
        }
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        try {
            Mark mark = MarkService.resolveEditable(source, StringArgumentType.getString(ctx, "mark"));
            Mark renamed = MarkService.rename(source, mark.id(), StringArgumentType.getString(ctx, "name"));
            source.sendSuccess(() -> Msg.tr("mark.renamed", "Renamed mark to %s", describe(renamed)), false);
            return 1;
        } catch (MarkException e) {
            source.sendFailure(e.component());
            return 0;
        }
    }

    private static int setVisibility(CommandContext<CommandSourceStack> ctx, Visibility visibility) {
        CommandSourceStack source = ctx.getSource();
        try {
            Mark mark = MarkService.resolveEditable(source, StringArgumentType.getString(ctx, "mark"));
            Mark updated = MarkService.setVisibility(source, mark.id(), visibility);
            source.sendSuccess(() -> Msg.tr("mark.updated", "Updated mark %s", describe(updated)), false);
            return 1;
        } catch (MarkException e) {
            source.sendFailure(e.component());
            return 0;
        }
    }

    /** One chat line: [id] name x y z (dimension) public/private; author and date on hover. */
    private static Component describe(Mark mark) {
        BlockPos p = mark.pos();
        MutableComponent hover = Msg.tr("mark.hover", "Author: %s\nCreated: %s",
                mark.ownerName(), DATE.format(Instant.ofEpochMilli(mark.createdAt())));
        if (!mark.description().isEmpty()) {
            hover.append("\n\n").append(Component.literal(mark.description()).withStyle(ChatFormatting.GRAY));
        }
        MutableComponent visibility = mark.isPublic()
                ? Msg.tr("visibility.public", "public").withStyle(ChatFormatting.GREEN)
                : Msg.tr("visibility.private", "private").withStyle(ChatFormatting.GOLD);
        return Component.empty()
                .append(Component.literal("[" + mark.shortId() + "] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(mark.name()).withStyle(Style.EMPTY.withColor(mark.color())))
                .append(Component.literal(" %d %d %d ".formatted(p.getX(), p.getY(), p.getZ())))
                .append(Component.literal("(" + mark.dimension().location() + ") ").withStyle(ChatFormatting.GRAY))
                .append(visibility)
                .withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }
}
