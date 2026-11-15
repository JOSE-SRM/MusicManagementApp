package sangitam.desktop.model;

import java.util.Set;

/** Explicit fields to restore from a successfully reread file. Mood ratings are local measurements. */
public record DetailReset(Set<Basic> basic, Set<TrackMetadata.Field> credits, boolean customTags) {
    public enum Basic {
        TITLE("Title"), ARTIST("Artist"), ALBUM("Album"), GENRE("Genre");
        private final String label;
        Basic(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    public DetailReset { basic = Set.copyOf(basic); credits = Set.copyOf(credits); }
    public boolean empty() { return basic.isEmpty() && credits.isEmpty() && !customTags; }
}
