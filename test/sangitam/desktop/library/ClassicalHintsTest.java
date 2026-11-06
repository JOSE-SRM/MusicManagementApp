package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.model.TrackMetadata.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClassicalHintsTest {
    @Test void extractsExplicitClassicalTermsAndRecordsTheirSource() {
        var values = ClassicalHints.enrich(Path.of("Classical/Baroque/sonata.mp3"),
            "Piano Sonata in C sharp minor, Op. 27 No. 2: I. Adagio sostenuto", "Classical", Map.of());
        assertEquals("C# minor", values.get(Field.KEY).text());
        assertEquals("Op. 27 No. 2", values.get(Field.OPUS).text());
        assertEquals("I. Adagio sostenuto", values.get(Field.MOVEMENT).text());
        assertEquals("Sonata", values.get(Field.FORM).text());
        assertEquals("piano", values.get(Field.INSTRUMENTATION).text());
        assertEquals(Origin.TITLE_HINT, values.get(Field.KEY).origin());
        assertEquals(Origin.FOLDER_HINT, values.get(Field.PERIOD).origin());
        assertFalse(values.containsKey(Field.CONDUCTOR));
        assertFalse(values.containsKey(Field.SOLOIST));
    }
    @Test void embeddedCreditsWinAndAmbiguousComposerDoesNotDeterminePeriod() {
        var embedded = Map.of(Field.COMPOSER, new Value("Beethoven", Origin.EMBEDDED),
            Field.KEY, new Value("D minor", Origin.EMBEDDED));
        var result = ClassicalHints.enrich(Path.of("music/song.mp3"), "Sonata in A major", "Classical", embedded);
        assertEquals(embedded.get(Field.KEY), result.get(Field.KEY));
        assertFalse(result.containsKey(Field.PERIOD));
        assertTrue(ClassicalHints.enrich(Path.of("pop/song.mp3"), "The Sonata of Love in A major", "Pop", Map.of()).isEmpty());
        var pop = ClassicalHints.enrich(Path.of("pop/song.mp3"), "The Sonata of Love in A major", "Pop",
            Map.of(Field.COMPOSER, new Value("Pop songwriter", Origin.EMBEDDED)));
        assertFalse(pop.containsKey(Field.KEY), "Having a composer does not make a song classical");
    }
    @Test void flexibleTagsAreDeduplicatedAndVocabularyIsOnlyASuggestion() {
        assertTrue(TagVocabulary.ALL.size() >= 100);
        assertEquals(1, TrackMetadata.parseTags("Focus, focus,  Focus ").size());
        assertTrue(TrackMetadata.parseTags("My own category").contains("My own category"));
        assertTrue(TagVocabulary.matching("piano").contains("Piano"));
        assertThrows(IllegalArgumentException.class, () -> TrackMetadata.parseTags("x".repeat(81)));
    }
}
