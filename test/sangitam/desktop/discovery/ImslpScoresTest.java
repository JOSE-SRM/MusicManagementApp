package sangitam.desktop.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImslpScoresTest {
    @Test void ranksCompleteEngravedEditionsAndFiltersAudioPartsAndArrangements() {
        String html = card(100, "Complete Score", "full.pdf", "PDF scanned by Archive Copyright Public Domain")
            + card(200, "Complete Score", "engraved.pdf", "PDF typeset by editor Copyright Creative Commons Attribution 4.0")
            + card(300, "Piano Part", "part.pdf", "Copyright Public Domain")
            + card(400, "Complete Score", "recording.mp3", "Copyright Public Domain")
            + card(500, "Complete Score", "arrangement.pdf", "Arranger Different Person Copyright Public Domain")
            + card(600, "Complete Score", "restricted.pdf", "Copyright Non-PD in EU");
        var editions = ImslpScores.editions(html);
        assertEquals(2, editions.size());
        assertEquals("File:engraved.pdf", editions.getFirst().file());
        assertTrue(editions.getFirst().link().toString().endsWith("/200"));
    }

    private static String card(int id, String title, String file, String metadata) {
        return """
            <div class="we"><div id="IMSLP%d">
            <div class="we_file_download"><a href="https://imslp.org/wiki/Special:ImagefromIndex/%d">%s</a></div>
            <span class="we_file_info2"><a href="/wiki/File:%s" title="File:%s">File</a> – 12 pp.</span>
            </div>%s</div>
            """.formatted(id, id, title, file, file, metadata);
    }
}
