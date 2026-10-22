package me.cinematic.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import me.cinematic.Cinematic;
import me.cinematic.core.ClockSync;
import me.cinematic.core.PanelGeometry;
import me.cinematic.core.PersonalAudio;
import me.cinematic.core.ScreenKey;
import me.cinematic.core.Timeline;
import me.cinematic.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Client-thread state; media threads read only immutable snapshots and the synchronized clock. */
public final class ClientPlayback {
    public final RemoteTarget remote = new RemoteTarget();
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final PersonalAudio settings;
    private final ClockSync clock = new ClockSync();
    private boolean synced;
    private Object world;
    private Object connection;
    private String baseUrl = "";
    private String token = "";
    private String accessError = "";
    private long renewalAt;
    private long lastAccessRequest;
    private long lastClock;
    private long lastFlush;

    public ClientPlayback() { this(new PersonalAudio(FMLPaths.CONFIGDIR.get().resolve("cinematic-audio.properties"))); }

    ClientPlayback(PersonalAudio settings) {
        this.settings = settings;
        try { settings.load(); }
        catch (IOException e) { Cinematic.LOG.warn("Cannot read Cinematic personal volume preferences", e); }
    }

    public static long now() { return System.nanoTime() / 1_000_000; }
    public PersonalAudio settings() { return settings; }
    public Entry get(UUID id) { return entries.get(id); }
    public Entry get(ScreenKey key) { return entries.values().stream().filter(entry -> entry.state.key().equals(key)).findFirst().orElse(null); }

    public Payloads.Snapshot controlState(UUID id) {
        Entry nearby = entries.get(id);
        return remote.newest(id, nearby == null ? null : nearby.state());
    }

    public long controlPosition(UUID id) {
        var state = controlState(id);
        if (state == null) { return 0; }
        long serverNow = synced ? clock.serverTime(now()) : state.serverTimeMs();
        return new Timeline(state.durationMs(), state.positionMs(), state.anchorMs(), state.playing(), state.looping()).positionAt(serverNow);
    }

    public String controlStatus(UUID id) {
        var state = controlState(id);
        if (state == null) { return "Screen unavailable"; }
        Entry local = entries.get(id);
        if (local != null && local.decoder != null && local.state.revision() == state.revision()) { return local.status(); }
        if (!state.error().isEmpty()) { return state.error(); }
        if (state.mediaId().isEmpty()) { return "No media selected"; }
        if (state.playing() && (synced ? clock.serverTime(now()) : state.serverTimeMs()) < state.anchorMs()) { return "Preparing…"; }
        return state.playing() ? "Playing" : "Paused";
    }

    public void snapshots(Payloads.Snapshots packet) {
        var retained = new HashSet<UUID>();
        for (var state : packet.screens()) {
            remote.observe(state);
            retained.add(state.id());
            Entry entry = entries.get(state.id());
            if (entry == null) { entries.put(state.id(), new Entry(state)); }
            else if (state.revision() >= entry.state.revision()) { entry.update(state); }
        }
        entries.entrySet().removeIf(pair -> {
            if (!retained.contains(pair.getKey())) { pair.getValue().close(); return true; }
            return false;
        });
    }

    public void access(Payloads.Access packet) {
        accessError = packet.error();
        if (!accessError.isEmpty()) { return; }
        try {
            String advertised = packet.baseUrl();
            if (advertised.startsWith("http://{server}:")) {
                var connection = Minecraft.getInstance().getConnection();
                if (connection == null || !(connection.getConnection().getRemoteAddress() instanceof InetSocketAddress address)) {
                    throw new IllegalArgumentException("The server must configure an advertised media URL");
                }
                int port = Integer.parseInt(advertised.substring("http://{server}:".length()));
                advertised = new URI("http", null, address.getHostString(), port, null, null, null).toASCIIString();
            }
            URI uri = URI.create(advertised);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Server media URL is invalid; ask an operator to check advertisedUrl");
            }
            boolean changed = !baseUrl.equals(advertised) || !token.equals(packet.token());
            baseUrl = advertised;
            token = packet.token();
            renewalAt = System.currentTimeMillis() + Math.max(30_000, Math.min(300_000, packet.expiresMs() - System.currentTimeMillis() - 60_000));
            if (changed) { entries.values().forEach(Entry::closeDecoder); }
        } catch (IllegalArgumentException | URISyntaxException e) { accessError = "Cannot connect to media. Ask an operator to check advertisedUrl and the media port."; }
    }

    public void clock(Payloads.Clock packet) {
        if (packet.sentAt() == lastClock) {
            clock.sample(packet.sentAt(), now(), packet.serverTimeMs());
            synced = true;
        }
    }

    public void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() != connection) {
            reset();
            connection = minecraft.getConnection();
        }
        if (minecraft.level != world) {
            entries.values().forEach(Entry::close);
            entries.clear();
            world = minecraft.level;
            if (world != null) {
                String identity = minecraft.getCurrentServer() != null ? minecraft.getCurrentServer().ip
                        : minecraft.getSingleplayerServer() != null ? minecraft.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().toString() : "local";
                settings.server(identity);
            }
        }
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) { return; }
        long now = now();
        if (lastClock == 0 || now - lastClock >= 5000) {
            lastClock = now;
            ClientPacketDistributor.sendToServer(new Payloads.Request(Payloads.RequestKind.CLOCK, Payloads.NO_SCREEN, 0, now, ""));
        }
        if ((token.isEmpty() || System.currentTimeMillis() >= renewalAt) && now - lastAccessRequest > 30_000) {
            lastAccessRequest = now;
            ClientPacketDistributor.sendToServer(new Payloads.Request(Payloads.RequestKind.ACCESS, Payloads.NO_SCREEN, 0, now, ""));
        }
        var active = entries.values().stream().filter(entry -> !entry.state.mediaId().isEmpty() && entry.state.error().isEmpty())
                .sorted(Comparator.comparingDouble(entry -> PanelGeometry.distanceSquared(entry.state.key(), entry.state.facing(), entry.state.width(), entry.state.height(),
                        minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ())))
                .limit(4).toList();
        for (Entry entry : entries.values()) {
            if (active.contains(entry) && synced && !token.isEmpty() && accessError.isEmpty()) { entry.tick(); }
            else { entry.closeDecoder(); entry.releaseTexture(); }
        }
        if (now - lastFlush >= 5000) { flush(); lastFlush = now; }
    }

    public void retry(UUID screen) {
        Entry entry = entries.get(screen);
        if (entry != null) { entry.closeDecoder(); }
        if (!accessError.isEmpty()) { accessError = ""; token = ""; }
    }

    public void flush() {
        try { settings.flush(); }
        catch (IOException e) { Cinematic.LOG.warn("Cannot save Cinematic personal volume preferences", e); }
    }

    public void reset() {
        entries.values().forEach(Entry::close);
        entries.clear(); remote.open(null); clock.reset(); synced = false;
        world = null; connection = null;
        baseUrl = ""; token = ""; accessError = ""; renewalAt = 0; lastClock = 0; lastAccessRequest = 0;
        flush();
    }

    public final class Entry implements AutoCloseable {
        private volatile Payloads.Snapshot state;
        private MediaDecoder decoder;
        private MediaSound sound;
        private DynamicTexture texture;
        private final Identifier textureId;
        private int epoch;
        private long lastResync;
        private long previousPosition = -1;
        private boolean presenting;

        private Entry(Payloads.Snapshot state) {
            this.state = state;
            textureId = Identifier.fromNamespaceAndPath(Cinematic.ID, "video/" + state.id());
        }

        public Payloads.Snapshot state() { return state; }
        public long position() {
            var snapshot = state;
            if (!synced) { return snapshot.positionMs(); }
            return new Timeline(snapshot.durationMs(), snapshot.positionMs(), snapshot.anchorMs(), snapshot.playing(), snapshot.looping())
                    .positionAt(clock.serverTime(now()));
        }

        private void update(Payloads.Snapshot next) {
            boolean audioChanged = state.audioStream() != next.audioStream();
            boolean mediaChanged = !state.mediaId().equals(next.mediaId());
            long expected = position();
            var adjusted = new Timeline(next.durationMs(), next.positionMs(), next.anchorMs(), next.playing(), next.looping());
            boolean needsSeek = state.playing() != next.playing()
                    || Math.abs(expected - adjusted.positionAt(clock.serverTime(now()))) > 50
                    || (!next.playing() && state.positionMs() != next.positionMs())
                    || (next.anchorMs() > clock.serverTime(now()) && next.anchorMs() != state.anchorMs());
            boolean resized = state.width() != next.width() || state.height() != next.height() || !state.key().equals(next.key());
            state = next;
            if (mediaChanged) { closeDecoder(); releaseTexture(); }
            else if (audioChanged) { closeDecoder(); }
            else if (decoder != null && needsSeek) { presenting = false; stopSound(); decoder.seek(position(), next.playing()); }
            if (mediaChanged || audioChanged || needsSeek) { previousPosition = position(); }
            if (resized) { stopSound(); }
        }

        private void tick() {
            if (decoder == null) {
                decoder = new MediaDecoder(baseUrl + "/media/" + state.mediaId(), token, state.videoWidth(), state.videoHeight(), state.audioStream(), this::position, state.playing());
                epoch = decoder.epoch();
            }
            decoder.discardBefore(position() - 50);
            if (!decoder.error().isEmpty()) { stopSound(); return; }
            if (epoch != decoder.epoch()) { stopSound(); epoch = decoder.epoch(); }
            if (decoder.takeResync() && now() - lastResync > 5000) {
                stopSound(); presenting = false; decoder.seek(position(), state.playing()); lastResync = now();
            }
            long position = position();
            if (previousPosition >= 0 && state.looping() && state.playing()
                    && position + state.durationMs() / 2 < previousPosition) {
                stopSound(); presenting = false; decoder.seek(position, true);
            }
            previousPosition = position;
            if (!presenting && decoder.buffered() && clock.serverTime(now()) >= state.anchorMs()) { presenting = true; }
            if (!state.playing() || clock.serverTime(now()) < state.anchorMs()) { stopSound(); }
            else if (state.audio() && decoder.buffered() && sound == null && decoder.error().isEmpty()) {
                sound = new MediaSound(state, decoder);
                sound.tick();
                Minecraft.getInstance().getSoundManager().play(sound);
            }
        }

        /** Called during extraction, at display frequency, rather than the 20-Hz client tick. */
        public Identifier texture() {
            RenderSystem.assertOnRenderThread();
            if (decoder != null && (!state.playing() || presenting || clock.serverTime(now()) < state.anchorMs())) {
                var frame = decoder.frame(position());
                if (frame != null) {
                    if (texture == null || texture.getPixels() == null || texture.getPixels().getWidth() != frame.width() || texture.getPixels().getHeight() != frame.height()) {
                        releaseTexture();
                        texture = new DynamicTexture(() -> "Cinematic video " + state.id(), new NativeImage(frame.width(), frame.height(), false));
                        Minecraft.getInstance().getTextureManager().register(textureId, texture);
                    }
                    var pixels = texture.getPixels();
                    pixels.getPixelBytes().put(frame.rgba());
                    texture.upload();
                }
            }
            return texture == null ? null : textureId;
        }

        public String status() {
            if (!state.error().isEmpty()) { return state.error(); }
            if (state.mediaId().isEmpty()) { return "No media selected"; }
            if (!accessError.isEmpty()) { return accessError; }
            if (!synced || token.isEmpty()) { return "Connecting to media…"; }
            if (decoder == null) { return "Playback limited to the four nearest screens"; }
            if (!decoder.error().isEmpty()) { return decoder.error(); }
            if (state.playing() && clock.serverTime(now()) < state.anchorMs()) { return "Preparing…"; }
            if (!decoder.ready() || (state.playing() && !presenting)) { return decoder.stage(); }
            return state.playing() ? "Playing" : "Paused";
        }

        private void stopSound() {
            if (sound != null) { Minecraft.getInstance().getSoundManager().stop(sound); sound = null; }
        }
        private void closeDecoder() { presenting = false; previousPosition = -1; stopSound(); if (decoder != null) { decoder.close(); decoder = null; } }
        private void releaseTexture() {
            RenderSystem.assertOnRenderThread();
            if (texture != null) { Minecraft.getInstance().getTextureManager().release(textureId); texture = null; }
        }
        @Override public void close() { closeDecoder(); releaseTexture(); }
    }
}
