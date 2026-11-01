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
        assertTrue(analyzer.analyze(pulsed).activity() > analyzer.analyze(steady).activity());
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
