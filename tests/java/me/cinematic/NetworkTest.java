package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import me.cinematic.core.ScreenKey;
import me.cinematic.media.AudioTrack;
import me.cinematic.network.Payloads;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class NetworkTest {
    @Test void protocolTwoPreservesScheduledAnchorAndResolvedGlobalAudioStream() {
        var state = new Payloads.Snapshot(UUID.randomUUID(), new ScreenKey("minecraft:overworld", 1, 2, 3),
                0, 3, 2, "a".repeat(32), "Movie", 10_000, 1920, 1080, true, 4,
                1000, 2500, 1500, true, false, 9, 64, "");
        var packet = new Payloads.Snapshots(List.of(state));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            Payloads.Snapshots.CODEC.encode(buffer, packet);
            assertEquals(packet, Payloads.Snapshots.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void audioMetadataIsCorrelatedBoundedAndRoundTrips() {
        var packet = new Payloads.AudioTracks(UUID.randomUUID(), "a".repeat(32), 42,
                List.of(new AudioTrack(4, "eng", "Original", "aac", 6, true)), "");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            Payloads.AudioTracks.CODEC.encode(buffer, packet);
            assertEquals(packet, Payloads.AudioTracks.CODEC.decode(buffer));
            buffer.clear();
            buffer.writeUUID(packet.screen()); buffer.writeUtf(packet.mediaId()); buffer.writeLong(42); buffer.writeUtf("");
            buffer.writeVarInt(65);
            assertThrows(IllegalArgumentException.class, () -> Payloads.AudioTracks.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
