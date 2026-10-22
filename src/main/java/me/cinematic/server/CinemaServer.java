package me.cinematic.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.cinematic.CinemaConfig;
import me.cinematic.Cinematic;
import me.cinematic.block.ScreenControllerBlock;
import me.cinematic.core.ControlPolicy;
import me.cinematic.core.ScreenKey;
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
        ScreenKey key = new ScreenKey(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
        ScreenSession session = positions.get(key);
        if (session == null) {
            int facing = state.getValue(ScreenControllerBlock.FACING).get2DDataValue();
            session = new ScreenSession(UUID.randomUUID(), key, facing);
            positions.put(key, session);
            screens.put(session.id(), session);
        }
        return session;
    }

    public void remove(ServerLevel level, BlockPos pos) {
        ScreenKey key = new ScreenKey(level.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ());
        ScreenSession previous = positions.remove(key);
        if (previous != null) { screens.remove(previous.id()); }
    }

    public void open(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (!level.getBlockState(pos).is(Cinematic.CONTROLLER.get())
                || pos.distToCenterSqr(player.position()) > 16 * 16) { return; }
        ScreenSession screen = ensure(level, pos, level.getBlockState(pos));
        sendNearby(player);
        sendAccess(player);
        PacketDistributor.sendToPlayer(player, new Payloads.Open(screen.id()));
    }

    public void request(ServerPlayer player, Payloads.Request request) {
        if (!allowRequest(player)) { return; }
        switch (request.kind()) {
            case CLOCK -> PacketDistributor.sendToPlayer(player, new Payloads.Clock(request.sentAt(), now()));
            case ACCESS -> sendAccess(player);
            case LIBRARY -> {
                ScreenSession screen = screens.get(request.screen());
                if (screen == null) { notice(player, request.screen(), "This screen no longer exists"); return; }
                try {
                    ControlPolicy.check(screen, player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), hasRemote(player), screen.revision());
                    if (request.offset() < 0 || request.offset() > 4096) { throw new IllegalArgumentException("Invalid media page"); }
                    if (library == null) { throw new IllegalArgumentException(mediaError); }
                    String query = request.query().toLowerCase(Locale.ROOT);
                    var matches = library.entries().stream().filter(entry -> entry.name().toLowerCase(Locale.ROOT).contains(query)).toList();
                    var page = matches.stream().skip(request.offset()).limit(20)
                            .map(entry -> new Payloads.LibraryItem(entry.id(), entry.name(), entry.durationMs(), entry.width() > 0)).toList();
                    PacketDistributor.sendToPlayer(player, new Payloads.Library(screen.id(), request.sentAt(), request.offset(), matches.size(), page, library.status()));
                } catch (IllegalArgumentException e) { notice(player, screen.id(), e.getMessage()); }
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
        ScreenSession screen = screens.get(control.screen());
        if (screen == null) { throw new IllegalArgumentException("This screen no longer exists"); }
        ControlPolicy.check(screen, player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), hasRemote(player), control.revision());
        BlockPos pos = new BlockPos(screen.key().x(), screen.key().y(), screen.key().z());
        if (!player.level().hasChunkAt(pos) || !player.level().getBlockState(pos).is(Cinematic.CONTROLLER.get())) {
            throw new IllegalArgumentException("This screen no longer exists");
        }
        long now = now();
        switch (control.action()) {
            case SELECT -> {
                var entry = library == null ? null : library.get(control.mediaId());
                if (entry == null) { throw new IllegalArgumentException("Media missing or invalid; ask an operator to run /cinematic reload"); }
                screen.select(entry.id(), entry.durationMs(), now);
            }
            case RESIZE -> screen.resize(control.width(), control.height());
            case PLAY_PAUSE -> {
                if (library == null || library.get(screen.mediaId()) == null) { throw new IllegalArgumentException("Select an available media file first"); }
                screen.updateTimeline(screen.timeline().playingAt(now) ? screen.timeline().pause(now) : screen.timeline().play(now));
            }
            case STOP -> screen.updateTimeline(screen.timeline().stop(now));
            case SEEK -> {
                if (control.value() < 0 || control.value() > screen.timeline().durationMs()) { throw new IllegalArgumentException("Seek is outside the media timeline"); }
                screen.updateTimeline(screen.timeline().seek(control.value(), now));
            }
            case SKIP -> {
                if (control.value() != -10_000 && control.value() != 10_000) { throw new IllegalArgumentException("Skip must be ±10 seconds"); }
                screen.updateTimeline(screen.timeline().seek(screen.timeline().positionAt(now) + control.value(), now));
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
            var removed = new ArrayList<ScreenSession>();
            for (var screen : screens.values()) {
                var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(screen.key().dimension())));
                if (level == null) { continue; }
                var pos = new BlockPos(screen.key().x(), screen.key().y(), screen.key().z());
                if (level.hasChunkAt(pos) && !level.getBlockState(pos).is(Cinematic.CONTROLLER.get())) { removed.add(screen); }
            }
            removed.forEach(s -> { screens.remove(s.id()); positions.remove(s.key()); });
            server.getPlayerList().getPlayers().forEach(this::sendNearby);
        }
        if (tick % 100 == 0) { save(); }
    }

    private void sendNearby(ServerPlayer player) {
        String dimension = player.level().dimension().identifier().toString();
        var nearby = screens.values().stream().filter(s -> s.key().dimension().equals(dimension))
                .filter(s -> s.key().distanceSquared(player.getX(), player.getY(), player.getZ()) < 256 * 256)
                .sorted(Comparator.comparingDouble(s -> s.key().distanceSquared(player.getX(), player.getY(), player.getZ())))
                .limit(64).map(this::snapshot).toList();
        PacketDistributor.sendToPlayer(player, new Payloads.Snapshots(nearby));
    }

    private Payloads.Snapshot snapshot(ScreenSession screen) {
        long now = now();
        var entry = library == null ? null : library.get(screen.mediaId());
        String error = !mediaError.isEmpty() ? mediaError : !screen.mediaId().isEmpty() && entry == null
                ? "Media missing or invalid; ask an operator to run /cinematic reload" : "";
        return new Payloads.Snapshot(screen.id(), screen.key(), screen.facing(), screen.width(), screen.height(), screen.mediaId(),
                entry == null ? "" : entry.name(), screen.timeline().durationMs(), entry == null ? 0 : entry.width(), entry == null ? 0 : entry.height(),
                entry != null && entry.audio(), screen.timeline().positionAt(now), now, screen.timeline().playingAt(now), screen.timeline().looping(), screen.revision(),
                CinemaConfig.AUDIO_RANGE.getAsInt(), limited(error));
    }

    private static String limited(String message) { return message.length() > 500 ? message.substring(0, 500) : message; }
    private static void notice(ServerPlayer player, UUID screen, String message) {
        PacketDistributor.sendToPlayer(player, new Payloads.Notice(screen, limited(message)));
    }

    private void save() {
        if (!persistenceHealthy || !saving.compareAndSet(false, true)) { return; }
        long now = now();
        var snapshot = screens.values().stream().map(s -> new ScreenSession(s.id(), s.key(), s.facing(), s.width(), s.height(), s.mediaId(), s.timeline().pause(now), s.revision())).toList();
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
