package sangitam.desktop.library;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Estimates local pulses from note attacks within each continuous audio passage. */
final class TempoEstimator {
    private static final int FFT = 1024;
    private static final int HOP = 128;
    private static final double[] WINDOW = new double[FFT];

    static {
        for (int i = 0; i < FFT; i++) WINDOW[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (FFT - 1));
    }

    record Estimate(double bpm, double low, double high, double evidence, int windows) { }
    private record Pulse(double bpm, double evidence) { }

    static Estimate estimate(List<SpectralFeatures.Passage> passages, double rate) throws InterruptedIOException {
        double framesPerSecond = rate / HOP;
        int window = (int) Math.round(framesPerSecond * 12);
        int hop = (int) Math.round(framesPerSecond * 6);
        var pulses = new ArrayList<Pulse>();
        int windows = 0;
        for (var passage : passages) {
            double[] novelty = spectralFlux(passage, rate);
            for (int start = 0; start + hop <= novelty.length; start += hop) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                Pulse pulse = localPulse(novelty, start, Math.min(novelty.length, start + window), framesPerSecond);
                windows++;
                if (pulse != null) pulses.add(pulse);
            }
        }
        if (pulses.isEmpty()) return new Estimate(0, 0, 0, 0, 0);
        pulses.sort(Comparator.comparingDouble(Pulse::bpm));
        int last = pulses.size() - 1;
        double center = pulses.get(pulses.size() / 2).bpm();
        double low = pulses.get((int) Math.floor(last * .2)).bpm();
        double high = pulses.get((int) Math.ceil(last * .8)).bpm();
        double evidence = pulses.stream().mapToDouble(Pulse::evidence).average().orElse(0)
            * Math.sqrt(pulses.size() / (double) windows);
        return new Estimate(center, low, high, evidence, pulses.size());
    }

    private static double[] spectralFlux(SpectralFeatures.Passage passage, double rate) throws InterruptedIOException {
        int frames = Math.max(0, 1 + Math.floorDiv(passage.length() - FFT, HOP));
        if (frames < 2) return new double[0];
        double[] real = new double[FFT], imaginary = new double[FFT];
        double[] prior = new double[FFT / 2 + 1], spectrum = new double[FFT / 2 + 1];
        double[] flux = new double[frames - 1];
        int first = Math.max(2, (int) Math.ceil(40 * FFT / rate));
        int last = Math.min(spectrum.length - 2, (int) Math.floor(5400 * FFT / rate));
        for (int frame = 0; frame < frames; frame++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int offset = frame * HOP;
            for (int i = 0; i < FFT; i++) real[i] = passage.signal()[offset + i] * WINDOW[i];
            Arrays.fill(imaginary, 0);
            SpectralFeatures.transform(real, imaginary);
            for (int bin = first - 1; bin <= last + 1; bin++) {
                spectrum[bin] = Math.log1p(12 * Math.sqrt(real[bin] * real[bin] + imaginary[bin] * imaginary[bin]));
            }
            if (frame > 0) {
                double onset = 0;
                for (int bin = first; bin <= last; bin++) {
                    // A small pitch shift in a sustained note should not count as a new attack.
                    double reference = Math.max(prior[bin], Math.max(prior[bin - 1], prior[bin + 1]));
                    onset += Math.max(0, spectrum[bin] - reference);
                }
                flux[frame - 1] = onset / (last - first + 1);
            }
            double[] swap = prior;
            prior = spectrum;
            spectrum = swap;
        }
        var smooth = new double[flux.length];
        for (int i = 1; i + 1 < flux.length; i++) smooth[i] = (flux[i - 1] + 2 * flux[i] + flux[i + 1]) / 4;
        return smooth;
    }

    private static Pulse localPulse(double[] flux, int start, int end, double rate) {
        int length = end - start;
        double mean = 0;
        for (int i = start; i < end; i++) mean += flux[i];
        mean /= length;
        var centered = new double[length];
        double power = 0;
        for (int i = 0; i < length; i++) {
            centered[i] = flux[start + i] - mean;
            power += centered[i] * centered[i];
        }
        if (power / length < .002 * .002) return null;

        int minimum = (int) Math.round(rate * 60 / 240);
        int maximum = (int) Math.round(rate * 60 / 40);
        double[] correlation = new double[3 * maximum + 2];
        for (int lag = minimum - 1; lag < correlation.length; lag++) {
            double dot = 0;
            for (int i = lag; i < length; i++) dot += centered[i] * centered[i - lag];
            correlation[lag] = dot / power;
        }
        int best = 0;
        double bestScore = 0;
        for (int lag = minimum; lag <= maximum; lag++) {
            if (correlation[lag] <= correlation[lag - 1] || correlation[lag] < correlation[lag + 1]) continue;
            double logRatio = Math.log(60 * rate / (lag * 110)) / Math.log(2);
            double prior = Math.exp(-.5 * Math.pow(logRatio / .8, 2));
            double recurrence = correlation[lag] + .4 * Math.max(0, correlation[2 * lag])
                + .2 * Math.max(0, correlation[3 * lag]);
            double score = recurrence * prior;
            if (score > bestScore) {
                best = lag;
                bestScore = score;
            }
        }
        if (bestScore < .17 || correlation[best] < Math.max(.12, 3 / Math.sqrt(length))) return null;
        double left = correlation[best - 1], middle = correlation[best], right = correlation[best + 1];
        double correction = .5 * (left - right) / (left - 2 * middle + right);
        return new Pulse(60 * rate / (best + correction), middle);
    }
}
