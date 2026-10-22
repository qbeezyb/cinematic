package me.cinematic.core;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Bounded mono S16 PCM queue. Buffers are owned copies, never FFmpeg's reusable native buffers. */
public final class PcmQueue {
    private static final int MAX_BYTES = 192_000;
    private final ArrayDeque<ByteBuffer> chunks = new ArrayDeque<>();
    private int bytes;
    private boolean closed;

    public synchronized boolean offer(byte[] pcm) {
        if (closed || pcm.length == 0 || pcm.length > MAX_BYTES - bytes) { return false; }
        chunks.add(ByteBuffer.wrap(pcm));
        bytes += pcm.length;
        notifyAll();
        return true;
    }

    public synchronized int read(ByteBuffer destination, long waitMs) throws InterruptedException {
        long deadline = System.nanoTime() + waitMs * 1_000_000;
        while (bytes == 0 && !closed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { return 0; }
            wait(Math.max(1, remaining / 1_000_000));
        }
        int start = destination.position();
        while (destination.hasRemaining() && !chunks.isEmpty()) {
            ByteBuffer source = chunks.getFirst();
            int count = Math.min(source.remaining(), destination.remaining());
            int limit = source.limit();
            source.limit(source.position() + count);
            destination.put(source);
            source.limit(limit);
            bytes -= count;
            if (!source.hasRemaining()) { chunks.removeFirst(); }
        }
        return destination.position() - start;
    }

    public synchronized void clear() { chunks.clear(); bytes = 0; }
    public synchronized void close() { closed = true; clear(); notifyAll(); }
    public synchronized int size() { return bytes; }
}
