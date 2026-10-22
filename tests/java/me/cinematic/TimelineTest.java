package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import me.cinematic.core.ClockSync;
import me.cinematic.core.ControlPolicy;
import me.cinematic.core.PanelGeometry;
import me.cinematic.core.ScreenKey;
import me.cinematic.core.ScreenSession;
import me.cinematic.core.SessionStore;
import me.cinematic.core.Timeline;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimelineTest {
    @TempDir Path temporary;

    @Test void playbackUsesServerElapsedTimeAndPauseFreezesIt() {
        Timeline playing = new Timeline(120_000, 10_000, 500, true, false);
        assertEquals(12_500, playing.positionAt(3000));
        assertEquals(12_500, playing.pause(3000).positionAt(60_000));
        assertEquals(120_000, playing.positionAt(999_999));
        assertFalse(playing.playingAt(999_999));
        assertEquals(0, playing.pause(999_999).play(999_999).positionAt(999_999));
    }

    @Test void loopWrapsAcrossMultipleCyclesAndSeeksClamp() {
        Timeline loop = new Timeline(1000, 900, 100, true, true);
        assertEquals(300, loop.positionAt(3500));
        assertEquals(0, loop.seek(-100, 500).positionAt(500));
        assertEquals(1000, loop.loop(false, 500).seek(90_000, 500).positionAt(500));
        assertEquals(0, new Timeline(0, 0, 0, true, true).positionAt(100));
        assertFalse(new Timeline(0, 0, 0, true, true).playingAt(100));
    }

    @Test void permissionsRequireHandDistanceDimensionAndExactRevision() {
        ScreenSession screen = new ScreenSession(UUID.randomUUID(), new ScreenKey("minecraft:overworld", 0, 0, 0), 0);
        assertDoesNotThrow(() -> ControlPolicy.check(screen, "minecraft:overworld", 17, .5, .5, true, 0));
        assertThrows(IllegalArgumentException.class, () -> ControlPolicy.check(screen, "minecraft:overworld", 17.01, .5, .5, true, 0));
        assertThrows(IllegalArgumentException.class, () -> ControlPolicy.check(screen, "minecraft:the_nether", .5, .5, .5, true, 0));
        assertThrows(IllegalArgumentException.class, () -> ControlPolicy.check(screen, "minecraft:overworld", .5, .5, .5, false, 0));
        assertThrows(IllegalArgumentException.class, () -> ControlPolicy.check(screen, "minecraft:overworld", .5, .5, .5, true, 1));
        assertThrows(IllegalArgumentException.class, () -> screen.resize(0, 41));
        assertEquals(0, screen.revision());
        screen.resize(40, 40);
        assertEquals(1, screen.revision());
    }

    @Test void unloadedSessionsContinueAndRestartRestoresPausedPosition() throws Exception {
        ScreenSession screen = new ScreenSession(UUID.randomUUID(), new ScreenKey("minecraft:overworld", 10, 80, 20), 3);
        screen.select("a".repeat(32), 120_000, 0);
        screen.resize(32, 18);
        screen.updateTimeline(screen.timeline().play(100));
        SessionStore store = new SessionStore(temporary.resolve("data/sessions.dat"));
        store.save(List.of(screen), 10_100);
        ScreenSession loaded = store.load(50_000).getFirst();
        assertEquals(screen.id(), loaded.id());
        assertEquals(32, loaded.width());
        assertEquals(18, loaded.height());
        assertEquals(10_000, loaded.timeline().positionAt(90_000));
        assertFalse(loaded.timeline().playing());
        assertEquals(screen.revision(), loaded.revision());
    }

    @Test void malformedPersistenceDoesNotReplaceWorldState() throws Exception {
        Path file = temporary.resolve("sessions.dat");
        Files.write(file, new byte[] {0, 0, 0, 0});
        assertThrows(java.io.IOException.class, () -> new SessionStore(file).load(0));
    }

    @Test void geometryPreservesAspectRatioAndUsesAllFourFacings() {
        var fit = PanelGeometry.fit(10, 10, 1920, 1080);
        assertEquals(10, fit.width(), .0001);
        assertEquals(5.625, fit.height(), .0001);
        assertEquals(2.1875, fit.y(), .0001);
        var key = new ScreenKey("minecraft:overworld", 0, 0, 0);
        assertEquals(40, PanelGeometry.point(key, 0, 40, 0, 0).x());
        assertEquals(40, PanelGeometry.point(key, 1, 40, 0, 0).z());
        assertEquals(-39, PanelGeometry.point(key, 2, 40, 40, 0).x());
        assertEquals(-39, PanelGeometry.point(key, 3, 40, 40, 0).z());
    }

    @Test void clockChoosesLowestLatencySampleAndRejectsInvalidSamples() {
        ClockSync clock = new ClockSync();
        clock.sample(1000, 1100, 2050);
        assertEquals(2100, clock.serverTime(1100));
        clock.sample(2000, 2400, 9000);
        assertEquals(3400, clock.serverTime(2400));
        clock.sample(3000, 3020, 4010);
        assertEquals(4020, clock.serverTime(3020));
        clock.sample(5000, 4999, 0);
        assertEquals(4020, clock.serverTime(3020));
    }
}
