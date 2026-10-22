package me.cinematic.core;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Two seconds of owned mono S16/48kHz PCM, timestamped in sample frames. */
public final class PcmQueue {
    private static final int MAX_BYTES = 192_000;
    private record Chunk(long startSample, ByteBuffer data) {}
    private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
    private int bytes;
    private boolean closed;
    private long nextSample;
    private long generation;

    public synchronized boolean offer(byte[] pcm) { return offerSamples(pcm, nextSample); }
    public synchronized boolean offer(byte[] pcm, long timestampMs) { return offerSamples(pcm, timestampMs * 48); }
    private boolean offerSamples(byte[] pcm, long sample) {
        if (closed || pcm.length == 0 || pcm.length > MAX_BYTES - bytes) { return false; }
        chunks.add(new Chunk(sample, ByteBuffer.wrap(pcm)));
        bytes += pcm.length;
        nextSample = sample + pcm.length / 2;
        notifyAll();
        return true;
    }

    public synchronized void trimBefore(long positionMs) {
        trimSamplesBefore(Math.max(0, positionMs) * 48);
    }

    private void trimSamplesBefore(long sample) {
        while (!chunks.isEmpty()) {
            Chunk chunk = chunks.getFirst();
            ByteBuffer data = chunk.data();
            long skip = Math.min(data.remaining(), Math.max(0, sample - chunk.startSample() - data.position() / 2) * 2);
            data.position(data.position() + (int) skip);
            bytes -= (int) skip;
            if (data.hasRemaining()) { break; }
            chunks.removeFirst();
        }
    }

    /** Fill gaps with silence, trim late packets, and advance independently of video. */
    public synchronized int readAt(ByteBuffer destination, long positionMs, long waitMs) throws InterruptedException {
        return readAt(destination, positionMs, waitMs, generation);
    }

    public synchronized int readAt(ByteBuffer destination, long positionMs, long waitMs, long expectedGeneration) throws InterruptedException {
        if (generation != expectedGeneration) { return 0; }
        trimBefore(positionMs);
        awaitData(waitMs);
        if (generation != expectedGeneration) { return 0; }
        trimBefore(positionMs);
        long sample = positionMs * 48;
        int start = destination.position();
        while (destination.remaining() >= 2 && !chunks.isEmpty()) {
            trimSamplesBefore(sample);
            if (chunks.isEmpty()) { break; }
            Chunk chunk = chunks.getFirst();
            long sourceSample = chunk.startSample() + chunk.data().position() / 2;
            int gap = (int) Math.min(destination.remaining() / 2, Math.max(0, sourceSample - sample));
            for (int i = 0; i < gap; i++) { destination.putShort((short) 0); }
            sample += gap;
            int count = transfer(destination);
            sample += count / 2;
        }
        return destination.position() - start;
    }

    public synchronized int read(ByteBuffer destination, long waitMs) throws InterruptedException {
        awaitData(waitMs);
        int start = destination.position();
        while (destination.hasRemaining() && !chunks.isEmpty()) { transfer(destination); }
        return destination.position() - start;
    }

    private void awaitData(long waitMs) throws InterruptedException {
        long deadline = System.nanoTime() + waitMs * 1_000_000;
        while (bytes == 0 && !closed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { return; }
            wait(Math.max(1, remaining / 1_000_000));
        }
    }

    private int transfer(ByteBuffer destination) {
        ByteBuffer source = chunks.getFirst().data();
        int count = Math.min(source.remaining(), destination.remaining());
        int limit = source.limit();
        source.limit(source.position() + count);
        destination.put(source);
        source.limit(limit);
        bytes -= count;
        if (!source.hasRemaining()) { chunks.removeFirst(); }
        return count;
    }

    public synchronized long generation() { return generation; }
    public synchronized void clear() { chunks.clear(); bytes = 0; nextSample = 0; generation++; notifyAll(); }
    public synchronized void close() { closed = true; clear(); notifyAll(); }
    public synchronized int size() { return bytes; }
}
