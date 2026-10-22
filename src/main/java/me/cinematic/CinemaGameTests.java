package me.cinematic;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;
import me.cinematic.block.ScreenBlockEntity;
import me.cinematic.block.ScreenControllerBlock;
import me.cinematic.core.SessionStore;
import me.cinematic.network.Payloads;
import me.cinematic.server.CinemaServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Modern registry-based GameTests. JSON instances live in data/cinematic/test_instance. */
public final class CinemaGameTests {
    public static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(BuiltInRegistries.TEST_FUNCTION, Cinematic.ID);
    static {
        FUNCTIONS.register("registration", () -> CinemaGameTests::registration);
        FUNCTIONS.register("placement", () -> CinemaGameTests::placement);
        FUNCTIONS.register("replacement", () -> CinemaGameTests::replacement);
        FUNCTIONS.register("recipes", () -> CinemaGameTests::recipes);
        FUNCTIONS.register("dimensions", () -> CinemaGameTests::dimensions);
        FUNCTIONS.register("persistence", () -> CinemaGameTests::persistence);
        FUNCTIONS.register("audio_tracks", () -> CinemaGameTests::audioTracks);
        FUNCTIONS.register("unauthorized_controls", () -> CinemaGameTests::unauthorizedControls);
    }

    private static void registration(GameTestHelper helper) {
        helper.assertTrue(BuiltInRegistries.BLOCK.getKey(Cinematic.CONTROLLER.get()).toString().equals("cinematic:screen_controller"), "Controller block registered");
        helper.assertTrue(Cinematic.CONTROLLER_ITEM.get().getBlock() == Cinematic.CONTROLLER.get(), "Controller item links to the controller block");
        helper.assertTrue(BuiltInRegistries.ITEM.getKey(Cinematic.REMOTE.get()).toString().equals("cinematic:remote"), "Remote registered");
        helper.succeed();
    }

    private static void placement(GameTestHelper helper) {
        int x = 0;
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockPos pos = new BlockPos(x++, 1, 1);
            helper.setBlock(pos, Cinematic.CONTROLLER.get().defaultBlockState().setValue(ScreenControllerBlock.FACING, facing));
            helper.assertBlockProperty(pos, ScreenControllerBlock.FACING, facing);
            helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(pos)) instanceof ScreenBlockEntity, "Controller has its block entity");
            helper.setBlock(pos.above(), Cinematic.CONTROLLER.get().defaultBlockState().setValue(ScreenControllerBlock.FACING, facing));
            var level = helper.getLevel();
            var service = CinemaServer.get(level.getServer());
            var lower = helper.absolutePos(pos);
            var upper = lower.above();
            var panel = service.ensure(level, lower, level.getBlockState(lower));
            var child = service.ensure(level, upper, level.getBlockState(upper));
            helper.assertTrue(panel.id().equals(child.id()) && panel.width() == 1 && panel.height() == 2, "Blocks connect for facing " + facing);
        }
        helper.succeed();
    }

    private static void recipes(GameTestHelper helper) {
        var recipes = helper.getLevel().getServer().getRecipeManager();
        for (String name : List.of("screen_controller", "remote")) {
            helper.assertTrue(recipes.byKey(ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("cinematic", name))).isPresent(), "Crafting recipe loaded: " + name);
        }
        helper.succeed();
    }

    private static void replacement(GameTestHelper helper) {
        var relative = new BlockPos(1, 1, 1);
        var level = helper.getLevel();
        var pos = helper.absolutePos(relative);
        var service = CinemaServer.get(level.getServer());
        helper.setBlock(relative, Cinematic.CONTROLLER.get());
        var original = service.ensure(level, pos, level.getBlockState(pos));
        original.select("a".repeat(32), 60_000, 0);
        helper.setBlock(relative, net.minecraft.world.level.block.Blocks.AIR);
        helper.setBlock(relative, Cinematic.CONTROLLER.get());
        var replacement = service.ensure(level, pos, level.getBlockState(pos));
        helper.assertTrue(!replacement.id().equals(original.id()), "Immediate replacement gets a fresh screen identity");
        helper.assertTrue(replacement.width() == 1 && replacement.height() == 1, "Replacement starts at 1×1");
        var player = FakePlayerFactory.getMinecraft(level);
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        var stale = new Payloads.Control(original.id(), original.revision(), Payloads.Action.RESIZE, "", 0, 2, 2);
        helper.assertTrue(rejected(() -> service.applyControl(player, stale)), "Removed identities reject controls immediately");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.succeed();
    }

    private static void dimensions(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Cinematic.CONTROLLER.get().defaultBlockState().setValue(ScreenControllerBlock.FACING, Direction.SOUTH));
        var level = helper.getLevel();
        var service = CinemaServer.get(level.getServer());
        var screen = service.ensure(level, helper.absolutePos(relative), level.getBlockState(helper.absolutePos(relative)));
        helper.assertTrue(screen.width() == 1 && screen.height() == 1, "Screens start at 1×1");
        screen.select("a".repeat(32), 60_000, 0);
        screen.selectAudio(2, 0);
        var identity = screen.id();
        var facing = level.getBlockState(helper.absolutePos(relative)).getValue(ScreenControllerBlock.FACING);
        var right = facing.getCounterClockWise();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) {
                helper.setBlock(relative.relative(right, x).above(y), Cinematic.CONTROLLER.get().defaultBlockState().setValue(ScreenControllerBlock.FACING, facing));
            }
        }
        var far = helper.absolutePos(relative.relative(right, 2).above());
        var joined = service.ensure(level, far, level.getBlockState(far));
        helper.assertTrue(joined.id().equals(identity) && joined.width() == 3 && joined.height() == 2, "Touching blocks share one 3×2 screen: got " + joined.width() + "×" + joined.height() + ", same identity=" + joined.id().equals(identity));
        helper.assertTrue(joined.mediaId().equals("a".repeat(32)), "Adding blocks preserves media selection");
        helper.assertTrue(joined.audioStream() == 2, "Growth preserves shared audio selection");
        helper.setBlock(relative.relative(right, 1), net.minecraft.world.level.block.Blocks.AIR);
        joined = service.ensure(level, far, level.getBlockState(far));
        helper.assertTrue(joined.width() * joined.height() < 6, "Removing a block splits the panel without drawing across the hole");
        helper.setBlock(relative.relative(right, 1), Cinematic.CONTROLLER.get().defaultBlockState().setValue(ScreenControllerBlock.FACING, facing));
        joined = service.ensure(level, far, level.getBlockState(far));
        helper.assertTrue(joined.width() == 3 && joined.height() == 2, "Filling the hole reconnects the screen");
        helper.assertTrue(joined.audioStream() == 2, "Splitting and rejoining preserve shared audio selection");
        var extra = relative.relative(right, 3);
        helper.setBlock(extra, Cinematic.CONTROLLER.get().defaultBlockState().setValue(ScreenControllerBlock.FACING, facing.getOpposite()));
        var separatePos = helper.absolutePos(extra);
        var separate = service.ensure(level, separatePos, level.getBlockState(separatePos));
        helper.assertTrue(!separate.id().equals(joined.id()) && separate.width() == 1, "Different facing directions do not connect");
        helper.succeed();
    }

    private static void persistence(GameTestHelper helper) {
        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Cinematic.CONTROLLER.get().defaultBlockState().setValue(ScreenControllerBlock.FACING, Direction.SOUTH));
        var level = helper.getLevel();
        var screen = CinemaServer.get(level.getServer()).ensure(level, helper.absolutePos(relative), level.getBlockState(helper.absolutePos(relative)));
        screen.select("f".repeat(32), 60_000, 0);
        var state = level.getBlockState(helper.absolutePos(relative));
        var right = state.getValue(ScreenControllerBlock.FACING).getCounterClockWise();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 2; y++) { helper.setBlock(relative.relative(right, x).above(y), state); }
        }
        screen = CinemaServer.get(level.getServer()).ensure(level, helper.absolutePos(relative), state);
        screen.selectAudio(2, 0);
        screen.updateTimeline(screen.timeline().play(0));
        java.nio.file.Path folder = null;
        try {
            folder = Files.createTempDirectory("cinematic-gametest-");
            var store = new SessionStore(folder.resolve("sessions.dat"));
            store.save(List.of(screen), 5000);
            var restored = store.load(50_000).getFirst();
            helper.assertTrue(restored.id().equals(screen.id()) && restored.width() == 3 && restored.height() == 2, "Connected screen identity and dimensions survive persistence");
            helper.assertTrue(!restored.timeline().playing() && restored.timeline().positionAt(90_000) == 5000, "Restart restores paused playback");
            helper.assertTrue(restored.audioStream() == 2, "Restart restores shared audio selection");
        } catch (IOException error) { throw new IllegalStateException(error); }
        finally {
            if (folder != null) {
                try { Files.deleteIfExists(folder.resolve("sessions.dat")); Files.deleteIfExists(folder); }
                catch (IOException ignored) { Cinematic.LOG.warn("Cannot remove GameTest temporary directory"); }
            }
        }
        helper.succeed();
    }

    private static void unauthorizedControls(GameTestHelper helper) {
        var relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Cinematic.CONTROLLER.get());
        var level = helper.getLevel();
        var pos = helper.absolutePos(relative);
        var service = CinemaServer.get(level.getServer());
        var screen = service.ensure(level, pos, level.getBlockState(pos));
        var player = FakePlayerFactory.getMinecraft(level);
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        var loop = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.LOOP, "", 1, 0, 0);
        helper.assertTrue(rejected(() -> service.applyControl(player, loop)), "No remote means no controls");
        var audio = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, "a".repeat(32), 2, 0, 0);
        helper.assertTrue(rejected(() -> service.applyControl(player, audio)), "Audio selection requires a Remote");
        player.setItemInHand(InteractionHand.OFF_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        service.applyControl(player, loop);
        helper.assertTrue(screen.timeline().looping(), "Offhand remote permits controls");
        helper.assertTrue(rejected(() -> service.applyControl(player, loop)), "Stale revision rejected");
        helper.assertTrue(rejected(() -> service.applyControl(player, audio)), "Stale audio selection rejected");
        player.setPos(pos.getX() + 18, pos.getY(), pos.getZ());
        var distant = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.LOOP, "", 0, 0, 0);
        helper.assertTrue(rejected(() -> service.applyControl(player, distant)) && screen.timeline().looping(), "Distant controls do not mutate the screen");
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        var unavailable = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, "a".repeat(32), 999, 0, 0);
        helper.assertTrue(rejected(() -> service.applyControl(player, unavailable)), "Unindexed media and audio tracks rejected");
        var resize = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.RESIZE, "", 0, 8, 6);
        helper.assertTrue(rejected(() -> service.applyControl(player, resize)) && screen.width() == 1, "Network resize cannot create a virtual screen");
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        helper.succeed();
    }

    private static void audioTracks(GameTestHelper helper) {
        var relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Cinematic.CONTROLLER.get());
        var level = helper.getLevel();
        var pos = helper.absolutePos(relative);
        var service = CinemaServer.get(level.getServer());
        var screen = service.ensure(level, pos, level.getBlockState(pos));
        var player = FakePlayerFactory.getMinecraft(level);
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        player.setItemInHand(InteractionHand.MAIN_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        final String media;
        try {
            var path = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("cinematic/media/languages.mkv");
            media = me.cinematic.media.MediaLibrary.opaqueId("languages.mkv", Files.size(path), Files.getLastModifiedTime(path).toMillis());
        } catch (IOException e) { throw new IllegalStateException("GameTest language fixture missing", e); }
        helper.startSequence().thenWaitUntil(() -> {
            try { service.applyControl(player, new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.SELECT, media, 0, 0, 0)); }
            catch (IllegalArgumentException e) { helper.assertTrue(false, "Waiting for language fixture indexing"); }
        }).thenExecute(() -> {
            var paused = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, media, 1, 0, 0);
            service.applyControl(player, paused);
            helper.assertTrue(screen.audioStream() == 1 && !screen.timeline().playing(), "Paused language switch stays paused");
            helper.assertTrue(rejected(() -> service.applyControl(player, paused)), "Stale language revision rejected");
            service.applyControl(player, new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.PLAY_PAUSE, "", 0, 0, 0));
            service.applyControl(player, new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, media, 2, 0, 0));
            helper.assertTrue(screen.audioStream() == 2 && screen.timeline().playing(), "Playing language switch retains playback intent");
            helper.assertTrue(screen.timeline().anchorMs() > CinemaServer.now(), "Playing switch schedules preparation");
            var unknown = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, media, 999, 0, 0);
            helper.assertTrue(rejected(() -> service.applyControl(player, unknown)), "Unknown global stream rejected");
            var wrongMedia = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, "b".repeat(32), 1, 0, 0);
            helper.assertTrue(rejected(() -> service.applyControl(player, wrongMedia)), "Audio selection must match indexed media");
            player.setPos(pos.getX() + 20, pos.getY(), pos.getZ());
            var distant = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, media, 1, 0, 0);
            helper.assertTrue(rejected(() -> service.applyControl(player, distant)), "Distant audio switch rejected");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(screen.audioStream() == 2, "Rejected controls preserve shared audio");
        }).thenSucceed();
    }

    private static boolean rejected(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    private CinemaGameTests() {}
}
