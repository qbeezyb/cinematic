package me.cinematic.core;

/** Coalesces seeks and prevents an in-flight decode from publishing into a newer timeline. */
public final class SeekMailbox {
    public record Request(long generation, long targetMs, boolean playing) {}

    private long generation;
    private Request pending;
    private boolean closed;

    public synchronized void request(long targetMs, boolean playing, Runnable invalidate) {
        if (closed) { return; }
        pending = new Request(++generation, Math.max(0, targetMs), playing);
        invalidate.run();
    }

    public synchronized Request take() {
        Request request = pending;
        pending = null;
        return request;
    }

    /** Callbacks only copy/publish Java state; never perform native work while holding this lock. */
    public synchronized boolean publish(long expectedGeneration, Runnable publish) {
        if (closed || generation != expectedGeneration) { return false; }
        publish.run();
        return true;
    }

    public synchronized void close(Runnable invalidate) {
        closed = true;
        pending = null;
        generation++;
        invalidate.run();
    }
}
