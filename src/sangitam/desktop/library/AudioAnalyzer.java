package sangitam.desktop.library;

import sangitam.desktop.model.TrackTags;
import sangitam.desktop.model.TrackTags.Failure;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Measures bounded PCM passages independently of the playback engine.
 */
public final class AudioAnalyzer {
    private static final int PASSAGE_SECONDS = 36;
    private static final int PASSAGE_COUNT = 5;
    private static final int MAX_SAMPLED_SECONDS = PASSAGE_SECONDS * PASSAGE_COUNT;
    // Streaming decoders must traverse skipped PCM. Bound that work for unusually long files.
    private static final int MAX_STREAMED_SECONDS = 1800;
    public enum Sampling {
        DISTRIBUTED("Distributed passages", 0), OPENING_30("First 30 seconds", 30), OPENING_90("First 90 seconds", 90);
        private final String label;
        private final int seconds;
        Sampling(String label, int seconds) { this.label = label; this.seconds = seconds; }
        @Override public String toString() { return label; }
    }

    public TrackTags analyze(Path file) { return analyze(file, 0); }

    public TrackTags analyze(Path file, double durationSeconds) {
        return analyze(file, durationSeconds, Sampling.DISTRIBUTED, true);
    }

    public TrackTags analyze(Path file, double durationSeconds, Sampling sampling, boolean moodTags) {
        TrackTags measured = decode(file, durationSeconds, sampling);
        return moodTags && measured.analyzed() ? measured.withMoodTags(MoodTags.suggest(measured.features())) : measured;
    }

    private TrackTags decode(Path file, double durationSeconds, Sampling sampling) {
        long size = -1;
        long modified = -1;
        try {
            var attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) return TrackTags.failed(size, modified, Failure.UNAVAILABLE);
            size = attributes.size();
            modified = attributes.lastModifiedTime().toMillis();
            if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".flac")) {
                try {
                    return analyzeFlac(file, durationSeconds, sampling, size, modified);
                } catch (EOFException | IllegalArgumentException | IndexOutOfBoundsException malformed) {
                    return TrackTags.failed(size, modified, Failure.DAMAGED_FLAC);
                }
            }
            try (AudioInputStream source = AudioSystem.getAudioInputStream(file.toFile())) {
                AudioFormat input = source.getFormat();
                if (input.getChannels() < 1 || input.getChannels() > 32 || input.getSampleRate() < 4000
                    || input.getSampleRate() > 384000) return TrackTags.failed(size, modified, Failure.UNSUPPORTED_PCM);
                AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    input.getSampleRate(), 16, input.getChannels(), input.getChannels() * 2,
                    input.getSampleRate(), false);
                if (!AudioSystem.isConversionSupported(format, input)) {
                    return TrackTags.failed(size, modified, Failure.UNSUPPORTED_PCM);
                }
                try (AudioInputStream pcm = AudioSystem.getAudioInputStream(format, source)) {
                    if (sampling.seconds > 0) return opening(pcm, sampling.seconds, size, modified);
                    return analyzeStream(pcm, durationSeconds, size, modified);
                }
            }
        } catch (FlacPcmInputStream.DecodeFailure error) {
            return TrackTags.failed(size, modified, error.failure());
        } catch (UnsupportedAudioFileException error) {
            return TrackTags.failed(size, modified, Failure.UNSUPPORTED_FORMAT);
        } catch (NoSuchFileException error) {
            return TrackTags.failed(size, modified, Failure.UNAVAILABLE);
        } catch (IOException | SecurityException error) {
            return Thread.currentThread().isInterrupted() ? TrackTags.pending()
                : TrackTags.failed(size, modified, Failure.READ_ERROR);
        } catch (RuntimeException error) {
            return TrackTags.failed(size, modified, Failure.ANALYSIS_ERROR);
        }
    }

    private TrackTags analyzeFlac(Path file, double duration, Sampling sampling, long size, long modified) throws IOException {
        try (var input = new FlacPcmInputStream.SeekableFile(file)) {
            var stream = new FlacPcmInputStream(input);
            AudioInputStream pcm = stream.pcm();
            if (sampling.seconds > 0) return opening(pcm, sampling.seconds, size, modified);
            float rate = pcm.getFormat().getSampleRate();
            if (stream.frameCount() <= Math.round(rate * MAX_SAMPLED_SECONDS)) {
                return measure(pcm, duration >= 50 || stream.frameCount() >= rate * 50, size, modified);
            }
            int passageFrames = Math.round(rate * PASSAGE_SECONDS);
            List<Measurement> passages = new ArrayList<>();
            for (int index = 0; index < PASSAGE_COUNT; index++) {
                long start = passageStart(stream.frameCount(), passageFrames, index);
                if (index > 0) {
                    try {
                        long landed = stream.seek(input, start);
                        pcm = stream.pcm();
                        long bytes = (start - landed) * pcm.getFormat().getFrameSize();
                        if (pcm.skip(bytes) != bytes) throw new EOFException();
                    } catch (InterruptedIOException cancelled) {
                        throw cancelled;
                    } catch (IOException seekFailed) {
                        // Some FLAC streams cannot be positioned reliably. Restart from the opening.
                        break;
                    }
                }
                passages.add(readPassage(pcm, passageFrames, false));
            }
            if (passages.size() == PASSAGE_COUNT) return summarize(passages, pcm.getFormat(), size, modified);
        }
        try (var input = Files.newInputStream(file); var pcm = new FlacPcmInputStream(input).pcm()) {
            return measure(pcm, true, size, modified);
        }
    }

    private TrackTags opening(AudioInputStream pcm, int seconds, long size, long modified) throws IOException {
        var format = pcm.getFormat();
        return summarize(List.of(readPassage(pcm, Math.round(format.getSampleRate() * seconds), false)),
            format, size, modified);
    }

    private TrackTags analyzeStream(AudioInputStream pcm, double duration, long size, long modified) throws IOException {
        if (!Double.isFinite(duration) || duration <= MAX_SAMPLED_SECONDS) {
            return measure(pcm, duration >= 50, size, modified);
        }
        AudioFormat format = pcm.getFormat();
        int passageFrames = Math.round(format.getSampleRate() * PASSAGE_SECONDS);
        long end = Math.round(format.getSampleRate() * Math.min(duration, MAX_STREAMED_SECONDS));
        List<Measurement> passages = new ArrayList<>();
        long position = 0;
        byte[] discard = new byte[65536 - 65536 % format.getFrameSize()];
        for (int index = 0; index < PASSAGE_COUNT; index++) {
            long start = passageStart(end, passageFrames, index);
            if (!discardFrames(pcm, discard, start - position)) break;
            Measurement passage = readPassage(pcm, passageFrames, false);
            if (passage.frames() == 0) break;
            passages.add(passage);
            position = start + passage.frames();
            if (passage.frames() < passageFrames) break;
        }
        return summarize(passages, format, size, modified);
    }

    private long passageStart(long totalFrames, int passageFrames, int index) {
        return (totalFrames - passageFrames) * index / (PASSAGE_COUNT - 1);
    }

    private boolean discardFrames(AudioInputStream pcm, byte[] buffer, long frames) throws IOException {
        long bytes = frames * pcm.getFormat().getFrameSize();
        while (bytes > 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int read = pcm.read(buffer, 0, (int) Math.min(bytes, buffer.length));
            if (read < 0) return false;
            bytes -= read;
        }
        return true;
    }

    private TrackTags measure(AudioInputStream pcm, boolean longTrack, long size, long modified) throws IOException {
        var format = pcm.getFormat();
        long frameLimit = Math.round(format.getSampleRate() * (longTrack ? MAX_SAMPLED_SECONDS : 90));
        return summarize(List.of(readPassage(pcm, frameLimit, longTrack)), format, size, modified);
    }

    private record Measurement(SpectralFeatures.Passage passage, long frames, double squares, double peak,
                               double energySquares, long energySamples, int onsets) { }

    private Measurement readPassage(AudioInputStream pcm, long maxFrames, boolean skipQuietIntro) throws IOException {
        AudioFormat format = pcm.getFormat();
        int channels = format.getChannels();
        int frameSize = format.getFrameSize();
        int windowFrames = Math.max(1, Math.round(format.getSampleRate() / 20));
        int stride = spectralStride(format);
        float[] mono = new float[(int)(maxFrames / stride)];
        int captured = 0;
        double downsample = 0;
        int downsampleCount = 0;
        long earlyEnd = Math.round(format.getSampleRate() * 8);
        long laterStart = Math.round(format.getSampleRate() * 20);
        byte[] buffer = new byte[8192 - 8192 % frameSize];
        double[] previous = new double[channels];
        List<Double> rmsWindows = new ArrayList<>();
        List<Double> changeWindows = new ArrayList<>();
        List<Double> continuousEnvelope = new ArrayList<>();
        double envelopeSquares = 0;
        int envelopeFrames = 0;
        double squares = 0;
        double peak = 0;
        double energySquares = 0;
        double windowSquares = 0;
        double windowChanges = 0;
        long energySamples = 0;
        int windowSamples = 0;
        long frames = 0;
        boolean measuring = false;
        int onsetCount = 0;

        while (frames < maxFrames) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int read = pcm.read(buffer, 0, (int) Math.min(buffer.length, (maxFrames - frames) * frameSize));
            if (read < 0) break;
            for (int offset = 0; offset + frameSize <= read; offset += frameSize) {
                boolean include = !skipQuietIntro || frames < earlyEnd || frames >= laterStart;
                if (measuring && !include) {
                    if (windowSamples > 0) {
                        rmsWindows.add(Math.sqrt(windowSquares / windowSamples));
                        changeWindows.add(Math.sqrt(windowChanges / windowSamples));
                    }
                    onsetCount += onsets(rmsWindows, changeWindows);
                    rmsWindows.clear();
                    changeWindows.clear();
                    windowSquares = windowChanges = 0;
                    windowSamples = 0;
                }
                if (include && !measuring) Arrays.fill(previous, 0);
                measuring = include;
                double mix = 0;
                for (int channel = 0; channel < channels; channel++) {
                    int index = offset + channel * 2;
                    short value = (short) (((buffer[index + 1] & 0xff) << 8) | (buffer[index] & 0xff));
                    double sample = value / 32768.0;
                    double square = sample * sample;
                    mix += sample;
                    squares += square;
                    peak = Math.max(peak, Math.abs(sample));
                    envelopeSquares += square;
                    if (include) {
                        energySquares += square;
                        windowSquares += square;
                        double delta = sample - previous[channel];
                        windowChanges += delta * delta;
                        previous[channel] = sample;
                        energySamples++;
                        windowSamples++;
                    }
                }
                if (windowSamples >= windowFrames * channels) {
                    rmsWindows.add(Math.sqrt(windowSquares / windowSamples));
                    changeWindows.add(Math.sqrt(windowChanges / windowSamples));
                    windowSquares = windowChanges = 0;
                    windowSamples = 0;
                }
                if (++envelopeFrames == windowFrames) {
                    continuousEnvelope.add(Math.sqrt(envelopeSquares / ((long) envelopeFrames * channels)));
                    envelopeSquares = 0;
                    envelopeFrames = 0;
                }
                downsample += mix / channels;
                if (++downsampleCount == stride) {
                    mono[captured++] = (float) (downsample / stride);
                    downsample = 0;
                    downsampleCount = 0;
                }
                frames++;
            }
        }
        if (windowSamples > 0) {
            rmsWindows.add(Math.sqrt(windowSquares / windowSamples));
            changeWindows.add(Math.sqrt(windowChanges / windowSamples));
        }
        if (envelopeFrames > 0) {
            continuousEnvelope.add(Math.sqrt(envelopeSquares / ((long) envelopeFrames * channels)));
        }
        return new Measurement(new SpectralFeatures.Passage(mono, captured, continuousEnvelope), frames,
            squares, peak, energySquares, energySamples, onsetCount + onsets(rmsWindows, changeWindows));
    }

    private TrackTags summarize(List<Measurement> measured, AudioFormat format, long size, long modified)
        throws InterruptedIOException {
        long frames = measured.stream().mapToLong(Measurement::frames).sum();
        if (frames == 0) return TrackTags.failed(size, modified, Failure.NO_AUDIO);
        int channels = format.getChannels();
        double energySquares = measured.stream().mapToDouble(Measurement::energySquares).sum();
        long energySamples = measured.stream().mapToLong(Measurement::energySamples).sum();
        double decibels = SpectralFeatures.db(Math.sqrt(energySquares / Math.max(1, energySamples)));
        int loudness = bounded((decibels + 40) * 100 / 32);
        double activitySeconds = energySamples / ((double) format.getSampleRate() * channels);
        int activity = bounded(measured.stream().mapToInt(Measurement::onsets).sum() / Math.max(0.1, activitySeconds) * 38);
        int energy = bounded(loudness * 0.65 + activity * 0.35);
        double rms = Math.sqrt(measured.stream().mapToDouble(Measurement::squares).sum() / (frames * channels));
        double peak = measured.stream().mapToDouble(Measurement::peak).max().orElse(0);
        double rate = format.getSampleRate();
        var features = SpectralFeatures.measure(measured.stream().map(Measurement::passage).toList(),
            rate / spectralStride(format), frames / rate,
            rms, peak, energy, activity, size, modified);
        return features.version() == 0 ? TrackTags.pending() : new TrackTags(energy, activity, true, size, modified, features);
    }

    private int spectralStride(AudioFormat format) { return Math.max(1, Math.round(format.getSampleRate() / 11025)); }

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
