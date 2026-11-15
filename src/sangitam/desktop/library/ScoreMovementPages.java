package sangitam.desktop.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode;

/** Page boundaries checked against specific PDFs. Page numbers are PDF indices starting at one. */
public final class ScoreMovementPages {
    public record Range(int first, int last) { }
    // Consecutive pitch-class masks in eighths of a quarter note, transcribed from Noctave's Op. 57 MusicXML.
    private static final List<String> APPASSIONATA_OPENINGS = List.of(
        "1:10 100:2 20:36 100:10 1:2 20:12 100:10 1:2 20:12 120:12 91:20 95:4 924:12",
        "122:8 442:8 122:12 44a:4 10a:8 109:7 149:1 122:6 162:2 122:3 162:1 122:3 12a:1 122:8 442:8 122:12 292:4 14a:8 149:8 122:3 62:1 22:3 2a:1 22:4 0:4 149:16",
        "492:72 0:4 2:2 1:2 400:2 1:2 400:2 100:2 80:2 100:2 80:2 20:2 10:8 0:4 2:2 1:2 400:2 1:2 400:2 100:2 80:2 100:2 80:2 20:2 10:8 0:4 2:2 1:2");
    private static final String NOCTAVE_APPASSIONATA = "3765b5ee08acc608e8e1784b2b50c46aa1d2caae376808d7a2fa9d2059dbfcf3";
    private static final List<String> WALDSTEIN_OPENINGS = List.of(
        "1:4 91:52 245:8 884:12 880:2 a80:2 880:16 884:10 881:2 880:2 a80:2 880:16 424:56 491:8 221:12 220:2 2a0:2 " +
        "220:16 121:10 520:2 120:2 1a0:2 120:16 a0:24 120:2 128:2 124:2 121:2",
        "20:4 21:4 220:4 20:4 220:4 28:4 0:4 110:4 910:12 0:4 10:4 810:4 90:4 10:4 12:4 410:4 0:4 848:16 0:4 4:4 24:4 " +
        "804:4 2:4 802:4 22:4 21:4 11:4 81:4 411:8 0:8 224:16 0:4 400:4 484:4 0:4 1:4 490:4 0:4 20:4 221:16 1:4 221:6 " +
        "91:2 221:2 481:2 901:2 201:4 405:4 481:1 221:1 91:2 51:2 91:2 241:2 481:2 241:2 481:2 240:2 480:2 200:2 401:2 " +
        "81:2 410:2 481:4 241:2 481:2 201:2 403:2 405:4 91:4 15:1 411:1",
        "1:2 11:2 80:2 1:2 90:4 80:2 81:2 90:4 80:2 81:2 90:6 11:2 4:2 24:2 84:2 804:2 84:2 a0:2 80:2 880:2 1:2 11:2 " +
        "81:2 1:2 10:4 80:2 1:4 11:2 80:2 1:2 90:4 80:2 81:2 80:2 90:2 80:2 81:2 90:6 11:2 4:2 24:2 84:2 804:2 84:2 " +
        "a0:2 80:2 880:2 1:4 10:2 80:2 1:2 10:2 4:2 81:2 880:2 80:2 800:2 4:2 a0:4 820:2 24:2 a0:4 820:2 24:2 a0:4 " +
        "804:2 84:2 10:2 90:2 11:2 90:2 1:2 81:2 1:2 81:2 800:2 880:2 804:2 880:2 4:2 84:2 4:2 80:6 800:2 4:2 a0:4 " +
        "820:2 24:2 a0:4 820:2 24:2 a0:4 804:2 84:2 8:2 88:2 9:2 88:2 1:2 81:2 9:2 81:2 800:2 880:2 804:2 880:2 4:2 " +
        "84:2 804:2 84:2 10:2 90:2 11:2 90:2 1:2 81:2 11:2 81:2 800:2 880:2 804:2 880:2 4:2 84:2 804:2 84:2");

    private ScoreMovementPages() { }

    public static List<Range> verified(Path file, int pages, String key) throws IOException {
        return switch (key) {
            case "ludwig van beethoven|op57" -> {
                if (pages == 38 && isNoctaveAppassionata(file))
                    yield List.of(new Range(1, 21), new Range(22, 26), new Range(26, 38));
                if (pages == 40 && matches(file, "313cd8bcb173d6e1bccd38ac36551f1cbab2f87af023da3711057817ce58cb1d"))
                    yield List.of(new Range(3, 22), new Range(23, 26), new Range(27, 40));
                yield List.of();
            }
            case "ludwig van beethoven|op106" -> pages == 43
                && matches(file, "3fe116d07a2add39b667b21bb21a1ca455e1404017d0f90ba141a5a0daeb7ca0")
                ? List.of(new Range(1, 12)) : List.of();
            case "ludwig van beethoven|op53" -> pages == 42
                && matches(file, "8eaa3be07166924da36640cd5fc3f996cbdea249f940794e11ec8bba418a8041")
                ? List.of(new Range(3, 19), new Range(20, 21), new Range(22, 42)) : List.of();
            default -> List.of();
        };
    }

    public static Range movement(int number, List<Range> ranges) {
        return number < 1 || number > ranges.size() ? null : ranges.get(number - 1);
    }

    public static int identify(Path audio, double duration, String key) throws Exception {
        // Full performances keep the complete score, and ambiguous openings keep the existing search.
        if (duration <= 0 || duration >= 16 * 60) return 0;
        return switch (key) {
            case "ludwig van beethoven|op57" -> ScoreSync.identifyOpening(audio, APPASSIONATA_OPENINGS);
            case "ludwig van beethoven|op53" -> ScoreSync.identifyOpening(audio, WALDSTEIN_OPENINGS);
            default -> 0;
        };
    }

    public static Range bookmarks(Path file, int movement) throws IOException {
        if (movement <= 0) return null;
        try (var document = Loader.loadPDF(file.toFile())) {
            return bookmarks(document, document.getDocumentCatalog().getDocumentOutline(), movement, 0);
        }
    }

    private static Range bookmarks(org.apache.pdfbox.pdmodel.PDDocument document, PDOutlineNode node,
                                   int movement, int depth) throws IOException {
        if (node == null || depth == 5) return null;
        for (var item : node.children()) {
            var next = item.getNextSibling();
            if (next != null && ScoreWork.numberedMovementHeading(item.getTitle()) == movement
                && ScoreWork.numberedMovementHeading(next.getTitle()) == movement + 1) {
                var first = item.findDestinationPage(document);
                var following = next.findDestinationPage(document);
                if (first != null && following != null) {
                    int start = document.getPages().indexOf(first) + 1;
                    int stop = document.getPages().indexOf(following);
                    if (start > 0 && stop >= start) return new Range(start, stop);
                }
            }
            var child = bookmarks(document, item, movement, depth + 1);
            if (child != null) return child;
        }
        return null;
    }

    static boolean isNoctaveAppassionata(Path file) throws IOException {
        return Files.size(file) == 1_615_367 && matches(file, NOCTAVE_APPASSIONATA);
    }

    private static boolean matches(Path file, String checksum) throws IOException {
        try (var input = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            return checksum.equals(HexFormat.of().formatHex(input.getMessageDigest().digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
