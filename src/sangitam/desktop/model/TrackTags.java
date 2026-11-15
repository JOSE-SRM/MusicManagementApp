package sangitam.desktop.model;

/** Measured audio traits. These are estimates, not inferred emotions or musical genres. */
public record TrackTags(int energy, int activity, boolean analyzed, long fileSize, long modifiedMillis,
                        AudioFeatures features, Failure failure) {
    public enum Failure {
        UNAVAILABLE("The audio file is unavailable."),
        UNSUPPORTED_FORMAT("The analysis decoder does not support this audio format."),
        UNSUPPORTED_PCM("The decoded audio cannot be converted to the PCM format used for analysis."),
        DAMAGED_FLAC("The FLAC stream could not be decoded. It may be damaged or incomplete."),
        NO_AUDIO("The decoder returned no audio samples."),
        READ_ERROR("The audio file could not be read during analysis."),
        ANALYSIS_ERROR("Analysis could not measure the decoded audio.");

        private final String message;
        Failure(String message) { this.message = message; }
        public String message() { return message; }
    }

    // Bump when a decoder change warrants retrying cached failures.
    public static final int ANALYSIS_VERSION = 3;

    public TrackTags {
        energy = Math.max(0, Math.min(100, energy));
        activity = Math.max(0, Math.min(100, activity));
        features = features == null ? AudioFeatures.empty() : features;
    }
    public TrackTags(int energy, int activity, boolean analyzed, long size, long modified) {
        this(energy, activity, analyzed, size, modified, AudioFeatures.empty());
    }

    public TrackTags(int energy, int activity, boolean analyzed, long size, long modified, AudioFeatures features) {
        this(energy, activity, analyzed, size, modified, features, null);
    }

    public static TrackTags failed(long size, long modified, Failure failure) {
        return new TrackTags(50, 50, false, size, modified, AudioFeatures.empty(), failure);
    }

    public TrackTags withMoodTags(java.util.List<String> tags) {
        return new TrackTags(energy, activity, analyzed, fileSize, modifiedMillis, features.withMoodTags(tags), failure);
    }

    private static final TrackTags PENDING = new TrackTags(50, 50, false, -1, -1);
    public static TrackTags pending() { return PENDING; }

    public boolean matches(long size, long modified) {
        return fileSize >= 0 && fileSize == size && modifiedMillis == modified;
    }

    public boolean current(long size, long modified) {
        return features.current(size, modified) || (failure != null && matches(size, modified));
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
