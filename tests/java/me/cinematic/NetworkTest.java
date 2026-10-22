package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import me.cinematic.client.RemoteTarget;

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
    @Test void protocolThreePreservesScheduledAnchorAndResolvedGlobalAudioStream() {
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

    private static Payloads.Snapshot state(UUID id, long revision, long serverTime) {
        return new Payloads.Snapshot(id, new ScreenKey("minecraft:the_nether", 9000, 80, -7000),
                1, 40, 40, "a".repeat(32), "Movie", 7_200_000, 1920, 1080, true, 4,
                1000, 2500, serverTime, true, false, revision, 64, "");
    }

    @Test void remoteOpeningListsAndIndividualResponsesRoundTripWithCorrelation() {
        var state = state(UUID.randomUUID(), 9, 1500);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (var open : List.of(new Payloads.Open(state), new Payloads.Open(null))) {
                Payloads.Open.CODEC.encode(buffer, open); assertEquals(open, Payloads.Open.CODEC.decode(buffer)); buffer.clear();
            }
            var page = new Payloads.ScreenList(42, 20, 41, List.of(state), "");
            Payloads.ScreenList.CODEC.encode(buffer, page); assertEquals(page, Payloads.ScreenList.CODEC.decode(buffer)); buffer.clear();
            for (var response : List.of(new Payloads.ScreenState(state.id(), 43, state, ""),
                    new Payloads.ScreenState(state.id(), 44, null, "Removed screen"))) {
                Payloads.ScreenState.CODEC.encode(buffer, response); assertEquals(response, Payloads.ScreenState.CODEC.decode(buffer)); buffer.clear();
            }
            var request = new Payloads.Request(Payloads.RequestKind.LIBRARY, state.id(), 20, 45, "movie", 9);
            Payloads.Request.CODEC.encode(buffer, request); assertEquals(request, Payloads.Request.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void remoteListsRejectOversizedOrNegativeCountsAndMismatchedIdentities() {
        var state = state(UUID.randomUUID(), 9, 1500);
        assertThrows(IllegalArgumentException.class, () -> new Payloads.ScreenList(1, 0, 21, java.util.Collections.nCopies(21, state), ""));
        assertThrows(IllegalArgumentException.class, () -> new Payloads.ScreenState(UUID.randomUUID(), 1, state, ""));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            for (int count : new int[] {-1, 21, Integer.MAX_VALUE}) {
                buffer.clear(); buffer.writeLong(42); buffer.writeVarInt(0); buffer.writeVarInt(41); buffer.writeUtf(""); buffer.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> Payloads.ScreenList.CODEC.decode(buffer));
            }
        } finally { buffer.release(); }
    }

    @Test void remoteStateRejectsSupersededResponsesOlderRevisionsAndResponsesAfterClosing() {
        var remote = new RemoteTarget(); var id = UUID.randomUUID(); var initial = state(id, 9, 1500);
        remote.open(initial);
        long oldRequest = remote.request(); long current = remote.request();
        assertFalse(remote.accept(new Payloads.ScreenState(id, oldRequest, state(id, 10, 1600), "")));
        assertFalse(remote.accept(new Payloads.ScreenState(UUID.randomUUID(), current, null, "")));
        assertFalse(remote.accept(new Payloads.ScreenState(id, current, state(id, 8, 1600), "")));
        assertFalse(remote.accept(new Payloads.ScreenState(id, current, state(id, 9, 1400), "")));
        assertTrue(remote.accept(new Payloads.ScreenState(id, current, state(id, 10, 1600), "")));
        remote.invalidate();
        assertFalse(remote.accept(new Payloads.ScreenState(id, current, null, "Removed")));
        long removal = remote.request();
        assertTrue(remote.accept(new Payloads.ScreenState(id, removal, null, "Removed")));
        assertNull(remote.state());
    }

    @Test void remoteMetadataSurvivesNearbyReplacementWithoutCreatingPlaybackEntries() {
        var remote = new RemoteTarget(); var id = UUID.randomUUID(); var initial = state(id, 9, 1500);
        remote.open(initial);
        assertEquals(initial, remote.newest(id, null), "Empty nearby snapshots must leave the remote target available");
        assertEquals(initial, remote.newest(id, state(id, 8, 1600)));
        var newer = state(id, 10, 1600);
        assertEquals(newer, remote.newest(id, newer));
        remote.observe(newer);
        assertEquals(newer, remote.newest(id, null), "The latest nearby state survives leaving its dimension or range");
        remote.observe(initial);
        assertEquals(newer, remote.state());
        assertNull(remote.newest(UUID.randomUUID(), null));
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
