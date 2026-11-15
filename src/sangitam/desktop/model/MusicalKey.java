package sangitam.desktop.model;

import java.util.Locale;
import java.util.regex.Pattern;

/** Formats a key label while leaving its stored spelling and origin intact. */
public final class MusicalKey {
    private static final Pattern LABEL = Pattern.compile(
        "([A-G])(?:\\s*([b#♭♯♮])|[\\s-]+(flat|sharp|natural))?(\\s*(?:major|minor|maj|min|m))?",
        Pattern.CASE_INSENSITIVE);

    private MusicalKey() { }

    public static String display(String value) {
        var match = LABEL.matcher(value.strip());
        if (!match.matches()) return value;
        String accidental = match.group(2) == null ? match.group(3) : match.group(2);
        String symbol = accidental == null ? "" : switch (accidental.toLowerCase(Locale.ROOT)) {
            case "b", "flat" -> "♭";
            case "#", "sharp" -> "♯";
            case "natural" -> "♮";
            default -> accidental;
        };
        return match.group(1).toUpperCase(Locale.ROOT) + symbol + (match.group(4) == null ? "" : match.group(4));
    }
}
