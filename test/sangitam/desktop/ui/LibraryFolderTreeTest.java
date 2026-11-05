package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LibraryFolderTreeTest {
    @Test void nestsFoldersAndKeepsTracksAtTheirActualParents() {
        Path root = Path.of("music", "Downloaded").toAbsolutePath();
        Path classical = root.resolve("Classical");
        Path sonatas = classical.resolve("Beethoven").resolve("Piano Sonatas");
        Track sonata = new Track(sonatas.resolve("Sonata.mp3"), "Sonata", "Beethoven", "Album", 60, "MP3");
        Track sampler = new Track(classical.resolve("Sampler.mp3"), "Sampler", "Artist", "Album", 60, "MP3");
        Track other = new Track(root.resolve("Other.mp3"), "Other", "Artist", "Album", 60, "MP3");

        LibraryFolderTree.FolderModel model = LibraryFolderTree.buildModel(List.of(other, sampler, sonata));
        var folders = model.nodes();
        assertSame(folders.get(root), folders.get(classical).getParent());
        assertSame(folders.get(classical), folders.get(sonatas.getParent()).getParent());
        assertSame(folders.get(sonatas), folders.get(sonata.path()).getParent());
        assertSame(folders.get(classical), folders.get(sampler.path()).getParent());
        assertSame(folders.get(sonatas.getParent()), folders.get(classical).getChildAt(0));
        assertSame(folders.get(root), model.root().getFirstChild());
        assertEquals(1, model.root().getChildCount());
    }
}
