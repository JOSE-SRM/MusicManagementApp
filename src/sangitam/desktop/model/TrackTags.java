package sangitam.desktop.model;

/** Measured audio traits. These are estimates, not inferred emotions or musical genres. */
public record TrackTags(int energy, int activity, boolean analyzed, long fileSize, long modifiedMillis) {
    /** Increment when the signal calculations change so saved audio profiles are refreshed. */
    public static final int ANALYSIS_VERSION = 3;

    public TrackTags {
        energy = Math.max(0, Math.min(100, energy));
        activity = Math.max(0, Math.min(100, activity));
    }

    public static TrackTags pending() { return new TrackTags(50, 50, false, -1, -1); }

    public boolean matches(long size, long modified) {
        return fileSize >= 0 && fileSize == size && modifiedMillis == modified;
    }

    public String energyLabel() {
        if (!analyzed) return "Not analyzed";
        return energy < 34 ? "Calm" : energy < 67 ? "Balanced" : "Charged";
    }

    public String activityLabel() {
        if (!analyzed) return "Unknown rhythm";
        return activity < 34 ? "Low movement" : activity < 67 ? "Steady movement" : "Busy movement";
    }

    /** Neutral wording keeps a signal estimate from sounding like a mood classification. */
    public String energyEstimate() {
        return analyzed ? "Estimated energy " + energy + "/100" : "Energy not measured";
    }

    public String movementEstimate() {
        return analyzed ? "Estimated movement " + activity + "/100" : "Movement not measured";
    }
}
