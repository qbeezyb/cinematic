package me.cinematic.client;

import java.util.UUID;
import me.cinematic.network.Payloads;

/** One metadata-only target, independent of nearby playback entries and their resource lifecycle. */
public final class RemoteTarget {
    private static long sequence;
    private Payloads.Snapshot state;
    private long requestId;

    public static long nextRequest() { return ++sequence; }
    public Payloads.Snapshot state() { return state; }
    public void open(Payloads.Snapshot initial) { state = initial; invalidate(); }
    public void invalidate() { requestId = nextRequest(); }
    public long request() { invalidate(); return requestId; }

    public boolean accept(Payloads.ScreenState response) {
        if (state == null || response.requestId() != requestId || !response.screen().equals(state.id())) { return false; }
        if (response.state() != null && older(response.state(), state)) { return false; }
        state = response.state();
        return true;
    }

    public void observe(Payloads.Snapshot snapshot) {
        if (state != null && state.id().equals(snapshot.id()) && !older(snapshot, state)) { state = snapshot; }
    }

    public Payloads.Snapshot newest(UUID id, Payloads.Snapshot nearby) {
        if (state == null || !state.id().equals(id)) { return nearby; }
        return nearby == null || older(nearby, state) ? state : nearby;
    }

    private static boolean older(Payloads.Snapshot a, Payloads.Snapshot b) {
        return a.revision() < b.revision() || a.revision() == b.revision() && a.serverTimeMs() < b.serverTimeMs();
    }
}
