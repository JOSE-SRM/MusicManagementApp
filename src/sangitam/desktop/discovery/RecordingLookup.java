package sangitam.desktop.discovery;

import sangitam.desktop.model.RecordingCandidate;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

/** Builds text-only searches and checks evidence for a particular performance. */
public final class RecordingLookup {
    public record Evidence(boolean title, boolean credit, boolean album, boolean duration,
                           boolean corroborated, int rank) {
        public String summary() {
            return (title ? "Title/work agrees" : "Title/work unconfirmed")
                + " · " + (credit ? "Credit agrees" : "Credit unconfirmed")
                + (album ? " · Release agrees" : "")
                + " · " + (duration ? "Duration agrees" : "Duration unconfirmed")
                + (corroborated ? " · Can fill missing fields" : " · Link only");
        }
    }

    private RecordingLookup() { }

    public static boolean missingTitle(String title) {
        return normalize(title).matches("(?:|tmp|untitled|track ?[0-9]+|[0-9]+)");
    }

    public static boolean missingLabel(String label) {
        return normalize(label).matches("(?:|unknown|unknown artist|unknown album)");
    }

    public static String initialTitle(Track track) {
        if (!missingTitle(track.title())) return track.title();
        return track.metadata().text(Field.WORK);
    }

    /** Only field values shown to the user enter the outbound query. */
    public static String query(String text, Track track, boolean includeKnownDetails) {
        var terms = new ArrayList<String>();
        term(terms, "recording", text);
        if (terms.isEmpty()) throw new IllegalArgumentException("Enter a recording or work title to search.");
        if (!includeKnownDetails) return terms.getFirst();
        var metadata = track.metadata();
        if (!missingLabel(track.artist())) term(terms, "artist", track.artist());
        term(terms, "artist", metadata.text(Field.COMPOSER));
        term(terms, "artist", metadata.text(Field.SOLOIST));
        term(terms, "artist", metadata.text(Field.PERFORMERS));
        if (!missingLabel(track.album())) term(terms, "release", track.album());
        term(terms, "recording", metadata.text(Field.WORK));
        term(terms, "recording", metadata.text(Field.OPUS));
        term(terms, "recording", metadata.text(Field.MOVEMENT));
        if (track.durationSeconds() > 0) {
            long center = Math.round(track.durationSeconds() / 2);
            terms.add("qdur:[" + Math.max(0, center - 3) + " TO " + (center + 3) + "]");
        }
        return terms.size() == 1 ? terms.getFirst()
            : "+" + terms.getFirst() + " " + String.join(" ", terms.subList(1, terms.size()));
    }

    private static void term(ArrayList<String> terms, String field, String value) {
        String text = value.replaceAll("\\p{Cntrl}", " ").strip();
        if (text.isBlank()) return;
        if (text.codePointCount(0, text.length()) > 150) text = text.substring(0, text.offsetByCodePoints(0, 150));
        String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
        String clause = field + ":\"" + escaped + "\"";
        if (!terms.contains(clause)) terms.add(clause);
    }

    public static Evidence compare(Track track, RecordingCandidate candidate) {
        var metadata = track.metadata();
        String title = normalize(candidate.title());
        String work = normalize(metadata.text(Field.WORK));
        String catalogue = normalize(metadata.text(Field.OPUS));
        String movement = normalize(metadata.text(Field.MOVEMENT));
        boolean titleMatch = !missingTitle(track.title()) && normalize(track.title()).equals(title);
        boolean workMatch = work.length() >= 8 && contains(title, work)
            && (catalogue.isEmpty() || contains(title, catalogue) || contains(work, catalogue))
            && (movement.isEmpty() || contains(title, movement));
        String credit = normalize(candidate.credit());
        String performer = normalize(metadata.text(Field.SOLOIST));
        if (performer.isEmpty()) performer = normalize(metadata.text(Field.PERFORMERS));
        String artist = normalize(track.artist());
        boolean knownArtist = !missingLabel(track.artist()) && !artist.equals(normalize(metadata.text(Field.COMPOSER)))
            && !track.genre().equalsIgnoreCase("Classical");
        boolean creditMatch = performer.isEmpty() ? knownArtist && contains(credit, artist) : contains(credit, performer);
        boolean albumMatch = !missingLabel(track.album()) && candidate.releases().stream()
            .anyMatch(release -> normalize(release.title()).equals(normalize(track.album())));
        double seconds = track.durationSeconds();
        boolean durationMatch = seconds > 0 && candidate.durationMillis() > 0
            && Math.abs(candidate.durationMillis() / 1000.0 - seconds) <= Math.clamp(seconds * .025, 2.5, 10);
        boolean corroborated = (titleMatch || workMatch) && durationMatch
            && (performer.isEmpty() || creditMatch) && (creditMatch || albumMatch);
        int rank = (titleMatch ? 5 : workMatch ? 3 : 0) + (creditMatch ? 4 : 0)
            + (albumMatch ? 3 : 0) + (durationMatch ? 2 : 0);
        return new Evidence(titleMatch || workMatch, creditMatch, albumMatch, durationMatch, corroborated, rank);
    }

    private static boolean contains(String text, String value) {
        return !value.isEmpty() && (" " + text + " ").contains(" " + value + " ");
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT)
            .replaceAll("\\p{M}+", "").replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
