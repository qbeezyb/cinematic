package me.cinematic.client;

import com.mojang.blaze3d.platform.NativeImage;
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
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final PersonalAudio settings = new PersonalAudio(FMLPaths.CONFIGDIR.get().resolve("cinematic-audio.properties"));
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

    public ClientPlayback() {
        try { settings.load(); }
        catch (IOException e) { Cinematic.LOG.warn("Cannot read Cinematic personal volume preferences", e); }
    }

    public static long now() { return System.nanoTime() / 1_000_000; }
    public PersonalAudio settings() { return settings; }
    public Entry get(UUID id) { return entries.get(id); }
    public Entry get(ScreenKey key) { return entries.values().stream().filter(entry -> entry.state.key().equals(key)).findFirst().orElse(null); }

    public void snapshots(Payloads.Snapshots packet) {
        var retained = new HashSet<UUID>();
        for (var state : packet.screens()) {
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
            else { entry.closeDecoder(); }
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
        entries.clear(); clock.reset(); synced = false;
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

        private Entry(Payloads.Snapshot state) {
            this.state = state;
            textureId = Identifier.fromNamespaceAndPath(Cinematic.ID, "video/" + state.id());
        }

        public Payloads.Snapshot state() { return state; }
        public long position() {
            var snapshot = state;
            if (!synced) { return snapshot.positionMs(); }
            return new Timeline(snapshot.durationMs(), snapshot.positionMs(), snapshot.serverTimeMs(), snapshot.playing(), snapshot.looping())
                    .positionAt(clock.serverTime(now()));
        }

        private void update(Payloads.Snapshot next) {
            boolean mediaChanged = !state.mediaId().equals(next.mediaId());
            long expected = position();
            var adjusted = new Timeline(next.durationMs(), next.positionMs(), next.serverTimeMs(), next.playing(), next.looping());
            boolean needsSeek = state.playing() != next.playing()
                    || Math.abs(expected - adjusted.positionAt(clock.serverTime(now()))) > 250;
            boolean resized = state.width() != next.width() || state.height() != next.height() || !state.key().equals(next.key());
            state = next;
            if (mediaChanged) { closeDecoder(); releaseTexture(); }
            else if (decoder != null && needsSeek) { stopSound(); decoder.seek(position(), next.playing()); }
            if (resized) { stopSound(); }
        }

        private void tick() {
            if (decoder == null) {
                decoder = new MediaDecoder(baseUrl + "/media/" + state.mediaId(), token, state.videoWidth(), state.videoHeight(), this::position, state.playing());
                epoch = decoder.epoch();
            }
            decoder.discardBefore(position() - 50);
            if (!decoder.error().isEmpty()) { stopSound(); return; }
            if (epoch != decoder.epoch()) { stopSound(); epoch = decoder.epoch(); }
            if (decoder.takeResync() && now() - lastResync > 1000) {
                stopSound(); decoder.seek(position(), state.playing()); lastResync = now();
            }
            if (decoder.ended() && state.looping() && state.playing()
                    && position() < decoder.lastDecodedMs() - Math.min(250, state.durationMs() / 2)) {
                stopSound(); decoder.seek(position(), true);
            }
            if (!state.playing()) { stopSound(); }
            else if (state.audio() && decoder.ready() && sound == null && decoder.error().isEmpty()) {
                sound = new MediaSound(state, decoder);
                sound.tick();
                Minecraft.getInstance().getSoundManager().play(sound);
            }
        }

        /** Called during extraction, at display frequency, rather than the 20-Hz client tick. */
        public Identifier texture() {
            if (decoder != null) {
                var frame = decoder.frame(position());
                if (frame != null) {
                    if (texture == null || texture.getPixels() == null || texture.getPixels().getWidth() != frame.width() || texture.getPixels().getHeight() != frame.height()) {
                        releaseTexture();
                        texture = new DynamicTexture(() -> "Cinematic video " + state.id(), new NativeImage(frame.width(), frame.height(), false));
                        Minecraft.getInstance().getTextureManager().register(textureId, texture);
                    }
                    var pixels = texture.getPixels();
                    byte[] rgba = frame.rgba();
                    for (int y = 0; y < frame.height(); y++) {
                        for (int x = 0; x < frame.width(); x++) {
                            int i = (y * frame.width() + x) * 4;
                            int color = 0xFF000000 | (rgba[i] & 255) << 16 | (rgba[i + 1] & 255) << 8 | (rgba[i + 2] & 255);
                            pixels.setPixel(x, y, color);
                        }
                    }
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
            if (!decoder.ready()) { return decoder.stage(); }
            return state.playing() ? "Playing" : "Paused";
        }

        private void stopSound() {
            if (sound != null) { Minecraft.getInstance().getSoundManager().stop(sound); sound = null; }
        }
        private void closeDecoder() { stopSound(); if (decoder != null) { decoder.close(); decoder = null; } releaseTexture(); }
        private void releaseTexture() {
            if (texture != null) { Minecraft.getInstance().getTextureManager().release(textureId); texture = null; }
        }
        @Override public void close() { closeDecoder(); releaseTexture(); }
    }
}
