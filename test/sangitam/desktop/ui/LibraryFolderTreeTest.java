package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;


class LibraryFolderTreeTest {
    @Test void nestsFoldersAndKeepsTracksAtTheirActualParents() {
        Path root = Path.of("music", "Downloaded").toAbsolutePath();
        Path classical = root.resolve("Classical");
        Path sonatas = classical.resolve("Beethoven").resolve("Piano Sonatas");
        Track sonata = new Track(sonatas.resolve("Sonata.mp3"), "Sonata", "Beethoven", "Album", 60, "MP3");
        Track sampler = new Track(classical.resolve("Sampler.mp3"), "Sampler", "Artist", "Album", 60, "MP3");
        Track other = new Track(root.resolve("Other.mp3"), "Other", "Artist", "Album", 60, "MP3");

        LocationIndex index = LocationIndex.build(List.of(other, sampler, sonata), "");
        assertEquals(List.of(root), index.page(null, 0).children());
        assertEquals(List.of(classical, other), index.page(root, 0).children());
        assertEquals(List.of(sonatas.getParent(), sampler), index.page(classical, 0).children());
        assertEquals(List.of(sonatas), index.page(sonatas.getParent(), 0).children());
        assertEquals(List.of(sonata), index.page(sonatas, 0).children());
        assertEquals(3, index.matches());
    }
}
