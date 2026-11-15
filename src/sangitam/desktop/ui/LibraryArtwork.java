package sangitam.desktop.ui;

import org.jaudiotagger.audio.AudioFileIO;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.FileStamp;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Reads embedded covers on demand. The cache and pending queue have fixed bounds. */
final class LibraryArtwork {
    private record CoverKey(Path path, FileStamp stamp) { }
    private static final FileStamp UNKNOWN = new FileStamp(-1, -1);
    private final int size;
    private final Function<Path, Icon> reader;
    private boolean closed;
    private final LinkedHashMap<CoverKey, Icon> cache = new LinkedHashMap<>(160, 0.75f, true);
    private final Set<CoverKey> pending = new HashSet<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(24), task -> {
            Thread thread = new Thread(task, "sangitam-cover-art");
            thread.setDaemon(true);
            return thread;
        });

    LibraryArtwork() { this(42); }
    LibraryArtwork(int size) { this(size, null); }
    LibraryArtwork(int size, Function<Path, Icon> reader) {
        if (size < 16 || size > 128) throw new IllegalArgumentException("Cover size must be within 16–128 pixels.");
        this.size = size; this.reader = reader == null ? this::read : reader;
    }

    Icon forEntry(LibraryEntry entry, Set<Path> unavailable, Map<Path, FileStamp> stamps, Runnable repaint) {
        if (closed) return null;
        List<Track> tracks = entry.tracks();
        int sample = Math.min(3, tracks.size());
        for (int i = 0; i < sample; i++) {
            Path path = tracks.get(i).path();
            if (unavailable.contains(path)) continue;
            CoverKey key = new CoverKey(path, stamps.getOrDefault(path, UNKNOWN));
            if (cache.containsKey(key)) {
                Icon found = cache.get(key);
                if (found != null) return found;
            } else {
                request(key, repaint);
                break;
            }
        }
        return null;
    }

    private void request(CoverKey key, Runnable repaint) {
        if (worker.isShutdown() || pending.size() >= 24 || !pending.add(key)) return;
        worker.execute(() -> {
            Icon loaded;
            try { loaded = reader.apply(key.path()); } catch (RuntimeException error) { loaded = null; }
            Icon icon = loaded;
            SwingUtilities.invokeLater(() -> {
                pending.remove(key);
                if (closed) return;
                cache.put(key, icon);
                if (cache.size() > 160) cache.pollFirstEntry();
                repaint.run();
            });
        });
    }

    private Icon read(Path path) {
        try {
            var tag = AudioFileIO.read(path.toFile()).getTag();
            var cover = tag == null ? null : tag.getFirstArtwork();
            byte[] bytes = cover == null ? null : cover.getBinaryData();
            if (bytes == null || bytes.length > 8_000_000) return null;
            try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (input == null) return null;
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return null;
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > 12000 || height > 12000) return null;
                    int sample = Math.max(1, Math.max(width, height) / (size * 2));
                    var params = reader.getDefaultReadParam();
                    params.setSourceSubsampling(sample, sample, 0, 0);
                    var image = reader.read(0, params);
                    double scale = size / (double) Math.max(image.getWidth(), image.getHeight());
                    return new ImageIcon(image.getScaledInstance(Math.max(1, (int) (image.getWidth() * scale)),
                        Math.max(1, (int) (image.getHeight() * scale)), Image.SCALE_SMOOTH));
                } finally {
                    reader.dispose();
                }
            }
        } catch (Exception error) {
            return null;
        }
    }

    void close() { closed = true; pending.clear(); cache.clear(); worker.shutdownNow(); }
}
