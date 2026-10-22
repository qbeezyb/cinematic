package me.cinematic.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.cinematic.CinemaConfig;
import me.cinematic.Cinematic;
import me.cinematic.block.ScreenControllerBlock;
import me.cinematic.core.ControlPolicy;
import me.cinematic.core.PanelGeometry;
import me.cinematic.core.ScreenKey;
import me.cinematic.core.ScreenLayout;
import me.cinematic.core.ScreenSession;
import me.cinematic.core.SessionStore;
import me.cinematic.media.Credentials;
import me.cinematic.media.MediaHttpServer;
import me.cinematic.media.MediaLibrary;
import me.cinematic.media.NativeMediaProbe;
import me.cinematic.network.Payloads;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class CinemaServer implements AutoCloseable {
    private static final Map<MinecraftServer, CinemaServer> INSTANCES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final Map<UUID, ScreenSession> screens = new HashMap<>();
    private final Map<ScreenKey, ScreenSession> positions = new HashMap<>();
    private final Map<ScreenKey, Integer> pendingWalls = new HashMap<>();
    private final Set<UUID> verifiedWalls = new HashSet<>();
    private final Map<UUID, long[]> requestRates = new HashMap<>();
    private final Map<UUID, Long> accessIssued = new HashMap<>();
    private final Credentials credentials = new Credentials(System::currentTimeMillis);
    private final SessionStore store;
    private final ExecutorService saves = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("Cinematic saves").factory());
    private final AtomicBoolean saving = new AtomicBoolean();
    private MediaLibrary library;
    private MediaHttpServer http;
    private String mediaError = "";
    private String baseUrl = "";
    private boolean persistenceHealthy = true;
    private int tick;

    public static long now() { return System.nanoTime() / 1_000_000; }
    public static CinemaServer get(MinecraftServer server) { return INSTANCES.get(server); }

    public static void register(IEventBus bus) {
        bus.addListener((ServerAboutToStartEvent event) -> INSTANCES.put(event.getServer(), new CinemaServer(event.getServer())));
        bus.addListener((ServerStoppingEvent event) -> {
            var instance = INSTANCES.remove(event.getServer());
            if (instance != null) { instance.close(); }
        });
        bus.addListener((ServerTickEvent.Post event) -> {
            var instance = get(event.getServer());
            if (instance != null) { instance.tick(); }
        });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                var instance = get(player.level().getServer());
                if (instance != null) {
                    instance.credentials.revoke(player.getUUID());
                    instance.requestRates.remove(player.getUUID());
                    instance.accessIssued.remove(player.getUUID());
                }
            }
        });
        bus.addListener(CinemaServer::commands);
    }

    private CinemaServer(MinecraftServer server) {
        this.server = server;
        store = new SessionStore(server.getWorldPath(LevelResource.ROOT).resolve("data/cinematic-sessions.dat"));
        try {
            for (ScreenSession session : store.load(now())) {
                if (screens.putIfAbsent(session.id(), session) != null || positions.putIfAbsent(session.key(), session) != null) {
                    throw new IOException("Duplicate screen identity in saved sessions");
                }
            }
            // Index saved anchors first. Chunks loading establish physical membership; old
            // virtual dimensions must not claim another independent controller's blocks.
        } catch (IOException e) {
            persistenceHealthy = false;
            screens.clear(); positions.clear();
            Cinematic.LOG.error("Cannot restore Cinematic sessions. Back up and repair data/cinematic-sessions.dat; saving is disabled to preserve it", e);
        }
        try {
            library = new MediaLibrary(FMLPaths.GAMEDIR.get().resolve("cinematic/media"), new NativeMediaProbe(), Cinematic.LOG::info);
            library.reload().exceptionally(error -> { Cinematic.LOG.error("Media scan failed: {}", MediaLibrary.describe(error)); return 0; });
            String host = server.isDedicatedServer() ? CinemaConfig.BIND_ADDRESS.get() : "127.0.0.1";
            int port = server.isDedicatedServer() ? CinemaConfig.PORT.getAsInt() : 0;
            http = new MediaHttpServer(new InetSocketAddress(host, port), library, credentials);
            String advertised = CinemaConfig.ADVERTISED_URL.get().strip();
            if (!server.isDedicatedServer()) {
                baseUrl = "http://127.0.0.1:" + http.port();
            } else if (advertised.isEmpty()) {
                baseUrl = "http://{server}:" + http.port();
            } else {
                URI uri = URI.create(advertised);
                if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException("advertisedUrl must be an HTTP(S) URL without credentials, query, or fragment");
                }
                baseUrl = advertised.replaceAll("/+$", "");
            }
            Cinematic.LOG.info("Cinematic media service listening on {}:{}", host, http.port());
        } catch (Exception | LinkageError e) {
            mediaError = e instanceof NoClassDefFoundError && MediaLibrary.describe(e).contains("com/sun/net/httpserver")
                    ? "Media service needs the JDK HTTP module. Add --add-modules=jdk.httpserver to Java launch arguments and restart."
                    : "Media service unavailable. Check cinematic-local.toml bindAddress/port/advertisedUrl and restart (" + MediaLibrary.describe(e) + ")";
            Cinematic.LOG.error(mediaError);
            if (http != null) { http.close(); http = null; }
        }
    }

    public ScreenSession ensure(ServerLevel level, BlockPos pos, BlockState state) {
        ScreenKey key = key(level, pos);
        ScreenSession session = positions.get(key);
        if (session == null) {
            int facing = state.getValue(ScreenControllerBlock.FACING).get2DDataValue();
            blocksChanged(level, pos, facing);
            session = positions.get(key);
            if (session == null) {
                // A partial chunk load defers discovery. Reuse a persisted panel containing
                // this block instead of creating an independent screen on one of its tiles.
                session = screens.values().stream().filter(s -> s.facing() == facing && s.key().dimension().equals(key.dimension()))
                        .filter(s -> s.distanceSquared(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) == 0).findFirst().orElse(null);
            }
            if (session == null) {
                session = new ScreenSession(UUID.randomUUID(), key, facing);
                positions.put(key, session);
                screens.put(session.id(), session);
            }
        }
        return session;
    }

    private static ScreenKey key(ServerLevel level, BlockPos pos) {
        return new ScreenKey(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
    }

    private static BlockPos pos(ScreenKey key) { return new BlockPos(key.x(), key.y(), key.z()); }

    /** Rebuild only coplanar, equally-facing blocks. Never load a chunk to discover a wall. */
    public void blocksChanged(ServerLevel level, BlockPos changedPos, int facing) {
        ScreenKey changed = key(level, changedPos);
        if (!rebuildWall(level, changed, facing)) { pendingWalls.put(changed, facing); }
        else { pendingWalls.remove(changed); }
    }

    public void blockLoaded(ServerLevel level, BlockPos pos, int facing) {
        ScreenSession known = positions.get(key(level, pos));
        if (known == null || !verifiedWalls.contains(known.id())) { blocksChanged(level, pos, facing); }
    }

    private record WallPanel(ScreenKey origin, int width, int height, List<ScreenKey> members) {}

    private boolean rebuildWall(ServerLevel level, ScreenKey changed, int facing) {
        var seeds = List.of(changed, PanelGeometry.block(changed, facing, -1, 0), PanelGeometry.block(changed, facing, 1, 0),
                PanelGeometry.block(changed, facing, 0, -1), PanelGeometry.block(changed, facing, 0, 1));
        var visited = new HashSet<ScreenKey>();
        var panels = new ArrayList<WallPanel>();
        var affected = new HashSet<ScreenSession>();
        ScreenSession previous = positions.get(changed);
        if (previous != null) { affected.add(previous); }
        for (ScreenKey seed : seeds) {
            if (visited.contains(seed)) { continue; }
            var queue = new ArrayDeque<ScreenLayout.Cell>();
            var cells = new HashSet<ScreenLayout.Cell>();
            queue.add(new ScreenLayout.Cell(0, 0));
            while (!queue.isEmpty()) {
                var cell = queue.removeFirst();
                ScreenKey member = PanelGeometry.block(seed, facing, cell.right(), cell.up());
                if (!visited.add(member)) { continue; }
                BlockPos block = pos(member);
                if (level.isOutsideBuildHeight(block)) { continue; }
                if (!level.hasChunkAt(block)) { return false; }
                BlockState state = level.getBlockState(block);
                if (!state.is(Cinematic.CONTROLLER.get()) || state.getValue(ScreenControllerBlock.FACING).get2DDataValue() != facing) { continue; }
                cells.add(cell);
                if (cells.size() > 6400) { return false; }
                ScreenSession old = positions.get(member);
                if (old != null) { affected.add(old); }
                queue.add(new ScreenLayout.Cell(cell.right() - 1, cell.up()));
                queue.add(new ScreenLayout.Cell(cell.right() + 1, cell.up()));
                queue.add(new ScreenLayout.Cell(cell.right(), cell.up() - 1));
                queue.add(new ScreenLayout.Cell(cell.right(), cell.up() + 1));
            }
            for (var rectangle : ScreenLayout.rectangles(cells)) {
                ScreenKey origin = PanelGeometry.block(seed, facing, rectangle.origin().right(), rectangle.origin().up());
                var blocks = new ArrayList<ScreenKey>();
                for (int x = 0; x < rectangle.width(); x++) {
                    for (int y = 0; y < rectangle.height(); y++) { blocks.add(PanelGeometry.block(origin, facing, x, y)); }
                }
                panels.add(new WallPanel(origin, rectangle.width(), rectangle.height(), blocks));
            }
        }
        // Pick all donors before moving any anchors. Keep the active/selected screen on merge.
        long wallTime = now();
        Comparator<ScreenSession> priority = Comparator.<ScreenSession>comparingInt(s -> s.timeline().playingAt(wallTime) ? 0 : s.mediaId().isEmpty() ? 2 : 1)
                // Temporary splits during construction can have identical playback state.
                // Keep the lower/left anchor on rejoin instead of picking a random UUID.
                .thenComparingInt(s -> s.key().y())
                .thenComparingInt(s -> switch (facing) {
                    case 0 -> s.key().x();
                    case 1 -> s.key().z();
                    case 2 -> -s.key().x();
                    default -> -s.key().z();
                }).thenComparing(s -> s.id().toString());
        var used = new HashSet<UUID>();
        var assignments = new ArrayList<ScreenSession>();
        for (WallPanel panel : panels) {
            var candidates = panel.members().stream().map(positions::get).filter(java.util.Objects::nonNull)
                    .filter(s -> s.facing() == facing).distinct().sorted(priority).toList();
            ScreenSession donor = candidates.stream().filter(s -> !used.contains(s.id())).findFirst().orElse(null);
            ScreenSession assigned;
            if (donor != null) {
                assigned = donor;
                used.add(donor.id());
            } else if (!candidates.isEmpty()) {
                donor = candidates.getFirst();
                assigned = new ScreenSession(UUID.randomUUID(), panel.origin(), facing, panel.width(), panel.height(),
                        donor.mediaId(), donor.timeline(), donor.revision() + 1, donor.audioStream());
            } else { assigned = new ScreenSession(UUID.randomUUID(), panel.origin(), facing); }
            assignments.add(assigned);
        }
        positions.entrySet().removeIf(entry -> affected.contains(entry.getValue()));
        affected.forEach(s -> { screens.remove(s.id()); verifiedWalls.remove(s.id()); });
        for (int i = 0; i < panels.size(); i++) {
            WallPanel panel = panels.get(i);
            ScreenSession assigned = assignments.get(i);
            assigned.reshape(panel.origin(), panel.width(), panel.height());
            screens.put(assigned.id(), assigned);
            verifiedWalls.add(assigned.id());
            panel.members().forEach(member -> positions.put(member, assigned));
        }
        pendingWalls.entrySet().removeIf(entry -> entry.getValue() == facing && visited.contains(entry.getKey()));
        return true;
    }

    public void open(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (!level.getBlockState(pos).is(Cinematic.CONTROLLER.get())
                || pos.distToCenterSqr(player.position()) > 16 * 16) { return; }
        ScreenSession screen = ensure(level, pos, level.getBlockState(pos));
        sendNearby(player);
        sendAccess(player);
        if (hasRemote(player)) { remember(player, screen.id()); }
        PacketDistributor.sendToPlayer(player, new Payloads.Open(snapshot(screen)));
    }

    private static final String REMEMBERED_SCREEN = "cinematic:remote_screen";

    public static UUID remembered(ServerPlayer player) {
        String value = player.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG).getStringOr(REMEMBERED_SCREEN, "");
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException e) { return Payloads.NO_SCREEN; }
    }

    private static void remember(ServerPlayer player, UUID screen) {
        CompoundTag persisted = player.getPersistentData().getCompoundOrEmpty(Player.PERSISTED_NBT_TAG);
        persisted.putString(REMEMBERED_SCREEN, screen.toString());
        player.getPersistentData().put(Player.PERSISTED_NBT_TAG, persisted);
    }

    public void openRemote(ServerPlayer player) {
        if (!hasRemote(player) || !allowRequest(player)) { return; }
        Payloads.Snapshot initial = null;
        try { initial = snapshot(resolveScreen(remembered(player))); }
        catch (IllegalArgumentException ignored) { /* First use or a removed target opens selection. */ }
        PacketDistributor.sendToPlayer(player, new Payloads.Open(initial));
    }

    public Payloads.Snapshot selectTarget(ServerPlayer player, UUID id) {
        if (!hasRemote(player)) { throw new IllegalArgumentException("Hold a Cinematic Remote in either hand"); }
        ScreenSession screen = resolveScreen(id);
        remember(player, screen.id());
        return snapshot(screen);
    }

    /** Checks physical state in the target dimension, without loading its chunks. */
    public ScreenSession resolveScreen(UUID id) {
        ScreenSession screen = screens.get(id);
        if (screen == null) { throw new IllegalArgumentException("This screen no longer exists. Choose another screen."); }
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(screen.key().dimension())));
        if (level == null) { throw new IllegalArgumentException("This screen's dimension is unavailable"); }
        BlockPos anchor = pos(screen.key());
        if (level.hasChunkAt(anchor)) {
            BlockState state = level.getBlockState(anchor);
            if (!state.is(Cinematic.CONTROLLER.get()) || state.getValue(ScreenControllerBlock.FACING).get2DDataValue() != screen.facing()) {
                blocksChanged(level, anchor, screen.facing());
                screen = screens.get(id);
                if (screen == null || screen.key().equals(key(level, anchor))) {
                    throw new IllegalArgumentException("This screen no longer exists. Choose another screen.");
                }
            }
        }
        return screen;
    }

    public Payloads.ScreenList screenList(ServerPlayer player, long requestId, int offset, String query) {
        if (!hasRemote(player)) { throw new IllegalArgumentException("Hold a Cinematic Remote in either hand"); }
        if (offset < 0) { throw new IllegalArgumentException("Invalid screen page"); }
        String search = query.toLowerCase(Locale.ROOT);
        var valid = new ArrayList<ScreenSession>();
        for (var candidate : List.copyOf(screens.values())) {
            try {
                var screen = resolveScreen(candidate.id());
                var state = snapshot(screen);
                if (screenDetails(state).toLowerCase(Locale.ROOT).contains(search)) { valid.add(screen); }
            } catch (IllegalArgumentException ignored) { /* Reconciled removed screens are omitted. */ }
        }
        valid.sort(Comparator.comparing((ScreenSession s) -> s.key().dimension()).thenComparingInt(s -> s.key().x())
                .thenComparingInt(s -> s.key().y()).thenComparingInt(s -> s.key().z()).thenComparing(s -> s.id().toString()));
        return new Payloads.ScreenList(requestId, offset, valid.size(), valid.stream().skip(offset).limit(20).map(this::snapshot).toList(), "");
    }

    private static String screenDetails(Payloads.Snapshot s) {
        return s.key().dimension() + " " + s.key().x() + " " + s.key().y() + " " + s.key().z()
                + " " + s.width() + " × " + s.height() + " " + s.name();
    }

    public void request(ServerPlayer player, Payloads.Request request) {
        if (!allowRequest(player)) { return; }
        switch (request.kind()) {
            case CLOCK -> PacketDistributor.sendToPlayer(player, new Payloads.Clock(request.sentAt(), now()));
            case ACCESS -> sendAccess(player);
            case SCREENS -> {
                try { PacketDistributor.sendToPlayer(player, screenList(player, request.sentAt(), request.offset(), request.query())); }
                catch (IllegalArgumentException e) {
                    PacketDistributor.sendToPlayer(player, new Payloads.ScreenList(request.sentAt(), Math.max(0, request.offset()), 0, List.of(), limited(e.getMessage())));
                }
            }
            case TARGET, REFRESH -> {
                try {
                    if (!hasRemote(player)) { throw new IllegalArgumentException("Hold a Cinematic Remote in either hand"); }
                    var state = request.kind() == Payloads.RequestKind.TARGET ? selectTarget(player, request.screen()) : snapshot(resolveScreen(request.screen()));
                    PacketDistributor.sendToPlayer(player, new Payloads.ScreenState(state.id(), request.sentAt(), state, ""));
                } catch (IllegalArgumentException e) {
                    PacketDistributor.sendToPlayer(player, new Payloads.ScreenState(request.screen(), request.sentAt(), null, limited(e.getMessage())));
                }
            }
            case AUDIO_TRACKS -> {
                ScreenSession screen = screens.get(request.screen());
                if (screen == null) {
                    PacketDistributor.sendToPlayer(player, new Payloads.AudioTracks(request.screen(), request.query(), request.sentAt(), List.of(), "This screen no longer exists"));
                    return;
                }
                try {
                    screen = resolveScreen(screen.id());
                    ControlPolicy.check(screen, hasRemote(player), request.revision());
                    var entry = library == null ? null : library.get(screen.mediaId());
                    if (entry == null || !screen.mediaId().equals(request.query())) { throw new IllegalArgumentException("Media changed or unavailable. Reopen Audio."); }
                    PacketDistributor.sendToPlayer(player, new Payloads.AudioTracks(screen.id(), entry.id(), request.sentAt(), entry.audioTracks(), ""));
                } catch (IllegalArgumentException e) {
                    PacketDistributor.sendToPlayer(player, new Payloads.AudioTracks(screen.id(), request.query(), request.sentAt(), List.of(), limited(e.getMessage())));
                }
            }
            case LIBRARY -> {
                ScreenSession screen = screens.get(request.screen());
                if (screen == null) {
                    PacketDistributor.sendToPlayer(player, new Payloads.Library(request.screen(), request.sentAt(), Math.max(0, request.offset()), 0, List.of(), "This screen no longer exists"));
                    return;
                }
                try {
                    screen = resolveScreen(screen.id());
                    ControlPolicy.check(screen, hasRemote(player), request.revision());
                    if (request.offset() < 0 || request.offset() > 4096) { throw new IllegalArgumentException("Invalid media page"); }
                    if (library == null) { throw new IllegalArgumentException(mediaError); }
                    String query = request.query().toLowerCase(Locale.ROOT);
                    var matches = library.entries().stream().filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(query)).toList();
                    var page = matches.stream().skip(request.offset()).limit(20)
                            .map(entry -> new Payloads.LibraryItem(entry.id(), entry.name(), entry.durationMs(), entry.width() > 0)).toList();
                    PacketDistributor.sendToPlayer(player, new Payloads.Library(screen.id(), request.sentAt(), request.offset(), matches.size(), page, library.status()));
                } catch (IllegalArgumentException e) {
                    PacketDistributor.sendToPlayer(player, new Payloads.Library(request.screen(), request.sentAt(), Math.max(0, request.offset()), 0, List.of(), limited(e.getMessage())));
                }
            }
        }
    }

    public void control(ServerPlayer player, Payloads.Control control) {
        if (!allowRequest(player)) { return; }
        ScreenSession screen = screens.get(control.screen());
        if (screen == null) { notice(player, control.screen(), "This screen no longer exists"); return; }
        try { applyControl(player, control); }
        catch (IllegalArgumentException e) { notice(player, screen.id(), e.getMessage()); }
        server.getPlayerList().getPlayers().forEach(this::sendNearby);
    }

    /** Applies exactly the network permission/validation path, without sending a response. */
    public void applyControl(ServerPlayer player, Payloads.Control control) {
        ScreenSession screen = resolveScreen(control.screen());
        ControlPolicy.check(screen, hasRemote(player), control.revision());
        long now = now();
        switch (control.action()) {
            case SELECT -> {
                var entry = library == null ? null : library.get(control.mediaId());
                if (entry == null) { throw new IllegalArgumentException("Media missing or invalid; ask an operator to run /cinematic reload"); }
                screen.select(entry.id(), entry.durationMs(), now);
            }
            case AUDIO_TRACK -> {
                var entry = library == null ? null : library.get(screen.mediaId());
                if (entry == null || !screen.mediaId().equals(control.mediaId()) || control.value() < 0
                        || entry.audioTracks().stream().noneMatch(t -> t.streamIndex() == control.value())) {
                    throw new IllegalArgumentException("Audio track is unavailable for this media. Reopen Audio.");
                }
                screen.selectAudio((int) control.value(), now);
            }
            case RESIZE -> throw new IllegalArgumentException("Screen size follows the blocks you build. Add or remove Screen Blocks to resize.");
            case PLAY_PAUSE -> {
                if (library == null || library.get(screen.mediaId()) == null) { throw new IllegalArgumentException("Select an available media file first"); }
                screen.updateTimeline(screen.timeline().playingAt(now) ? screen.timeline().pause(now) : screen.timeline().preparePlay(now));
            }
            case STOP -> screen.updateTimeline(screen.timeline().stop(now));
            case SEEK -> {
                if (control.value() < 0 || control.value() > screen.timeline().durationMs()) { throw new IllegalArgumentException("Seek is outside the media timeline"); }
                screen.updateTimeline(screen.timeline().prepareSeek(control.value(), now));
            }
            case SKIP -> {
                if (control.value() != -10_000 && control.value() != 10_000) { throw new IllegalArgumentException("Skip must be ±10 seconds"); }
                screen.updateTimeline(screen.timeline().prepareSeek(screen.timeline().positionAt(now) + control.value(), now));
            }
            case LOOP -> {
                if (control.value() != 0 && control.value() != 1) { throw new IllegalArgumentException("Invalid looping value"); }
                screen.updateTimeline(screen.timeline().loop(control.value() == 1, now));
            }
        }
    }

    private boolean allowRequest(ServerPlayer player) {
        long window = now() / 1000;
        long[] rate = requestRates.computeIfAbsent(player.getUUID(), key -> new long[] {window, 0});
        if (rate[0] != window) { rate[0] = window; rate[1] = 0; }
        return ++rate[1] <= 20;
    }

    public static boolean hasRemote(ServerPlayer player) {
        return player.getMainHandItem().is(Cinematic.REMOTE.get()) || player.getOffhandItem().is(Cinematic.REMOTE.get());
    }

    private void sendAccess(ServerPlayer player) {
        long now = System.currentTimeMillis();
        if (now - accessIssued.getOrDefault(player.getUUID(), 0L) < 30_000) { return; }
        accessIssued.put(player.getUUID(), now);
        if (http == null) {
            PacketDistributor.sendToPlayer(player, new Payloads.Access("", "", 0, limited(mediaError)));
            return;
        }
        var lease = credentials.issue(player.getUUID());
        PacketDistributor.sendToPlayer(player, new Payloads.Access(baseUrl, lease.token(), lease.expiresMs(), ""));
    }

    private void tick() {
        tick++;
        // Inspect loaded blocks only; sessions in unloaded chunks keep their monotonic timelines.
        if (tick % 20 == 0) {
            var changes = new HashMap<>(pendingWalls);
            for (var screen : List.copyOf(screens.values())) {
                var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(screen.key().dimension())));
                if (level == null) { continue; }
                var pos = new BlockPos(screen.key().x(), screen.key().y(), screen.key().z());
                if (level.hasChunkAt(pos) && !level.getBlockState(pos).is(Cinematic.CONTROLLER.get())) { changes.put(screen.key(), screen.facing()); }
            }
            changes.entrySet().stream().limit(64).forEach(change -> {
                var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(change.getKey().dimension())));
                if (level != null && level.hasChunkAt(pos(change.getKey()))) { blocksChanged(level, pos(change.getKey()), change.getValue()); }
            });
            server.getPlayerList().getPlayers().forEach(this::sendNearby);
        }
        if (tick % 100 == 0) { save(); }
    }

    private void sendNearby(ServerPlayer player) {
        String dimension = player.level().dimension().identifier().toString();
        var nearby = screens.values().stream().filter(s -> s.key().dimension().equals(dimension))
                .filter(s -> s.distanceSquared(player.getX(), player.getY(), player.getZ()) < 256 * 256)
                .sorted(Comparator.comparingDouble(s -> s.distanceSquared(player.getX(), player.getY(), player.getZ())))
                .limit(64).map(this::snapshot).toList();
        PacketDistributor.sendToPlayer(player, new Payloads.Snapshots(nearby));
    }

    private Payloads.Snapshot snapshot(ScreenSession screen) {
        long now = now();
        var entry = library == null ? null : library.get(screen.mediaId());
        String error = !mediaError.isEmpty() ? mediaError : !screen.mediaId().isEmpty() && entry == null
                ? "Media missing or invalid; ask an operator to run /cinematic reload" : "";
        var timeline = screen.timeline();
        if (!timeline.playingAt(now)) { timeline = timeline.pause(now); }
        return new Payloads.Snapshot(screen.id(), screen.key(), screen.facing(), screen.width(), screen.height(), screen.mediaId(),
                entry == null ? "" : entry.name(), screen.timeline().durationMs(), entry == null ? 0 : entry.width(), entry == null ? 0 : entry.height(),
                entry != null && entry.audio(), entry == null ? -1 : entry.resolvedAudioStream(screen.audioStream()),
                timeline.positionMs(), timeline.anchorMs(), now, timeline.playing(), timeline.looping(), screen.revision(),
                CinemaConfig.AUDIO_RANGE.getAsInt(), limited(error));
    }

    private static String limited(String message) { return message.length() > 500 ? message.substring(0, 500) : message; }
    private static void notice(ServerPlayer player, UUID screen, String message) {
        PacketDistributor.sendToPlayer(player, new Payloads.Notice(screen, limited(message)));
    }

    private void save() {
        if (!persistenceHealthy || !saving.compareAndSet(false, true)) { return; }
        long now = now();
        var snapshot = screens.values().stream().map(s -> new ScreenSession(s.id(), s.key(), s.facing(), s.width(), s.height(), s.mediaId(), s.timeline().pause(now), s.revision(), s.audioStream())).toList();
        saves.submit(() -> {
            try { store.save(snapshot, now); }
            catch (IOException e) { Cinematic.LOG.error("Cannot save Cinematic sessions", e); }
            finally { saving.set(false); }
        });
    }

    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cinematic").requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))
                .then(Commands.literal("reload").executes(context -> {
                    var service = get(context.getSource().getServer());
                    if (service == null || service.library == null) {
                        context.getSource().sendFailure(Component.literal("Cinematic media directory is unavailable; check the server log")); return 0;
                    }
                    context.getSource().sendSuccess(() -> Component.literal("Scanning cinematic/media/…"), false);
                    service.library.reload().whenComplete((count, error) -> service.server.execute(() -> {
                        if (error != null) { context.getSource().sendFailure(Component.literal("Reload failed: " + MediaLibrary.describe(error))); }
                        else { context.getSource().sendSuccess(() -> Component.literal(service.library.status()), false); }
                    }));
                    return 1;
                })));
    }

    @Override public void close() {
        if (http != null) { http.close(); }
        credentials.clear();
        if (library != null) { library.close(); }
        // Finish an earlier periodic save, then write the final capture before shutdown.
        saves.shutdown();
        try {
            if (!saves.awaitTermination(5, TimeUnit.SECONDS)) { saves.shutdownNow(); }
            if (persistenceHealthy) { store.save(screens.values(), now()); }
        } catch (IOException e) { Cinematic.LOG.error("Cannot save final Cinematic sessions", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
