package sangitam.desktop.model;

/** Measured audio traits. These are estimates, not inferred emotions or musical genres. */
public record TrackTags(int energy, int activity, boolean analyzed, long fileSize, long modifiedMillis) {
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
}
