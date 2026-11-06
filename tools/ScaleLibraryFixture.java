import sangitam.desktop.library.AudioAnalyzer;
import sangitam.desktop.model.*;
import sangitam.desktop.storage.LibraryStore;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;

/** Generates an isolated indexed library and actual playable files for manual scale testing. */
class ScaleLibraryFixture {
    private static final int DIRECTORIES = 200;
    private static final int PER_DIRECTORY = 500;
    private static final int SAMPLE_RATE = 8000;
    private static final int SAMPLES = SAMPLE_RATE / 2;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(root); // Refuse to reuse or overwrite any existing fixture.
        Path music = Files.createDirectory(root.resolve("Music"));
        LibraryStore store = new LibraryStore(root.resolve("Data/library.db"));
        store.initialize();
        AudioAnalyzer analyzer = new AudioAnalyzer();
        int linked = 0;
        for (int folder = 0; folder < DIRECTORIES; folder++) {
            Path directory = Files.createDirectories(music.resolve("Shelf %02d".formatted(folder / 20)).resolve("Folder %03d".formatted(folder)));
            byte[] tone = tone(220 + folder % 32 * 12);
            Path original = directory.resolve("Track 000.wav");
            Files.write(original, tone, StandardOpenOption.CREATE_NEW);
            TrackTags measurement = analyzer.analyze(original, 0.5);
            if (!measurement.analyzed()) throw new IllegalStateException("Synthetic WAV could not be analysed");

            var tracks = new ArrayList<Track>(PER_DIRECTORY);
            var stamps = new HashMap<Path, FileStamp>();
            var tags = new HashMap<Path, TrackTags>();
            for (int index = 0; index < PER_DIRECTORY; index++) {
                Path path = directory.resolve("Track %03d.wav".formatted(index));
                if (index != 0) {
                    try { Files.createLink(path, original); linked++; }
                    catch (UnsupportedOperationException | java.io.IOException error) {
                        Files.copy(original, path, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
                long size = Files.size(path), modified = Files.getLastModifiedTime(path).toMillis();
                int number = folder * PER_DIRECTORY + index;
                var metadata = new TrackMetadata(Map.of(), Set.of("Scale fixture"), 128, SAMPLE_RATE, 16, "Mono", false, TrackMetadata.CURRENT_VERSION);
                var track = new Track(path, "Scale %06d".formatted(number), "Fixture Artist %04d".formatted(number / 100),
                    "Fixture Album %04d".formatted(number / 20), 0.5, "WAV", folder % 2 == 0 ? "Classical" : "Instrumental", metadata);
                var f = measurement.features();
                var features = new AudioFeatures(f.version(), size, modified, f.sampledSeconds(), f.rmsDb(), f.peakDb(), f.dynamicRangeDb(),
                    f.centroidHz(), f.bpm(), f.tempoConfidence(), f.key(), f.mode(), f.keyConfidence(), f.arousal(), f.valence(), f.mood(), f.confidence(), f.energyCurve());
                tracks.add(track);
                stamps.put(path, new FileStamp(size, modified));
                tags.put(path, new TrackTags(measurement.energy(), measurement.activity(), true, size, modified, features));
            }
            store.saveScan(tracks, stamps, Set.of(), folder == 0 ? List.of(new LibrarySource(music, true)) : List.of(), tags);
            if ((folder + 1) % 20 == 0) System.out.printf("Generated %,d / %,d WAV paths%n", (folder + 1) * PER_DIRECTORY, DIRECTORIES * PER_DIRECTORY);
        }
        long started = System.nanoTime();
        int catalogCount = store.loadCatalog().size();
        double loadMs = (System.nanoTime() - started) / 1_000_000d;
        long fileCount;
        try (var files = Files.walk(music)) { fileCount = files.filter(Files::isRegularFile).count(); }
        if (catalogCount != DIRECTORIES * PER_DIRECTORY || catalogCount != fileCount) throw new IllegalStateException("Fixture count mismatch");
        String summary = String.format(Locale.ROOT, "%d indexed WAV paths (%d hard links). Catalog decode %.1f ms.%n", catalogCount, linked, loadMs);
        Files.writeString(root.resolve("READY.txt"), summary);
        System.out.print(summary);
    }

    private static byte[] tone(double frequency) {
        var data = ByteBuffer.allocate(44 + SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[]{'R','I','F','F'}).putInt(36 + SAMPLES * 2).put(new byte[]{'W','A','V','E','f','m','t',' '});
        data.putInt(16).putShort((short) 1).putShort((short) 1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2);
        data.putShort((short) 2).putShort((short) 16).put(new byte[]{'d','a','t','a'}).putInt(SAMPLES * 2);
        for (int i = 0; i < SAMPLES; i++) {
            double fade = Math.min(1, Math.min(i, SAMPLES - 1 - i) / 80d);
            data.putShort((short) Math.round(fade * 2200 * Math.sin(2 * Math.PI * frequency * i / SAMPLE_RATE)));
        }
        return data.array();
    }
}
