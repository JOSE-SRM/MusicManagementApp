package sangitam.desktop.library;

import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.model.TrackMetadata.Origin;
import sangitam.desktop.model.TrackMetadata.Value;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** Conservative text rules, never audio recognition or an attribution of a performer to a composer. */
public final class ClassicalHints {
    private static final Pattern FORM = Pattern.compile("\\b(sonata|symphony|concerto|nocturne|prelude|fugue|waltz|etude|étude|polonaise|mazurka|scherzo|rondo|suite|overture|requiem|mass|cantata|oratorio|opera|ballet|toccata|passacaglia|chaconne|variation(?:s)?|string quartet|piano trio)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern KEY = Pattern.compile("\\b([A-G])\\s*(?:([#♯b♭])|(?:\\s+(flat|sharp)))?\\s+(major|minor)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPUS = Pattern.compile("\\b(?:op(?:us)?\\.?\\s*\\d+[a-z]?(?:\\s*[,;]?\\s*(?:no\\.?|number)\\s*\\d+)?|BWV\\s*\\d+[a-z]?|K(?:V)?\\.?\\s*\\d+[a-z]?|Hob\\.?\\s*[IVX]+[:.]\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVEMENT = Pattern.compile("(?:^|[:—–])\\s*((?:[IVX]{1,8}|[1-9]\\d?)\\.\\s*[\\p{L}][^:]{1,100})$", Pattern.CASE_INSENSITIVE);
    private ClassicalHints() { }

    public static Map<Field, Value> enrich(Path path, String title, String genre, Map<Field, Value> embedded) {
        var fields = new EnumMap<Field, Value>(Field.class);
        fields.putAll(embedded);
        String genreName = genre.toLowerCase(Locale.ROOT).strip();
        boolean classical = genreName.contains("classical") || Set.of("baroque", "renaissance", "medieval", "chamber music", "opera", "orchestral").contains(genreName);
        for (Path part : path) if (part.toString().equalsIgnoreCase("Classical")) classical = true;
        if (!classical) return Map.copyOf(fields);
        hint(fields, Field.FORM, match(FORM, title), Origin.TITLE_HINT);
        var key = KEY.matcher(title);
        if (key.find()) {
            String accidental = key.group(2) != null ? key.group(2).replace("♯", "#").replace("♭", "b")
                : key.group(3) == null ? "" : key.group(3).equalsIgnoreCase("flat") ? "b" : "#";
            hint(fields, Field.KEY, key.group(1).toUpperCase(Locale.ROOT) + accidental + " " + key.group(4).toLowerCase(Locale.ROOT), Origin.TITLE_HINT);
        }
        hint(fields, Field.OPUS, match(OPUS, title), Origin.TITLE_HINT);
        var movement = MOVEMENT.matcher(title);
        if (movement.find()) hint(fields, Field.MOVEMENT, movement.group(1), Origin.TITLE_HINT);
        for (Path part : path) {
            String value = part.toString();
            if (Set.of("baroque", "romantic", "renaissance", "medieval", "classical period").contains(value.toLowerCase(Locale.ROOT)))
                hint(fields, Field.PERIOD, value, Origin.FOLDER_HINT);
        }
        var instruments = new ArrayList<String>();
        for (String instrument : List.of("piano", "violin", "viola", "cello", "flute", "guitar", "organ", "harpsichord", "choir", "orchestra"))
            if (Pattern.compile("\\b" + instrument + "\\b", Pattern.CASE_INSENSITIVE).matcher(title).find()) instruments.add(instrument);
        if (!instruments.isEmpty()) hint(fields, Field.INSTRUMENTATION, String.join(", ", instruments), Origin.TITLE_HINT);
        return Map.copyOf(fields);
    }
    private static String match(Pattern pattern, String text) { var found = pattern.matcher(text); return found.find() ? found.group() : ""; }
    private static void hint(Map<Field, Value> fields, Field field, String text, Origin origin) {
        if (!text.isBlank()) fields.putIfAbsent(field, new Value(text, origin));
    }
}
