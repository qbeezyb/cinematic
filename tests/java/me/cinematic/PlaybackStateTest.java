package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import me.cinematic.core.PcmQueue;
import me.cinematic.core.ScreenKey;
import me.cinematic.core.ScreenSession;
import me.cinematic.core.SessionStore;
import me.cinematic.core.Timeline;
import me.cinematic.media.AudioTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaybackStateTest {
    @TempDir Path temporary;

    @Test void scheduledPlaySeekAndResumeHoldTheirRequestedPosition() {
        var timeline = new Timeline(10_000, 2000, 0, false, false).preparePlay(100);
        assertEquals(1600, timeline.anchorMs());
        assertTrue(timeline.preparingAt(1599));
        assertEquals(2000, timeline.positionAt(1599));
        assertEquals(2100, timeline.positionAt(1700));
        var seek = timeline.prepareSeek(5000, 1800);
        assertEquals(5000, seek.positionAt(3299));
        assertEquals(5100, seek.positionAt(3400));
        // Snapshots must retain base position and anchor, even during preparation.
        var snapshot = new Timeline(seek.durationMs(), seek.positionMs(), seek.anchorMs(), seek.playing(), seek.looping());
        assertEquals(seek.positionAt(3500), snapshot.positionAt(3500));
        assertEquals(seek.anchorMs(), seek.loop(true, 1900).anchorMs());
        assertEquals(5000, seek.pause(2000).positionAt(9000));
        assertEquals(0, seek.stop(2000).positionAt(9000));
        assertFalse(seek.stop(2000).playing());
        var pausedSeek = seek.pause(2000).prepareSeek(300, 2100);
        assertFalse(pausedSeek.playing()); assertEquals(300, pausedSeek.positionAt(9000));
        assertEquals(100, new Timeline(1000, 900, 100, true, true).positionAt(300));
    }

    @Test void selectionPreservesIntentAndSurvivesCopiesAndVersionTwoPersistence() throws Exception {
        var screen = new ScreenSession(UUID.randomUUID(), new ScreenKey("minecraft:overworld", 0, 0, 0), 0);
        screen.select("a".repeat(32), 10_000, 0);
        screen.updateTimeline(screen.timeline().play(0));
        screen.selectAudio(7, 1000);
        assertEquals(1000, screen.timeline().positionAt(2000));
        assertEquals(2500, screen.timeline().anchorMs());
        assertTrue(screen.timeline().playing());
        var copy = new ScreenSession(UUID.randomUUID(), screen.key(), 0, 2, 2, screen.mediaId(), screen.timeline(), screen.revision(), screen.audioStream());
        copy.reshape(new ScreenKey("minecraft:overworld", 1, 0, 0), 3, 2);
        assertEquals(7, copy.audioStream()); assertEquals(screen.timeline(), copy.timeline());
        var store = new SessionStore(temporary.resolve("sessions.dat"));
        store.save(List.of(copy), 2000);
        var restored = store.load(9000).getFirst();
        assertEquals(7, restored.audioStream()); assertFalse(restored.timeline().playing());
        assertEquals(1000, restored.timeline().positionAt(50_000));
        restored.selectAudio(3, 10_000);
        assertFalse(restored.timeline().playing());
        restored.select("b".repeat(32), 2000, 11_000);
        assertEquals(-1, restored.audioStream());
    }

    @Test void versionOneLoadsPausedWithDefaultAudio() throws Exception {
        Path file = temporary.resolve("v1.dat");
        UUID id = UUID.randomUUID();
        try (var out = new DataOutputStream(Files.newOutputStream(file))) {
            out.writeInt(0x43494E45); out.writeInt(1); out.writeInt(1);
            out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
            out.writeUTF("minecraft:overworld"); out.writeInt(1); out.writeInt(2); out.writeInt(3);
            out.writeInt(0); out.writeInt(2); out.writeInt(3); out.writeUTF("a".repeat(32));
            out.writeLong(10_000); out.writeLong(4000); out.writeBoolean(true); out.writeLong(9);
        }
        var restored = new SessionStore(file).load(1000).getFirst();
        assertEquals(id, restored.id()); assertEquals(-1, restored.audioStream());
        assertEquals(4000, restored.timeline().positionAt(100_000)); assertFalse(restored.timeline().playing());
        assertTrue(restored.timeline().looping()); assertEquals(9, restored.revision());
    }

    @Test void timestampedPcmTrimsStaleSamplesAndRecoversAfterSilence() throws Exception {
        var queue = new PcmQueue();
        byte[] tone = new byte[9600]; java.util.Arrays.fill(tone, (byte) 7);
        assertTrue(queue.offer(tone, 100));
        var output = ByteBuffer.allocate(9600);
        assertEquals(9600, queue.readAt(output, 50, 0));
        assertEquals(0, output.get(4799)); assertEquals(7, output.get(4800));
        output.clear(); assertEquals(4800, queue.readAt(output, 150, 0));
        assertEquals(0, queue.readAt(ByteBuffer.allocate(9600), 200, 0));
        queue.offer(tone, 200); // Packet arrives after a 100ms starvation interval.
        queue.offer(tone, 300);
        output.clear(); assertEquals(9600, queue.readAt(output, 300, 0));
        assertEquals(7, output.get(0)); assertEquals(0, queue.size());
        queue.offer(tone, 500); queue.clear();
        assertEquals(0, queue.readAt(ByteBuffer.allocate(9600), 500, 0));
    }

    @Test void retiredSoundReadsCannotConsumeAudioFromANewerSeek() throws Exception {
        var queue = new PcmQueue();
        long retired = queue.generation();
        queue.offer(new byte[9600], 0);
        queue.clear();
        byte[] selected = new byte[9600]; java.util.Arrays.fill(selected, (byte) 9);
        queue.offer(selected, 1000);
        assertEquals(0, queue.readAt(ByteBuffer.allocate(9600), 1000, 0, retired));
        assertEquals(9600, queue.size(), "A retired sound callback must leave the new seek's PCM intact");
        var output = ByteBuffer.allocate(9600);
        assertEquals(9600, queue.readAt(output, 1000, 0, queue.generation()));
        assertEquals(9, output.get(0));
    }

    @Test void languageLabelsDefaultFallbackAndMetadataAreBounded() {
        var german = new AudioTrack(1, "deu", "Dub", "flac", 2, false);
        var english = new AudioTrack(4, "eng", "Original", "aac", 6, true);
        var duplicate = new AudioTrack(6, "ger", "Commentary", "aac", 2, false);
        var missing = new AudioTrack(7, "und", "", "", 0, false);
        var tracks = List.of(german, english, duplicate, missing);
        assertEquals(4, AudioTrack.defaultStream(tracks));
        assertEquals(1, AudioTrack.defaultStream(List.of(german, duplicate)));
        assertEquals(-1, AudioTrack.defaultStream(List.of()));
        assertEquals("German", german.languageName()); assertEquals("English", english.label(1, tracks));
        assertTrue(german.label(0, tracks).contains("Dub"));
        assertTrue(duplicate.label(2, tracks).contains("Commentary"));
        assertEquals("Track 4", missing.label(3, tracks));
        assertEquals("Track 1", missing.label(0, List.of(missing, new AudioTrack(8, "", "", "aac", 1, false))));
        assertEquals(128, new AudioTrack(1, "", "x".repeat(1000), "", 1, false).title().length());
        assertThrows(IllegalArgumentException.class, () -> AudioTrack.boundedTracks(List.of(german, german)));
        assertThrows(IllegalArgumentException.class, () -> AudioTrack.boundedTracks(java.util.Collections.nCopies(65, german)));
    }
}
