package sangitam.desktop.library;

import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.mp3.MP3File;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.KeyNotFoundException;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;

/** Rewrites tags on a staged private copy and publishes only after reading the result back. */
final class ManagedAudioTags {
    private static final Map<Field, FieldKey> FIELDS = Map.ofEntries(
        Map.entry(Field.COMPOSER, FieldKey.COMPOSER), Map.entry(Field.WORK, FieldKey.WORK),
        Map.entry(Field.MOVEMENT, FieldKey.MOVEMENT), Map.entry(Field.OPUS, FieldKey.OPUS),
        Map.entry(Field.PERIOD, FieldKey.PERIOD), Map.entry(Field.FORM, FieldKey.WORK_TYPE),
        Map.entry(Field.KEY, FieldKey.KEY), Map.entry(Field.CONDUCTOR, FieldKey.CONDUCTOR),
        Map.entry(Field.ORCHESTRA, FieldKey.ORCHESTRA), Map.entry(Field.RECORDING_DATE, FieldKey.RECORDINGDATE));

    private ManagedAudioTags() { }

    static boolean write(Track track) throws IOException {
        Path path = track.path();
        String extension = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!extension.endsWith(".flac") && !extension.endsWith(".mp3")) return false;
        if (!path.equals(path.toRealPath())) throw new IOException("The managed file resolves outside its catalog path.");
        var originalStamp = ActiveManagedLibrary.stamp(path);
        Path stage = null;
        try {
            var original = AudioFileIO.read(path.toFile());
            var tag = original.getTagOrCreateDefault();
            var values = new EnumMap<FieldKey, String>(FieldKey.class);
            values.put(FieldKey.TITLE, track.title());
            if (!track.artist().equalsIgnoreCase("Unknown artist")) values.put(FieldKey.ARTIST, track.artist());
            if (!track.album().equalsIgnoreCase("Unknown album")) values.put(FieldKey.ALBUM, track.album());
            FIELDS.forEach((field, key) -> values.put(key, track.metadata().text(field)));
            values.entrySet().removeIf(entry -> entry.getValue().isBlank() || same(tag, entry.getKey(), entry.getValue()));
            if (values.isEmpty()) return false;
            stage = Files.createTempFile(path.getParent(), ".sangitam-tags-", extension.endsWith(".flac") ? ".flac" : ".mp3");
            Files.copy(path, stage, StandardCopyOption.REPLACE_EXISTING);
            var edited = AudioFileIO.read(stage.toFile());
            var target = edited.getTagOrCreateAndSetDefault();
            var written = new EnumMap<FieldKey, String>(FieldKey.class);
            for (var field : values.entrySet()) {
                try {
                    target.setField(field.getKey(), field.getValue());
                    written.put(field.getKey(), field.getValue());
                } catch (KeyNotFoundException unsupported) {
                    // Some ID3 variants lack fields that FLAC supports.
                }
            }
            if (written.isEmpty()) return false;
            edited.commit();
            var readback = AudioFileIO.read(stage.toFile());
            if (readback.getTag() == null || written.entrySet().stream()
                .anyMatch(field -> !same(readback.getTag(), field.getKey(), field.getValue()))
                || !sameAudio(original, readback)) {
                throw new IOException("The rewritten audio tags could not be verified.");
            }
            if (!originalStamp.equals(ActiveManagedLibrary.stamp(path)))
                throw new IOException("The managed file changed while tags were being written.");
            Files.move(stage, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Could not write audio tags. " + failure.getMessage(), failure);
        } finally {
            if (stage != null) Files.deleteIfExists(stage);
        }
    }

    private static boolean sameAudio(AudioFile original, AudioFile edited) throws IOException {
        try (var before = new RandomAccessFile(original.getFile(), "r");
             var after = new RandomAccessFile(edited.getFile(), "r")) {
            long size = audioRegion(original, before);
            if (size != audioRegion(edited, after)) return false;
            byte[] left = new byte[128 * 1024];
            byte[] right = new byte[left.length];
            while (size > 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Tag verification was stopped.");
                int count = (int) Math.min(size, left.length);
                before.readFully(left, 0, count);
                after.readFully(right, 0, count);
                if (!java.util.Arrays.equals(left, 0, count, right, 0, count)) return false;
                size -= count;
            }
            return true;
        }
    }

    private static long audioRegion(AudioFile audio, RandomAccessFile data) throws IOException {
        long end = data.length();
        if (audio instanceof MP3File mp3) {
            if (mp3.hasID3v1Tag()) end -= 128;
            data.seek(mp3.getMP3AudioHeader().getMp3StartByte());
        } else {
            if (data.readInt() != 0x664c6143) throw new IOException("Could not locate the FLAC audio stream.");
            int block;
            do {
                block = data.readInt();
                data.seek(data.getFilePointer() + (block & 0xffffff));
            } while ((block & 0x80000000) == 0);
        }
        long size = end - data.getFilePointer();
        if (size <= 0) throw new IOException("Could not verify the encoded audio.");
        return size;
    }

    private static boolean same(org.jaudiotagger.tag.Tag tag, FieldKey key, String expected) {
        try { return expected.equals(tag.getFirst(key)); }
        catch (KeyNotFoundException unavailable) { return false; }
    }
}
