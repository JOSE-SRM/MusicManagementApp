package sangitam.desktop.model;

import java.util.*;

/** Optional metadata, kept separate from the track's file identity and measured mood scores. */
public record TrackMetadata(Map<Field, Value> fields, Set<String> tags, int bitrateKbps, int sampleRateHz,
                            int bitsPerSample, String channels, boolean embeddedArtwork, int extractionVersion) {
    public static final int CURRENT_VERSION = 1;
    public enum Field {
        COMPOSER("Composer"), CONDUCTOR("Conductor"), SOLOIST("Soloist"), PERFORMERS("Performers"), ORCHESTRA("Orchestra / ensemble"),
        ALBUM_ARTIST("Album artist"), WORK("Work"), MOVEMENT("Movement"), KEY("Musical key"),
        OPUS("Opus / catalogue"), PERIOD("Period"), FORM("Form"), INSTRUMENTATION("Instrumentation");
        private final String label;
        Field(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public enum Origin { EMBEDDED, TITLE_HINT, FOLDER_HINT, USER }
    public record Value(String text, Origin origin) {
        public Value { text = Objects.requireNonNull(text).strip(); Objects.requireNonNull(origin); }
    }
    public TrackMetadata {
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        tags = normalizeTags(tags == null ? Set.of() : tags);
        bitrateKbps = Math.max(0, bitrateKbps);
        sampleRateHz = Math.max(0, sampleRateHz);
        bitsPerSample = Math.max(0, bitsPerSample);
        channels = channels == null ? "" : channels.strip();
        extractionVersion = Math.max(0, extractionVersion);
    }
    public static TrackMetadata empty() { return new TrackMetadata(Map.of(), Set.of(), 0, 0, 0, "", false, 0); }
    public String text(Field field) { return fields.containsKey(field) ? fields.get(field).text() : ""; }
    public TrackMetadata edit(Map<Field, String> changes, Set<String> newTags) {
        var updated = new EnumMap<Field, Value>(Field.class);
        updated.putAll(fields);
        changes.forEach((key, text) -> updated.put(key, new Value(text, Origin.USER)));
        return new TrackMetadata(updated, newTags == null ? tags : newTags, bitrateKbps, sampleRateHz,
            bitsPerSample, channels, embeddedArtwork, extractionVersion);
    }
    public static Set<String> parseTags(String input) { return normalizeTags(Arrays.asList(input.split(",", -1))); }
    public static Set<String> normalizeTags(Collection<String> input) {
        var result = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (String tag : input) {
            String value = Objects.requireNonNull(tag).strip().replaceAll("\\s+", " ");
            if (value.length() > 80) throw new IllegalArgumentException("Each tag can contain at most 80 characters.");
            if (!value.isBlank()) result.add(value);
        }
        if (result.size() > 128) throw new IllegalArgumentException("Use at most 128 tags per track.");
        return Collections.unmodifiableSet(result);
    }
    public String searchableText() {
        return (String.join(" ", fields.values().stream().map(Value::text).toList()) + " " + String.join(" ", tags))
            .toLowerCase(Locale.ROOT);
    }
}
