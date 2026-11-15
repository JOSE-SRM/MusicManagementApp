package sangitam.desktop.model;

import java.util.*;
import static sangitam.desktop.model.TrackMetadata.Field.*;

/** Explicit credits in display priority. Commas are preserved for surname-first names. */
public final class ArtistCredits {
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern SEPARATOR = java.util.regex.Pattern.compile(";|\\s/\\s");
    public record Credit(String name, String role) { }
    private ArtistCredits() { }
    public static String key(String name) { return WHITESPACE.matcher(name.strip()).replaceAll(" ").toLowerCase(Locale.ROOT); }
    public static List<Credit> of(Track track) {
        var names = new LinkedHashMap<String, Credit>();
        for (var field : List.of(COMPOSER, CONDUCTOR, SOLOIST, PERFORMERS, ORCHESTRA, ALBUM_ARTIST)) add(names, track.metadata().text(field), field.toString());
        add(names, track.artist(), "Artist");
        return List.copyOf(names.values());
    }
    private static void add(Map<String, Credit> names, String text, String role) {
        if (text.isBlank()) return;
        for (String name : SEPARATOR.split(text)) {
            name = name.strip();
            if (!name.isEmpty() && !name.equalsIgnoreCase("Unknown artist") && !name.equalsIgnoreCase("Unknown"))
                names.putIfAbsent(key(name), new Credit(name, role));
        }
    }
    public static boolean features(Track track, String name) { return of(track).stream().anyMatch(credit -> key(credit.name()).equals(key(name))); }
}
