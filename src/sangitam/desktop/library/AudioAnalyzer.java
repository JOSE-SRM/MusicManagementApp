package sangitam.desktop.library;

import sangitam.desktop.model.TrackTags;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures loudness and short changes in the audio envelope. For longer songs,
 * it samples the opening and a later passage so a quiet intro does not define
 * the entire track. These are estimates of energy and movement, not genre or mood.
 */
public final class AudioAnalyzer {
    public TrackTags analyze(Path file) { return analyze(file, 0); }

    public TrackTags analyze(Path file, double durationSeconds) {
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
                    return measure(pcm, format, durationSeconds >= 50, size, modified);
                }
            }
        } catch (Exception unsupported) {
            return new TrackTags(50, 50, false, size, modified);
        }
    }

    private TrackTags measure(AudioInputStream pcm, AudioFormat format, boolean longTrack,
                              long size, long modified) throws java.io.IOException {
        int channels = format.getChannels();
        int frameSize = format.getFrameSize();
        int windowFrames = Math.max(1, Math.round(format.getSampleRate() / 20));
        long maxFrames = Math.round(format.getSampleRate() * (longTrack ? 36 : 24));
        long earlyEnd = Math.round(format.getSampleRate() * 8);
        long laterStart = Math.round(format.getSampleRate() * 20);
        byte[] buffer = new byte[8192 - 8192 % frameSize];
        double[] previous = new double[channels];
        List<Double> rmsWindows = new ArrayList<>();
        List<Double> changeWindows = new ArrayList<>();
        double squares = 0;
        double windowSquares = 0;
        double windowChanges = 0;
        long samples = 0;
        int windowSamples = 0;
        long frames = 0;
        boolean measuring = false;

        while (frames < maxFrames && !Thread.currentThread().isInterrupted()) {
            int read = pcm.read(buffer, 0, buffer.length);
            if (read < 0) break;
            for (int offset = 0; offset + frameSize <= read && frames < maxFrames; offset += frameSize) {
                boolean include = !longTrack || frames < earlyEnd || frames >= laterStart;
                if (measuring && !include && windowSamples > 0) {
                    rmsWindows.add(Math.sqrt(windowSquares / windowSamples));
                    changeWindows.add(Math.sqrt(windowChanges / windowSamples));
                    windowSquares = windowChanges = 0;
                    windowSamples = 0;
                }
                if (include && !measuring) java.util.Arrays.fill(previous, 0);
                measuring = include;
                if (include) {
                    for (int channel = 0; channel < channels; channel++) {
                        int index = offset + channel * 2;
                        short value = (short) (((buffer[index + 1] & 0xff) << 8) | (buffer[index] & 0xff));
                        double sample = value / 32768.0;
                        double square = sample * sample;
                        squares += square;
                        windowSquares += square;
                        double delta = sample - previous[channel];
                        windowChanges += delta * delta;
                        previous[channel] = sample;
                        samples++;
                        windowSamples++;
                    }
                    if (windowSamples >= windowFrames * channels) {
                        rmsWindows.add(Math.sqrt(windowSquares / windowSamples));
                        changeWindows.add(Math.sqrt(windowChanges / windowSamples));
                        windowSquares = windowChanges = 0;
                        windowSamples = 0;
                    }
                }
                frames++;
            }
        }
        if (samples == 0 || Thread.currentThread().isInterrupted()) return TrackTags.pending();
        if (windowSamples > 0) {
            rmsWindows.add(Math.sqrt(windowSquares / windowSamples));
            changeWindows.add(Math.sqrt(windowChanges / windowSamples));
        }
        double rms = Math.sqrt(squares / samples);
        double decibels = 20 * Math.log10(Math.max(rms, 0.000001));
        int loudness = bounded((decibels + 40) * 100 / 32);
        double sampledSeconds = samples / (format.getSampleRate() * channels);
        int activity = bounded(onsets(rmsWindows, changeWindows) / Math.max(0.1, sampledSeconds) * 38);
        int energy = bounded(loudness * 0.65 + activity * 0.35);
        return new TrackTags(energy, activity, true, size, modified);
    }

    private int onsets(List<Double> rms, List<Double> changes) {
        int count = 0;
        int last = -3;
        for (int i = 5; i < rms.size(); i++) {
            double baseRms = 0;
            double baseChange = 0;
            for (int previous = i - 5; previous < i; previous++) {
                baseRms += rms.get(previous);
                baseChange += changes.get(previous);
            }
            baseRms /= 5;
            baseChange /= 5;
            boolean volumeHit = rms.get(i) > 0.018 && rms.get(i) > baseRms * 1.16
                && rms.get(i) - baseRms > 0.006;
            boolean textureHit = changes.get(i) > 0.008 && changes.get(i) > baseChange * 1.22
                && changes.get(i) - baseChange > 0.003;
            if (i - last >= 2 && (volumeHit || textureHit)) { count++; last = i; }
        }
        return count;
    }

    private int bounded(double value) { return (int) Math.max(0, Math.min(100, Math.round(value))); }
}
