package sangitam.desktop.library;

import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;

import java.util.regex.Pattern;

/** Composition identity excludes the performer, recording and movement. */
public record ScoreWork(String key, String composer, String title, String catalogue, String instrument, int movement) {
    private static final Pattern NUMBER = Pattern.compile("^(?:movement |mvt )?(\\d{1,2}|iv|iii|ii|i)(?:st|nd|rd|th)?(?: |$)");
    private static final Pattern TITLE_MOVEMENT = Pattern.compile("\\b(?:movement|mvt|phase) (one|two|three|four|iv|iii|ii|i|\\d{1,2})\\b");
    private static final Pattern TRAILING_MOVEMENT = Pattern.compile("(?i)(?:[:—–]|\\s-\\s)\\s*(\\d{1,2}|IV|III|II|I)\\.\\s*\\p{L}[^:]*$");
    private static final Pattern CATALOGUE = Pattern.compile("\\b(?:op(?:us)?|woo|bwv|kv?|rv|d|hob|hwv|s)\\s*\\d+[a-z]?(?:\\s+no\\s*\\d+)?\\b");

    public static ScoreWork of(Track track) {
        var reference = WorkDetails.reference(track);
        String composer = track.metadata().text(Field.COMPOSER);
        if (composer.isBlank()) composer = reference.getOrDefault(Field.COMPOSER, WorkDetails.composerHint(track));
        composer = WorkDetails.canonicalComposer(composer);
        String title = reference.getOrDefault(Field.WORK, track.metadata().text(Field.WORK));
        String catalogue = reference.getOrDefault(Field.OPUS, track.metadata().text(Field.OPUS));
        if (catalogue.isBlank()) {
            var match = CATALOGUE.matcher(WorkDetails.fold(track.title()));
            if (match.find()) catalogue = match.group();
        }
        String instrument = reference.getOrDefault(Field.INSTRUMENTATION, track.metadata().text(Field.INSTRUMENTATION));
        int movement = movement(track.metadata().text(Field.MOVEMENT), track.title());
        if (title.isBlank()) title = ClassicalHints.withoutPhase(track.title());
        String canonicalTitle = WorkDetails.fold(title);
        String key = composer.isBlank() || canonicalTitle.isBlank() || canonicalTitle.equals("unknown title") ? "" :
            WorkDetails.fold(composer) + "|" + (catalogue.isBlank() ? canonicalTitle : catalogueKey(catalogue));
        return new ScoreWork(key, composer, title, catalogue, instrument, movement);
    }

    public static String catalogueKey(String text) {
        return WorkDetails.fold(text).replaceFirst("^opus", "op").replaceFirst("^o(?=\\s*\\d)", "op")
            .replaceFirst("^kv", "k").replace(" ", "");
    }

    static int movement(String value, String title) {
        var number = NUMBER.matcher(WorkDetails.fold(value));
        if (number.find()) return numeral(number.group(1));
        var labeled = TITLE_MOVEMENT.matcher(WorkDetails.fold(title));
        if (labeled.find()) return numeral(labeled.group(1));
        var suffix = TRAILING_MOVEMENT.matcher(title);
        return suffix.find() ? numeral(WorkDetails.fold(suffix.group(1))) : 0;
    }

    static int numberedMovementHeading(String title) {
        if (title == null || !title.matches("(?i)\\s*(?:movement\\s+|mvt\\s+)?(?:[1-9]|iv|iii|ii|i)(?:\\s*[.:–-]|\\s*$).*"))
            return 0;
        var number = NUMBER.matcher(WorkDetails.fold(title));
        return number.find() ? numeral(number.group(1)) : 0;
    }

    private static int numeral(String text) {
        return switch (text) {
            case "i", "one" -> 1;
            case "ii", "two" -> 2;
            case "iii", "three" -> 3;
            case "iv", "four" -> 4;
            default -> Integer.parseInt(text);
        };
    }
}
