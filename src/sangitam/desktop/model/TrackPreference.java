package sangitam.desktop.model;

/** Combines the explicit rating, one active like, and bounded evidence from qualified local plays. */
public final class TrackPreference {
    public static final int AVOID = -2;

    private TrackPreference() { }

    public static double score(int rating, boolean liked, int plays) {
        int count = Math.max(0, plays);
        return 20d * rating + (liked ? 12d : 0d) + 8d * count / (count + 12d);
    }
}
