package me.cinematic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.UUID;
import me.cinematic.core.PcmQueue;
import me.cinematic.core.PersonalAudio;
import me.cinematic.core.SeekMailbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientLogicTest {
    @TempDir Path temporary;

    @Test void personalVolumePersistsPerServerAndScreenWithoutChangingMuteLevel() throws Exception {
        Path file = temporary.resolve("audio.properties");
        UUID screen = UUID.randomUUID();
        var settings = new PersonalAudio(file);
        settings.server("one.example:25565");
        settings.level(screen, .35); settings.muted(screen, true); settings.flush();
        var restored = new PersonalAudio(file); restored.load(); restored.server("one.example:25565");
        assertEquals(0, restored.volume(screen));
        assertEquals(.35, restored.level(screen), .0001);
        restored.muted(screen, false); assertEquals(.35, restored.volume(screen), .0001);
        restored.server("two.example:25565"); assertEquals(1, restored.volume(screen));
        assertThrows(IllegalArgumentException.class, () -> restored.level(screen, Double.NaN));
    }

    @Test void boundedPcmQueueRetainsChunkBoundariesAndWakesOnClose() throws Exception {
        PcmQueue queue = new PcmQueue();
        assertFalse(queue.offer(new byte[0]));
        assertTrue(queue.offer(new byte[] {1, 2, 3, 4}));
        assertTrue(queue.offer(new byte[] {5, 6}));
        ByteBuffer first = ByteBuffer.allocate(3);
        assertEquals(3, queue.read(first, 0));
        assertEquals(3, queue.size());
        ByteBuffer rest = ByteBuffer.allocate(5); assertEquals(3, queue.read(rest, 0));
        assertEquals(4, rest.get(0)); assertEquals(6, rest.get(2));
        assertFalse(queue.offer(new byte[192_001]));
        assertTrue(queue.offer(new byte[192_000]));
        assertFalse(queue.offer(new byte[2]));
        queue.close(); assertEquals(0, queue.read(ByteBuffer.allocate(10), 100));
        assertFalse(queue.offer(new byte[2]));
    }

    @Test void rapidSeeksCoalesceAndInvalidateInflightFramesIncludingAfterClose() {
        var mailbox = new SeekMailbox();
        var frames = new java.util.ArrayList<Long>();
        assertTrue(mailbox.publish(0, () -> frames.add(0L)));
        mailbox.request(10_000, true, frames::clear);
        var first = mailbox.take();
        mailbox.request(20_000, true, frames::clear);
        mailbox.request(30_000, false, frames::clear);
        assertFalse(mailbox.publish(first.generation(), () -> frames.add(10_000L)));
        var latest = mailbox.take();
        assertEquals(30_000, latest.targetMs());
        assertFalse(latest.playing());
        assertEquals(null, mailbox.take());
        assertTrue(mailbox.publish(latest.generation(), () -> frames.add(latest.targetMs())));
        assertEquals(java.util.List.of(30_000L), frames);
        mailbox.close(frames::clear);
        assertFalse(mailbox.publish(latest.generation(), () -> frames.add(30_000L)));
        mailbox.request(40_000, true, () -> { throw new AssertionError("Closed decoder accepted a seek"); });
        assertEquals(null, mailbox.take());
        assertTrue(frames.isEmpty());
    }
}
