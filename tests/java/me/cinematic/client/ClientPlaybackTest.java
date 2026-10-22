package me.cinematic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import me.cinematic.core.PersonalAudio;
import me.cinematic.core.ScreenKey;
import me.cinematic.network.Payloads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientPlaybackTest {
    @TempDir Path temporary;

    @Test void openingDistantControlsAndReplacingNearbySnapshotsCreatesNoPlaybackResources() {
        var playback = new ClientPlayback(new PersonalAudio(temporary.resolve("audio.properties")));
        var id = UUID.randomUUID();
        var snapshot = new Payloads.Snapshot(id, new ScreenKey("minecraft:the_nether", 9000, 80, -7000),
                1, 40, 40, "a".repeat(32), "Movie", 7_200_000, 1920, 1080, true, 4,
                1000, 2500, 1500, true, false, 9, 64, "");
        playback.remote.open(snapshot);
        assertNull(playback.get(id), "Only playback entries can allocate decoders, sounds, or textures");
        assertEquals("Preparing…", playback.controlStatus(id));
        playback.snapshots(new Payloads.Snapshots(List.of()));
        assertEquals(snapshot, playback.controlState(id));
        assertEquals(1000, playback.controlPosition(id));
        assertNull(playback.get(id), "Nearby snapshot replacement must not promote remote metadata to playback");
    }
}
