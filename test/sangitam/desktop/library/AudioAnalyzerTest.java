package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.TrackTags;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Generated audio proves both formats are decoded and measured without using copyrighted songs. */
class AudioAnalyzerTest {
    @TempDir Path folder;

    @Test void measuresLoudnessAndDecodesMp3() throws Exception {
        Path quiet = writeWav("quiet.wav", 0);
        Path loud = writeWav("loud.wav", 20_000);
        AudioAnalyzer analyzer = new AudioAnalyzer();
        TrackTags quietTags = analyzer.analyze(quiet);
        TrackTags loudTags = analyzer.analyze(loud);
        assertTrue(quietTags.analyzed());
        assertTrue(loudTags.analyzed());
        assertTrue(loudTags.energy() > quietTags.energy() + 30);
        assertTrue(loudTags.matches(Files.size(loud), Files.getLastModifiedTime(loud).toMillis()));

        Path mp3 = folder.resolve("proof.mp3");
        try (var source = getClass().getResourceAsStream("/audio/proof.mp3")) {
            assertNotNull(source);
            Files.copy(source, mp3);
        }
        assertTrue(analyzer.analyze(mp3).analyzed(), "MP3SPI should decode the MP3 fixture");
    }

    @Test void detectsMoreActivityInRepeatedBurstsThanAContinuousTone() throws Exception {
        Path steady = writeWav("steady.wav", 18_000);
        Path pulsed = writePulseWav();
        AudioAnalyzer analyzer = new AudioAnalyzer();
        TrackTags rhythmic = analyzer.analyze(pulsed);
        assertTrue(rhythmic.activity() > analyzer.analyze(steady).activity());
        assertEquals("Charged", rhythmic.energyLabel());
        assertEquals("Busy movement", rhythmic.activityLabel());
    }

    @Test void measuresFlacAtDifferentBitDepthsAndReportsTruncation() throws Exception {
        var analyzer = new AudioAnalyzer();
        for (String name : new String[]{"tone-16-mono.flac", "tone-24-stereo.flac"}) {
            Path file = folder.resolve(name);
            try (var source = getClass().getResourceAsStream("/audio/" + name)) {
                assertNotNull(source);
                Files.copy(source, file);
            }
            TrackTags measured = analyzer.analyze(file);
            assertTrue(measured.analyzed(), () -> name + " failed with " + measured.failure());
            assertEquals(0.5, measured.features().sampledSeconds(), 0.01);
            assertTrue(measured.features().rmsDb() > -30, "PCM should contain an audible tone");

            byte[] original = Files.readAllBytes(file);
            Files.write(file, Arrays.copyOf(original, original.length - 100));
            TrackTags truncated = analyzer.analyze(file);
            assertFalse(truncated.analyzed());
            assertEquals(TrackTags.Failure.DAMAGED_FLAC, truncated.failure());
        }
    }

    @Test void distinguishesUnrecognizedAudioFromAnUnavailableFile() throws Exception {
        var analyzer = new AudioAnalyzer();
        Path file = folder.resolve("unrecognized.bin");
        assertEquals(TrackTags.Failure.UNAVAILABLE, analyzer.analyze(file).failure());
        Files.writeString(file, "Plain text");
        assertEquals(TrackTags.Failure.UNSUPPORTED_FORMAT, analyzer.analyze(file).failure());
    }

    @Test void longFlacSamplesTheEndingAndMeasuresOriginalChannelLevels() throws Exception {
        Path file = folder.resolve("late-signal.flac");
        try (var source = getClass().getResourceAsStream("/audio/late-signal.flac")) {
            assertNotNull(source);
            Files.copy(source, file);
        }
        var features = new AudioAnalyzer().analyze(file, 200).features();
        assertEquals(5, features.passageCount());
        assertEquals(180, features.sampledSeconds(), .01);
        assertEquals(-12.04, features.peakDb(), .02);
        assertEquals(-21.58, features.rmsDb(), .05);
        assertTrue(features.key().isEmpty());
    }

    @Test void streamedPcmSamplesTheEndingBeyondTheOpeningBudget() throws Exception {
        int rate = 8000;
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        byte[] samples = new byte[200 * rate * 2];
        for (int offset = 180 * rate * 2; offset < samples.length; offset += 2) samples[offset + 1] = 32;
        Path file = folder.resolve("late-signal.wav");
        try (var pcm = new AudioInputStream(new ByteArrayInputStream(samples), format, samples.length / 2)) {
            AudioSystem.write(pcm, AudioFileFormat.Type.WAVE, file.toFile());
        }
        var features = new AudioAnalyzer().analyze(file, 200).features();
        assertEquals(5, features.passageCount());
        assertEquals(180, features.sampledSeconds(), .01);
        assertEquals(-12.04, features.peakDb(), .02);
        assertEquals(-21.58, features.rmsDb(), .05);
    }

    @Test void laterRhythmicPassageIsNotHiddenByQuietIntro() throws Exception {
        AudioFormat format = new AudioFormat(8_000, 16, 1, true, false);
        byte[] bytes = new byte[36 * 8_000 * 2];
        for (int sample = 20 * 8_000; sample < 36 * 8_000; sample++) {
            boolean audible = (sample / 1_600) % 2 == 0;
            short value = audible ? (short) (20_000 * Math.sin(sample * 2 * Math.PI * 440 / 8_000)) : 0;
            bytes[sample * 2] = (byte) value;
            bytes[sample * 2 + 1] = (byte) (value >>> 8);
        }
        Path song = folder.resolve("long-intro.wav");
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(bytes),
            format, bytes.length / format.getFrameSize())) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, song.toFile());
        }
        TrackTags tags = new AudioAnalyzer().analyze(song, 60);
        assertTrue(tags.activity() >= 40, "later repeated beats should have visible movement");
    }

    private Path writePulseWav() throws Exception {
        AudioFormat format = new AudioFormat(44_100, 16, 1, true, false);
        byte[] bytes = new byte[44_100 * 2 * 2];
        for (int sample = 0; sample < 88_200; sample++) {
            boolean audible = (sample / 8_820) % 2 == 0;
            short value = audible ? (short) (18_000 * Math.sin(sample * 2 * Math.PI * 440 / 44_100)) : 0;
            bytes[sample * 2] = (byte) value;
            bytes[sample * 2 + 1] = (byte) (value >>> 8);
        }
        Path output = folder.resolve("pulsed.wav");
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(bytes),
            format, bytes.length / format.getFrameSize())) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
        return output;
    }

    private Path writeWav(String name, int amplitude) throws Exception {
        AudioFormat format = new AudioFormat(44_100, 16, 1, true, false);
        byte[] bytes = new byte[44_100 * 2];
        for (int sample = 0; sample < 44_100; sample++) {
            short value = (short) (amplitude * Math.sin(sample * 2 * Math.PI * 440 / 44_100));
            bytes[sample * 2] = (byte) value;
            bytes[sample * 2 + 1] = (byte) (value >>> 8);
        }
        Path output = folder.resolve(name);
        try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(bytes),
            format, bytes.length / format.getFrameSize())) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, output.toFile());
        }
        return output;
    }
}
