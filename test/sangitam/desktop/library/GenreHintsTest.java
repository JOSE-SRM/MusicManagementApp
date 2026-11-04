package sangitam.desktop.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenreHintsTest {
    @Test void onlyExplicitFolderNamesBecomeGenreHints() {
        assertEquals("Rock", GenreHints.fromFolder(Path.of("/Music/Rock/song.mp3")));
        assertEquals("Unknown genre", GenreHints.fromFolder(Path.of("/Music/Mixes/song.mp3")));
    }
}
