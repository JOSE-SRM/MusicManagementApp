package sangitam.desktop.playback;

import sangitam.desktop.model.Track;

/** Immutable transport snapshot passed from the audio thread to the Swing player bar. */
public record PlaybackState(Track track, Status status, double positionSeconds,
                            double durationSeconds, String error) {
    public enum Status { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

    public static PlaybackState idle() { return new PlaybackState(null, Status.IDLE, 0, 0, null); }
}
