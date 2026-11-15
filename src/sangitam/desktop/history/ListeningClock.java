package sangitam.desktop.history;

import sangitam.desktop.playback.PlaybackState;
import java.nio.file.Path;
import java.util.*;
import java.util.function.LongSupplier;

/** Wall-clock listening, independent of seeking and qualification-based play counts. EDT-owned. */
public final class ListeningClock {
    private final LongSupplier clock;
    private final Map<Path, Double> pending = new HashMap<>();
    private Path playing;
    private long last;
    public ListeningClock() { this(System::nanoTime); }
    ListeningClock(LongSupplier clock) { this.clock = clock; }
    public void observe(PlaybackState state) {
        long now = clock.getAsLong();
        if (playing != null) pending.merge(playing, Math.max(0, Math.min(2, (now - last) / 1_000_000_000d)), Double::sum);
        playing = state.status() == PlaybackState.Status.PLAYING && state.track() != null ? state.track().path() : null; last = now;
    }
    public Map<Path, Double> drain() { var result = Map.copyOf(pending); pending.clear(); return result; }
}
