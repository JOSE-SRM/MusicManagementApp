package sangitam.desktop.model;

import java.util.Objects;
import java.util.Set;

/** A saved query, rather than a manually ordered list of song paths. All enabled conditions must match. */
public record SmartPlaylistRule(String text, String genre, Set<String> tags, Range energy, Range activity,
                                int minimumPlays, boolean availableOnly, Order order, int limit) {
    public enum Order {
        RECENT("Recently added or played"), ALPHABETICAL("Alphabetical"), MOST_PLAYED("Most played"), LEAST_PLAYED("Least played");
        private final String label;
        Order(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public record Range(int minimum, int maximum) {
        public Range {
            if (minimum < 0 || maximum > 100 || minimum > maximum)
                throw new IllegalArgumentException("Rating ranges must run from a lower to a higher value within 0–100.");
        }
    }
    public SmartPlaylistRule {
        text = Objects.requireNonNull(text).strip(); genre = Objects.requireNonNull(genre).strip();
        tags = TrackMetadata.normalizeTags(Objects.requireNonNull(tags));
        Objects.requireNonNull(order);
        if (text.length() > 500 || genre.length() > 128) throw new IllegalArgumentException("Search text or genre is too long.");
        if (tags.size() > 16) throw new IllegalArgumentException("Use at most 16 required tags in one rule.");
        if (minimumPlays < 0) throw new IllegalArgumentException("Minimum plays cannot be negative.");
        if (limit < 1 || limit > 5000) throw new IllegalArgumentException("Choose a track limit between 1 and 5000.");
    }
    public static SmartPlaylistRule defaults() { return new SmartPlaylistRule("", "", Set.of(), null, null, 0, true, Order.RECENT, 100); }
}
