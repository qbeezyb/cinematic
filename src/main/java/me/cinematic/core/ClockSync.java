package me.cinematic.core;

/** NTP midpoint offset; prefer the lowest-latency sample in a rolling 30-second interval. */
public final class ClockSync {
    private volatile long offsetMs;
    private long bestRtt = Long.MAX_VALUE;
    private long sampledAt;

    public void sample(long sentMs, long receivedMs, long serverMs) {
        long rtt = receivedMs - sentMs;
        if (rtt < 0 || rtt > 10_000) { return; }
        if (rtt <= bestRtt || receivedMs - sampledAt > 30_000) {
            offsetMs = serverMs - sentMs - rtt / 2;
            bestRtt = rtt;
            sampledAt = receivedMs;
        }
    }

    public long serverTime(long localMs) { return localMs + offsetMs; }
    public void reset() { offsetMs = 0; bestRtt = Long.MAX_VALUE; sampledAt = 0; }
}
