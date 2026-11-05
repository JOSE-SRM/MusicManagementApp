package sangitam.desktop.ui;

import org.jaudiotagger.audio.AudioFileIO;
import sangitam.desktop.model.Track;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Reads embedded covers on demand. The cache and pending queue have fixed bounds. */
final class LibraryArtwork {
    private static final int SIZE = 42;
    private final LinkedHashMap<Path, Icon> cache = new LinkedHashMap<>(160, 0.75f, true);
    private final Set<Path> pending = new HashSet<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(24), task -> {
            Thread thread = new Thread(task, "sangitam-cover-art");
            thread.setDaemon(true);
            return thread;
        });

    Icon forEntry(LibraryEntry entry, Set<Path> unavailable, Runnable repaint) {
        List<Track> tracks = entry.tracks();
        int sample = Math.min(3, tracks.size());
        for (int i = 0; i < sample; i++) {
            Path path = tracks.get(i).path();
            if (unavailable.contains(path)) continue;
            if (cache.containsKey(path)) {
                Icon found = cache.get(path);
                if (found != null) return found;
            } else {
                request(path, repaint);
                break;
            }
        }
        return null;
    }

    private void request(Path path, Runnable repaint) {
        if (worker.isShutdown() || pending.size() >= 24 || !pending.add(path)) return;
        worker.execute(() -> {
            Icon icon = read(path);
            SwingUtilities.invokeLater(() -> {
                pending.remove(path);
                cache.put(path, icon);
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
                    int sample = Math.max(1, Math.max(width, height) / (SIZE * 2));
                    var params = reader.getDefaultReadParam();
                    params.setSourceSubsampling(sample, sample, 0, 0);
                    var image = reader.read(0, params);
                    double scale = SIZE / (double) Math.max(image.getWidth(), image.getHeight());
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

    void close() { worker.shutdownNow(); }
}
