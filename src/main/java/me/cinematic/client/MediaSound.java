package me.cinematic.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import me.cinematic.core.PanelGeometry;
import me.cinematic.network.Payloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/** PCM goes through Minecraft's channel engine, including master and RECORDS category gains. */
public final class MediaSound extends AbstractTickableSoundInstance {
    private final MediaDecoder decoder;
    private final java.util.UUID screen;
    private final int range;

    public MediaSound(Payloads.Snapshot state, MediaDecoder decoder) {
        super(me.cinematic.Cinematic.MEDIA_SOUND.get(), SoundSource.RECORDS, RandomSource.create());
        this.decoder = decoder;
        this.screen = state.id();
        this.range = state.audioRange();
        var center = PanelGeometry.point(state.key(), state.facing(), state.width() / 2.0, state.height() / 2.0, 0.03);
        x = center.x(); y = center.y(); z = center.z();
        attenuation = SoundInstance.Attenuation.NONE;
        relative = false;
        looping = false;
        volume = 1;
    }

    @Override public void tick() {
        var player = Minecraft.getInstance().player;
        if (player == null) { stop(); return; }
        double distance = Math.sqrt(player.distanceToSqr(x, y, z));
        volume = (float) (Math.max(0, 1 - distance / range) * CinematicClient.PLAYBACK.settings().volume(screen));
    }

    @Override public boolean canStartSilent() { return true; }

    @Override public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean loop) {
        return CompletableFuture.completedFuture(new AudioStream() {
            private volatile boolean closed;
            private long starvedAt;
            @Override public AudioFormat getFormat() { return new AudioFormat(48_000, 16, 1, true, false); }
            @Override public ByteBuffer read(int capacity) throws IOException {
                if (closed) { return ByteBuffer.allocateDirect(0); }
                int size = Math.min(capacity, 2048) & ~1;
                ByteBuffer data = ByteBuffer.allocateDirect(size);
                try {
                    int count = decoder.pcm().read(data, 50);
                    if (count == 0 && decoder.ended()) { return data.flip(); }
                    if (count == 0) {
                        if (starvedAt == 0) { starvedAt = System.nanoTime(); }
                        if (System.nanoTime() - starvedAt > 250_000_000L) { decoder.requestResync(); }
                    } else { starvedAt = 0; }
                    while (data.hasRemaining()) { data.put((byte) 0); }
                    return data.flip();
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Audio read interrupted", e); }
            }
            @Override public void close() { closed = true; }
        });
    }
}
