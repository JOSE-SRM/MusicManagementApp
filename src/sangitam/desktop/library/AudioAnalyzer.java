package sangitam.desktop.library;

import sangitam.desktop.model.TrackTags;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Samples at most 24 seconds of decoded PCM away from Swing's event thread.
 * MP3SPI supplies MP3 decoding to Java Sound; the JDK handles WAV. The two
 * scores describe loudness and changes in loudness, not mood or a precise BPM.
 */
public final class AudioAnalyzer {
    private static final int SAMPLE_SECONDS = 24;

    public TrackTags analyze(Path file) {
        long size = -1;
        long modified = -1;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
            try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile())) {
                AudioFormat input = source.getFormat();
                AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    input.getSampleRate(), 16, input.getChannels(), input.getChannels() * 2,
                    input.getSampleRate(), false);
                try (AudioInputStream pcm = AudioSystem.getAudioInputStream(format, source)) {
                    return measure(pcm, format, size, modified);
                }
            }
        } catch (Exception unsupported) {
            // A readable file can still have a decoder problem. Keep it playable in
            // the library and expose that its audio traits were not measured.
            return new TrackTags(50, 50, false, size, modified);
        }
    }

    private TrackTags measure(AudioInputStream pcm, AudioFormat format, long size, long modified)
        throws java.io.IOException {
        int channels = format.getChannels();
        int frameSize = format.getFrameSize();
        int windowFrames = Math.max(1, Math.round(format.getSampleRate() / 5));
        long maxFrames = Math.round(format.getSampleRate() * SAMPLE_SECONDS);
        byte[] buffer = new byte[8192 - 8192 % frameSize];
        long frames = 0;
        long samples = 0;
        double sumSquares = 0;
        double windowSquares = 0;
        int windowSamples = 0;
        double previousRms = 0;
        int onsets = 0;

        while (frames < maxFrames && !Thread.currentThread().isInterrupted()) {
            int read = pcm.read(buffer, 0, buffer.length);
            if (read < 0) break;
            for (int offset = 0; offset + frameSize <= read && frames < maxFrames; offset += frameSize) {
                for (int channel = 0; channel < channels; channel++) {
                    int byteIndex = offset + channel * 2;
                    short value = (short) (((buffer[byteIndex + 1] & 0xff) << 8)
                        | (buffer[byteIndex] & 0xff));
                    double normalized = value / 32768.0;
                    double square = normalized * normalized;
                    sumSquares += square;
                    windowSquares += square;
                    samples++;
                    windowSamples++;
                }
                frames++;
                if (frames % windowFrames == 0) {
                    double rms = Math.sqrt(windowSquares / Math.max(1, windowSamples));
                    if (rms > 0.025 && (previousRms < 0.01 || rms > previousRms * 1.35)) onsets++;
                    previousRms = rms;
                    windowSquares = 0;
                    windowSamples = 0;
                }
            }
        }
        if (samples == 0 || Thread.currentThread().isInterrupted()) return TrackTags.pending();
        double rms = Math.sqrt(sumSquares / samples);
        double decibels = 20 * Math.log10(Math.max(rms, 0.000001));
        int loudness = bounded((decibels + 42) * 100 / 33);
        double seconds = frames / format.getSampleRate();
        int activity = bounded(onsets / Math.max(0.1, seconds) * 20);
        int energy = bounded(loudness * 0.75 + activity * 0.25);
        return new TrackTags(energy, activity, true, size, modified);
    }

    private int bounded(double value) { return (int) Math.max(0, Math.min(100, Math.round(value))); }
}
