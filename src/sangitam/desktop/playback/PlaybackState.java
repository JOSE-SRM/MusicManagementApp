package sangitam.desktop.playback;

import sangitam.desktop.model.Track;

/** Immutable transport snapshot passed from the audio thread to the Swing player bar. */
public record PlaybackState(Track track, Status status, double positionSeconds,
                            double durationSeconds, String error, boolean seekable) {
    public enum Status { IDLE, LOADING, PLAYING, PAUSED, STOPPED, ENDED, ERROR }

    public PlaybackState(Track track, Status status, double positionSeconds, double durationSeconds, String error) {
        this(track, status, positionSeconds, durationSeconds, error,
            durationSeconds > 0 && (status == Status.PLAYING || status == Status.PAUSED));
    }

    public static PlaybackState idle() { return new PlaybackState(null, Status.IDLE, 0, 0, null, false); }
}
