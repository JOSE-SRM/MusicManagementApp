package sangitam.desktop.discovery;

import org.junit.jupiter.api.Test;
import sangitam.desktop.library.ScoreWork;

import static org.junit.jupiter.api.Assertions.*;

class MutopiaScoresTest {
    @Test void catalogueSelectsTheThreeEngravedPianoMovementsAndRejectsTranscriptions() {
        var work = new ScoreWork("beethoven|op13", "Ludwig van Beethoven", "Piano Sonata No. 8 (Pathétique)",
            "Op. 13", "Piano", 2);
        String catalogue = "<table>" + row(299, "1st", "Piano") + row(2064, "2nd", "2 Guitars")
            + row(295, "2nd", "Piano") + row(296, "3rd", "Piano") + "</table>";
        var items = MutopiaScores.match(catalogue, work);
        assertEquals(java.util.List.of(1, 2, 3), items.stream().map(MutopiaScores.Item::movement).toList());
        assertTrue(items.get(1).source().toString().endsWith("id=295"));
        assertTrue(MutopiaScores.match("<table>" + row(295, "2nd", "Piano") + "</table>", work).isEmpty());
    }

    private static String row(int id, String movement, String instrument) {
        return "<tr><td>L. V. Beethoven</td><td><a href='cgibin/piece-info.cgi?id=" + id
            + "'>Sonata No. 8 “Pathétique” (" + movement + " Movement)</a></td><td>Op. 13</td><td>"
            + instrument + "</td></tr>";
    }
}
