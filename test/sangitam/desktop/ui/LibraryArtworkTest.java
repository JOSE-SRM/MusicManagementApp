package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class LibraryArtworkTest {
    private final Path path = Path.of("cover.mp3").toAbsolutePath().normalize();
    private final LibraryEntry entry = new LibraryEntry(new Key(Kind.SONG, path.toString()), "Song", "Artist",
        List.of(new Track(path, "Song", "Artist", "Album", 60, "MP3")), 0, 0, 0);
    private Icon lookup(LibraryArtwork cache, int version, Runnable repaint) throws Exception {
        var found = new AtomicReference<Icon>();
        SwingUtilities.invokeAndWait(() -> found.set(cache.forEntry(entry, Set.of(), Map.of(path, new FileStamp(version, version)), repaint)));
        return found.get();
    }
    @Test void changedFileInvalidatesMissingArtworkWithoutReadingOnTheEventThread() throws Exception {
        var calls = new AtomicInteger(); Icon cover = new ImageIcon(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
        var cache = new LibraryArtwork(72, ignored -> {
            assertFalse(SwingUtilities.isEventDispatchThread()); return calls.incrementAndGet() == 1 ? null : cover;
        });
        try {
            var first = new CountDownLatch(1); assertNull(lookup(cache, 1, first::countDown)); assertTrue(first.await(5, TimeUnit.SECONDS));
            assertNull(lookup(cache, 1, () -> fail("Negative cover results must be cached"))); assertEquals(1, calls.get());
            var changed = new CountDownLatch(1); assertNull(lookup(cache, 2, changed::countDown)); assertTrue(changed.await(5, TimeUnit.SECONDS));
            assertSame(cover, lookup(cache, 2, () -> { })); assertEquals(2, calls.get());
        } finally { SwingUtilities.invokeAndWait(cache::close); }
    }
    @Test void lateOldReadsCannotReplaceNewerArtworkAndClosingSuppressesCallbacks() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        Icon old = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
        Icon current = new ImageIcon(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
        var calls = new AtomicInteger();
        var cache = new LibraryArtwork(72, ignored -> {
            if (calls.incrementAndGet() == 1) { started.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } return old; }
            return current;
        });
        try {
            lookup(cache, 1, () -> { }); assertTrue(started.await(5, TimeUnit.SECONDS));
            var updated = new CountDownLatch(1); assertNull(lookup(cache, 2, updated::countDown));
            release.countDown(); assertTrue(updated.await(5, TimeUnit.SECONDS));
            assertSame(current, lookup(cache, 2, () -> { }));
        } finally { release.countDown(); SwingUtilities.invokeAndWait(cache::close); }
        var afterClose = new AtomicInteger(); var reading = new CountDownLatch(1); var finish = new CountDownLatch(1);
        var closed = new LibraryArtwork(72, ignored -> {
            reading.countDown();
            boolean released = false;
            while (!released) try { released = finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignoredInterrupt) { }
            return old;
        });
        try {
            lookup(closed, 1, afterClose::incrementAndGet); assertTrue(reading.await(5, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(closed::close); finish.countDown();
            assertNull(lookup(closed, 2, afterClose::incrementAndGet)); assertEquals(0, afterClose.get());
        } finally { finish.countDown(); SwingUtilities.invokeAndWait(closed::close); }
    }
}
