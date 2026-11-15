package sangitam.desktop.library;

import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.model.TrackMetadata.Origin;
import sangitam.desktop.model.TrackMetadata.Value;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Resolves written key information and a small set of documented score keys. */
public final class ClassicalHints {
    private static final Pattern FORM = Pattern.compile(
        "\\b(symphonic poem|tone poem|sonatas?|symphon(?:y|ies)|concertos?"
        + "|piano con(?:cert|erto)(?=\\s*,?\\s*(?:(?:no\\.?|number)\\s*)?\\d)"
        + "|nocturnes?|preludes?|fugues?|waltz(?:es)?|valses?|etudes?|études?|polonaises?|mazurkas?|scherzos?"
        + "|rondos?|suites?|overtures?|requiem|mass|cantata|oratorio|operetta|opera|ballet|toccata|passacaglia|chaconne"
        + "|variations?|string quartet|piano trio|ballades?|bagatelle|intermezzo|canon|fantas(?:y|ia|ie)|minuet|ricercare"
        + "|impromptu|dance|serenade|rhapsody|march)(?=\\b|op\\.?\\s*\\d)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern KEY = Pattern.compile("\\b([A-G])(?:\\s*([#♯b♭])|[-\\s]+(flat|sharp))?\\s+(major|minor)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPUS = Pattern.compile("\\b(?:op(?:us)?\\.?\\s*\\d+[a-z]?(?:\\s*[,;]?\\s*(?:no\\.?|number)\\s*\\d+)?|BWV\\s*\\d+[a-z]?|K(?:V)?\\.?\\s*\\d+[a-z]?|Hob\\.?\\s*[IVX]+[:.]\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MOVEMENT = Pattern.compile("(?:^|[:—–]|\\s-\\s)\\s*((?:[IVX]{1,8}|[1-9]\\d?)\\.\\s*[\\p{L}][^:]{1,100})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_MOVEMENT = Pattern.compile("\\b(?:movement|mvt\\.?)\\s+(iii|ii|i|[1-3])\\b");
    private static final Pattern PHASE = Pattern.compile("\\s*\\[\\s*phase\\s+(one|two|three|four|[1-9]\\d?)\\s*]\\s*$",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDINAL_MOVEMENT = Pattern.compile(
        "(?i)(?:^|:)\\s*([1-9]\\d?)(?:st|nd|rd|th)?\\.?\\s*(?:mov(?:ement)?|mvt)\\.?\\s*:?\\s*([^;]+)");
    private static final Pattern INSTRUMENT = Pattern.compile(
        "\\b(piano(?:forte)?|violin|viola|cello|flute|clarinet|oboe|bassoon|horn|trumpet|guitar|organ|harpsichord|choir|orchestra)\\b",
        Pattern.CASE_INSENSITIVE);
    private ClassicalHints() { }

    static String workHint(String title) {
        title = withoutPhase(title);
        var form = FORM.matcher(title);
        if (!form.find()) return "";
        var movement = MOVEMENT.matcher(title);
        var ordinal = ORDINAL_MOVEMENT.matcher(title);
        int end = movement.find() ? movement.start() : ordinal.find() ? ordinal.start() : title.length();
        if (form.end() >= end) return "";
        return title.substring(form.start(), end).strip();
    }

    static String withoutPhase(String title) { return PHASE.matcher(title).replaceFirst("").strip(); }

    static String form(String title) {
        String matched = fold(match(FORM, title));
        return switch (matched) {
            case "etude", "etudes" -> "Étude";
            case "variation", "variations" -> "Variations";
            case "fantasia", "fantasie" -> "Fantasy";
            case "symphonies" -> "Symphony";
            case "waltzes", "valse", "valses" -> "Waltz";
            case "piano concert", "piano conerto" -> "Concerto";
            case "tone poem" -> "Symphonic Poem";
            case "mass" -> "Mass";
            default -> categoryCase(matched.endsWith("s") ? matched.substring(0, matched.length() - 1) : matched);
        };
    }

    public static String categoryCase(String value) {
        return java.util.Arrays.stream(value.strip().split("\\s+"))
            .filter(word -> !word.isEmpty())
            .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.joining(" "));
    }

    static String instrumentLabel(String text) {
        var names = new LinkedHashSet<String>();
        for (String part : text.split("\\s*[,;/]\\s*")) {
            if (!part.isBlank()) names.add(categoryCase(part.equalsIgnoreCase("pianoforte") ? "Piano" : part));
        }
        return String.join(", ", names);
    }

    public static Map<Field, Value> enrich(Path path, String title, String genre, Map<Field, Value> embedded) {
        var fields = new EnumMap<Field, Value>(Field.class);
        fields.putAll(embedded);
        String genreName = genre.toLowerCase(Locale.ROOT).strip();
        boolean classical = genreName.contains("classical") || Set.of("baroque", "renaissance", "medieval", "chamber music", "opera", "orchestral").contains(genreName);
        for (Path part : path) if (part.toString().equalsIgnoreCase("Classical")) classical = true;
        if (!classical) return Map.copyOf(fields);
        hint(fields, Field.FORM, form(title), Origin.TITLE_HINT);
        var movement = MOVEMENT.matcher(title);
        int movementStart = title.length();
        if (movement.find()) {
            hint(fields, Field.MOVEMENT, movement.group(1), Origin.TITLE_HINT);
            movementStart = movement.start(1);
        }
        var phase = PHASE.matcher(title);
        if (phase.find()) {
            String position = switch (phase.group(1).toLowerCase(Locale.ROOT)) {
                case "one" -> "1";
                case "two" -> "2";
                case "three" -> "3";
                case "four" -> "4";
                default -> phase.group(1);
            };
            hint(fields, Field.MOVEMENT, position, Origin.TITLE_HINT);
            movementStart = phase.start();
        }
        var ordinal = ORDINAL_MOVEMENT.matcher(title);
        if (ordinal.find()) {
            hint(fields, Field.MOVEMENT, ordinal.group(1) + ". " + ordinal.group(2).strip(), Origin.TITLE_HINT);
            movementStart = ordinal.start();
        }
        var key = KEY.matcher(title);
        while (key.find()) {
            String accidental = key.group(2) != null ? key.group(2).replace("♯", "#").replace("♭", "b")
                : key.group(3) == null ? "" : key.group(3).equalsIgnoreCase("flat") ? "b" : "#";
            Field field = fields.containsKey(Field.MOVEMENT) && key.start() < movementStart ? Field.WORK_KEY : Field.KEY;
            hint(fields, field, key.group(1).toUpperCase(Locale.ROOT) + accidental + " " + key.group(4).toLowerCase(Locale.ROOT), Origin.TITLE_HINT);
        }
        hint(fields, Field.OPUS, match(OPUS, title), Origin.TITLE_HINT);
        addScoreKeys(path, title, fields);
        for (Path part : path) {
            String value = part.toString();
            if (Set.of("baroque", "romantic", "renaissance", "medieval", "classical period").contains(value.toLowerCase(Locale.ROOT)))
                hint(fields, Field.PERIOD, value, Origin.FOLDER_HINT);
        }
        var instruments = new LinkedHashSet<String>();
        var instrument = INSTRUMENT.matcher(title);
        while (instrument.find()) instruments.add(instrument.group().toLowerCase(Locale.ROOT));
        if (!instruments.isEmpty()) hint(fields, Field.INSTRUMENTATION, String.join(", ", instruments), Origin.TITLE_HINT);
        return Map.copyOf(fields);
    }
    private static void addScoreKeys(Path path, String title, Map<Field, Value> fields) {
        String identity = fold(title + " " + text(fields, Field.WORK) + " " + text(fields, Field.OPUS));
        String composer = fold(text(fields, Field.COMPOSER));
        if (composer.isBlank()) composer = fold(path.toString() + " " + title);
        if (!composer.contains("beethoven")) return;
        if (identity.contains("fur elise") || identity.matches(".*\\bwoo\\s*59\\b.*")) {
            fields.put(Field.KEY, new Value("A minor", Origin.SCORE_REFERENCE));
            return;
        }
        if (!identity.contains("pathetique") && !identity.matches(".*\\bsonata\\b.*\\bop\\.?\\s*13\\b.*")) return;
        fields.put(Field.WORK_KEY, new Value("C minor", Origin.SCORE_REFERENCE));
        String label = fold(text(fields, Field.MOVEMENT) + " " + title);
        String movement = fold(text(fields, Field.MOVEMENT));
        var named = NAMED_MOVEMENT.matcher(label);
        if (movement.matches("^(ii|2)(?:\\W.*)?$")) setScoreKey(fields, "A-flat major");
        else if (movement.matches("^(i|iii|1|3)(?:\\W.*)?$")) setScoreKey(fields, "C minor");
        else if (named.find()) setScoreKey(fields, Set.of("ii", "2").contains(named.group(1)) ? "A-flat major" : "C minor");
        else if (label.contains("adagio cantabile") || label.contains("[phase two]")) setScoreKey(fields, "A-flat major");
        else if (label.contains("grave") || label.contains("rondo") || label.contains("[phase one]")
            || label.contains("[phase three]")) setScoreKey(fields, "C minor");
    }

    private static void setScoreKey(Map<Field, Value> fields, String key) {
        fields.put(Field.KEY, new Value(key, Origin.SCORE_REFERENCE));
    }

    private static String text(Map<Field, Value> fields, Field field) {
        return fields.containsKey(field) ? fields.get(field).text() : "";
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
    }
    private static String match(Pattern pattern, String text) { var found = pattern.matcher(text); return found.find() ? found.group() : ""; }
    private static void hint(Map<Field, Value> fields, Field field, String text, Origin origin) {
        if (!text.isBlank()) fields.putIfAbsent(field, new Value(text, origin));
    }
}
