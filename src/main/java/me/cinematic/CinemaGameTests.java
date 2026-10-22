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
        FUNCTIONS.register("global_remote", () -> CinemaGameTests::globalRemote);
        FUNCTIONS.register("remote_picker", () -> CinemaGameTests::remotePicker);
        FUNCTIONS.register("unloaded_remote", () -> CinemaGameTests::unloadedRemote);
        FUNCTIONS.register("remote_item", () -> CinemaGameTests::remoteItem);
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
        service.applyControl(player, distant);
        helper.assertTrue(!screen.timeline().looping(), "Distant controls mutate the shared screen");
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
            service.applyControl(player, distant);
            helper.assertTrue(screen.audioStream() == 1, "Distant audio switch succeeds");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.assertTrue(rejected(() -> service.applyControl(player, new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.AUDIO_TRACK, media, 2, 0, 0))), "Missing Remote rejects audio switch");
            helper.assertTrue(screen.audioStream() == 1, "Rejected controls preserve shared audio");
        }).thenSucceed();
    }

    private static void globalRemote(GameTestHelper helper) {
        var relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, Cinematic.CONTROLLER.get());
        var level = helper.getLevel(); var pos = helper.absolutePos(relative);
        var service = CinemaServer.get(level.getServer());
        var screen = service.ensure(level, pos, level.getBlockState(pos));
        var other = level.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(other != null && other != level, "A second dimension is available");
        var player = FakePlayerFactory.getMinecraft(other);
        player.setPos(pos.getX() + 50_000, 80, pos.getZ() + 50_000);
        player.setItemInHand(InteractionHand.MAIN_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        service.applyControl(player, new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.LOOP, "", 1, 0, 0));
        helper.assertTrue(screen.timeline().looping(), "Controls look up the screen in its dimension, independent of player location");
        service.selectTarget(player, screen.id());
        helper.assertTrue(CinemaServer.remembered(player).equals(screen.id()), "Target selection persists in player data");
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.write(player.getPersistentData(), new java.io.DataOutputStream(bytes));
            var saved = net.minecraft.nbt.NbtIo.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())));
            player.getPersistentData().remove(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
            player.getPersistentData().merge(saved);
            helper.assertTrue(CinemaServer.remembered(player).equals(screen.id()), "Remembered target survives serialized player NBT");
        } catch (IOException e) { throw new IllegalStateException(e); }
        var clone = FakePlayerFactory.get(other, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "CinematicClone"));
        clone.restoreFrom(player, false);
        helper.assertTrue(CinemaServer.remembered(clone).equals(screen.id()), "Remembered target survives respawn cloning");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertTrue(rejected(() -> service.selectTarget(player, screen.id())), "Selection requires a Remote");
        player.setItemInHand(InteractionHand.OFF_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        helper.assertTrue(rejected(() -> service.selectTarget(player, java.util.UUID.randomUUID())), "Invalid identities are rejected");
        helper.setBlock(relative, net.minecraft.world.level.block.Blocks.AIR);
        helper.assertTrue(rejected(() -> service.resolveScreen(screen.id())), "Removed target rejects lookup");
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        helper.succeed();
    }

    private static void remotePicker(GameTestHelper helper) {
        var level = helper.getLevel(); var service = CinemaServer.get(level.getServer());
        var player = FakePlayerFactory.getMinecraft(level);
        player.setItemInHand(InteractionHand.MAIN_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        var ids = new java.util.HashSet<java.util.UUID>();
        for (int i = 0; i < 23; i++) {
            var relative = new BlockPos(1, 4 + i * 2, 1);
            helper.setBlock(relative, Cinematic.CONTROLLER.get());
            var pos = helper.absolutePos(relative);
            ids.add(service.ensure(level, pos, level.getBlockState(pos)).id());
        }
        String query = level.dimension().identifier().toString();
        var first = service.screenList(player, 100, 0, query);
        var second = service.screenList(player, 101, 20, query);
        helper.assertTrue(first.requestId() == 100 && first.offset() == 0 && first.screens().size() == 20, "First page is bounded and correlated");
        helper.assertTrue(second.requestId() == 101 && second.offset() == 20 && second.total() == first.total(), "Next page retains total and correlation");
        var firstIds = first.screens().stream().map(Payloads.Snapshot::id).collect(java.util.stream.Collectors.toSet());
        helper.assertTrue(second.screens().stream().noneMatch(s -> firstIds.contains(s.id())), "Pages do not overlap");
        helper.assertTrue(service.screenList(player, 102, 0, "no-such-screen-xyz").total() == 0, "Unmatched searches return an empty page");
        helper.assertTrue(service.screenList(player, 103, Integer.MAX_VALUE, query).screens().isEmpty(), "Out-of-range pages are empty");
        helper.assertTrue(rejected(() -> service.screenList(player, 104, -1, query)), "Negative offsets are rejected");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.assertTrue(rejected(() -> service.screenList(player, 105, 0, query)), "Listing requires a Remote");
        helper.succeed();
    }

    /** Simulate the persisted session map before its remote chunk has loaded. */
    @SuppressWarnings("unchecked")
    private static void unloadedRemote(GameTestHelper helper) {
        var level = helper.getLevel(); var service = CinemaServer.get(level.getServer());
        var key = new me.cinematic.core.ScreenKey(level.dimension().identifier().toString(), 20_000_000, 80, 20_000_000);
        var anchor = new BlockPos(key.x(), key.y(), key.z());
        helper.assertTrue(!level.hasChunkAt(anchor), "Remote chunk starts unloaded");
        var screen = new me.cinematic.core.ScreenSession(java.util.UUID.randomUUID(), key, 0);
        screen.select("a".repeat(32), 7_200_000, 0);
        java.util.Map<java.util.UUID, me.cinematic.core.ScreenSession> sessions;
        try {
            var field = CinemaServer.class.getDeclaredField("screens"); field.setAccessible(true);
            sessions = (java.util.Map<java.util.UUID, me.cinematic.core.ScreenSession>) field.get(service);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        sessions.put(screen.id(), screen);
        try {
            var player = FakePlayerFactory.getMinecraft(level);
            player.setItemInHand(InteractionHand.MAIN_HAND, Cinematic.REMOTE.get().getDefaultInstance());
            service.selectTarget(player, screen.id());
            service.applyControl(player, new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.SEEK, "", 3_600_000, 0, 0));
            service.applyControl(player, new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.LOOP, "", 1, 0, 0));
            helper.assertTrue(screen.timeline().positionAt(CinemaServer.now()) == 3_600_000 && screen.timeline().looping(), "Persisted unloaded sessions accept validated controls");
            helper.assertTrue(!level.hasChunkAt(anchor), "Target selection and controls never load the chunk");
            var invalid = new Payloads.Control(screen.id(), screen.revision(), Payloads.Action.SEEK, "", -1, 0, 0);
            helper.assertTrue(rejected(() -> service.applyControl(player, invalid)), "Unloaded sessions still validate parameters");
            player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        } finally { sessions.remove(screen.id()); }
        helper.succeed();
    }

    private static void remoteItem(GameTestHelper helper) {
        var level = helper.getLevel(); var service = CinemaServer.get(level.getServer());
        var relative = new BlockPos(1, 1, 1); var pos = helper.absolutePos(relative);
        helper.setBlock(relative, Cinematic.CONTROLLER.get());
        var screen = service.ensure(level, pos, level.getBlockState(pos));
        var player = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "CinematicRemote"));
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        helper.assertTrue(CinemaServer.remembered(player).equals(Payloads.NO_SCREEN), "First-use Remote has no target");
        player.setItemInHand(InteractionHand.OFF_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        helper.assertTrue(Cinematic.REMOTE.get().use(level, player, InteractionHand.OFF_HAND) == net.minecraft.world.InteractionResult.SUCCESS,
                "First air use consumes the interaction and opens selection");
        var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), Direction.NORTH, pos, false);
        var event = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, pos, hit);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.isCanceled() && event.getCancellationResult() == net.minecraft.world.InteractionResult.SUCCESS,
                "Offhand Remote consumes the block interaction before block or item use repeats it");
        helper.assertTrue(CinemaServer.remembered(player).equals(screen.id()), "Screen Block use targets that screen");
        var other = pos.above(3);
        var otherHit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(other), Direction.NORTH, other, false);
        event = new net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock(player, InteractionHand.MAIN_HAND, other, otherHit);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        helper.assertTrue(event.isCanceled() && CinemaServer.remembered(player).equals(screen.id()), "Other blocks reopen the remembered target");
        player.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        player.setItemInHand(InteractionHand.MAIN_HAND, Cinematic.REMOTE.get().getDefaultInstance());
        helper.assertTrue(Cinematic.REMOTE.get().use(level, player, InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.SUCCESS,
                "Main-hand air use reopens the remembered target");
        helper.setBlock(relative, net.minecraft.world.level.block.Blocks.AIR);
        helper.assertTrue(Cinematic.REMOTE.get().use(level, player, InteractionHand.MAIN_HAND) == net.minecraft.world.InteractionResult.SUCCESS,
                "Removed remembered screens reopen selection without throwing");
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        helper.succeed();
    }

    private static boolean rejected(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    private CinemaGameTests() {}
}
