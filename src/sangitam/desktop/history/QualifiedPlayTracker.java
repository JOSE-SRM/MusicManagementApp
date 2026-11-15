package sangitam.desktop.history;

import sangitam.desktop.playback.PlaybackState;

import java.util.function.LongSupplier;

/** Counts real listening time, so pressing play or seeking near the end cannot inflate favorites. */
public final class QualifiedPlayTracker {
    private final LongSupplier clock;
    private long generation = Long.MIN_VALUE;
    private long lastNotice;
    private double listenedSeconds;
    private boolean playing;
    private boolean counted;

    public QualifiedPlayTracker() { this(System::nanoTime); }
    QualifiedPlayTracker(LongSupplier clock) { this.clock = clock; }

    /** Returns true exactly once after half the song or four minutes, whichever comes first. */
    public boolean observe(PlaybackState state, long currentGeneration) {
        long now = clock.getAsLong();
        if (generation != currentGeneration) {
            generation = currentGeneration;
            listenedSeconds = 0;
            playing = false;
            counted = false;
        }
        if (playing) {
            // Sleep and long UI stalls do not become hundreds of counted listening seconds.
            listenedSeconds += Math.max(0, Math.min(2, (now - lastNotice) / 1_000_000_000.0));
        }
        playing = state.status() == PlaybackState.Status.PLAYING;
        lastNotice = now;
        double duration = state.durationSeconds() > 0 ? state.durationSeconds()
            : state.track() == null ? 0 : state.track().durationSeconds();
        double threshold = Math.min(duration / 2, 240);
        if (!counted && threshold > 0 && listenedSeconds >= threshold) {
            counted = true;
            return true;
        }
        return false;
    }
}
