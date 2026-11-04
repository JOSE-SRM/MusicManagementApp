package sangitam.desktop.library;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Uses only explicit folder names as genre hints when audio files lack a genre tag. */
final class GenreHints {
    private static final Map<String, String> FOLDERS = new LinkedHashMap<>();
    static {
        FOLDERS.put("hip hop", "Hip-Hop"); FOLDERS.put("hiphop", "Hip-Hop");
        FOLDERS.put("classical", "Classical"); FOLDERS.put("electronic", "Electronic");
        FOLDERS.put("ambient", "Ambient"); FOLDERS.put("soundtrack", "Soundtrack");
        FOLDERS.put("jazz", "Jazz"); FOLDERS.put("blues", "Blues");
        FOLDERS.put("country", "Country"); FOLDERS.put("metal", "Metal");
        FOLDERS.put("rock", "Rock"); FOLDERS.put("pop", "Pop");
        FOLDERS.put("folk", "Folk"); FOLDERS.put("reggae", "Reggae");
        FOLDERS.put("indie", "Indie"); FOLDERS.put("dance", "Dance");
    }
    private GenreHints() { }
    static String fromFolder(Path file) {
        for (Path parent = file.getParent(); parent != null; parent = parent.getParent()) {
            String name = parent.getFileName() == null ? "" : parent.getFileName().toString()
                .toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ').trim();
            String genre = FOLDERS.get(name);
            if (genre != null) return genre;
        }
        return "Unknown genre";
    }
}
