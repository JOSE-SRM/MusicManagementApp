package sangitam.desktop.library;

import sangitam.desktop.model.AudioFeatures;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Combines spectral and envelope evidence from separately decoded passages. */
final class SpectralFeatures {
    private static final double[] MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    private static final double[] MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
    // Temperley's 1999 profiles give the leading tone more weight in tonal music.
    private static final double[] TONAL_MAJOR = {5, 2, 3.5, 2, 4.5, 4, 2, 4.5, 2, 3.5, 1.5, 4};
    private static final double[] TONAL_MINOR = {5, 2, 3.5, 4.5, 2, 4, 2, 4.5, 3.5, 2, 1.5, 4};
    private static final String[] NOTES = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
    private static final int FFT = 4096;
    private static final double[] WINDOW = new double[FFT];

    static {
        for (int i = 0; i < FFT; i++) WINDOW[i] = .5 - .5 * Math.cos(2 * Math.PI * i / (FFT - 1));
    }

    record Passage(float[] signal, int length, List<Double> envelope) { }
    private record Key(int index, double evidence, List<String> candidates) { }

    static AudioFeatures measure(List<Passage> passages, double rate, double seconds,
                                 double rms, double peak, int energy, int activity, long size, long modified)
        throws InterruptedIOException {
        List<double[]> tonalScores = new ArrayList<>();
        List<Double> envelope = new ArrayList<>();
        double[] opening = new double[12], closing = new double[12], frameChroma = new double[12];
        int edgeLength = (int) Math.round(rate * 8);
        Passage last = passages.getLast();
        boolean useEdges = passages.size() > 1 || last.length() > edgeLength * 3;
        int closingEnd = useEdges ? audibleEnd(last, rate, peak) : 0;
        double powerSum = 0;
        double weightedFrequency = 0;
        double[] real = new double[FFT], imaginary = new double[FFT], power = new double[FFT / 2 + 1];
        for (Passage passage : passages) {
            int sectionSamples = Math.max(FFT, (int) Math.round(rate * 36));
            for (int sectionStart = 0; sectionStart < passage.length(); sectionStart += sectionSamples) {
                int end = Math.min(passage.length(), sectionStart + sectionSamples);
                double[] chroma = new double[12];
                for (int start = sectionStart; start + FFT <= end; start += FFT / 2) {
                    if (Thread.currentThread().isInterrupted()) return AudioFeatures.empty();
                    for (int i = 0; i < FFT; i++) {
                        real[i] = passage.signal()[start + i] * WINDOW[i];
                        imaginary[i] = 0;
                    }
                    transform(real, imaginary);
                    double maxPower = 0;
                    for (int bin = 0; bin < power.length; bin++) {
                        double value = real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
                        power[bin] = value;
                        if (bin > 0) {
                            weightedFrequency += bin * rate / FFT * value;
                            powerSum += value;
                            maxPower = Math.max(maxPower, value);
                        }
                    }
                    Arrays.fill(frameChroma, 0);
                    double strength = addPeaks(power, rate, maxPower, frameChroma);
                    if (strength <= 0) continue;
                    for (int pitch = 0; pitch < 12; pitch++) {
                        // Sustained loud passages should not drown out quieter tonal material.
                        double value = frameChroma[pitch] / strength;
                        chroma[pitch] += value;
                        if (useEdges && passage == passages.getFirst() && start < edgeLength) opening[pitch] += value;
                        if (passage == last && start >= closingEnd - edgeLength && start < closingEnd) closing[pitch] += value;
                    }
                }
                if (hasTonalMaterial(chroma)) tonalScores.add(scoreKeys(chroma));
            }
            envelope.addAll(passage.envelope());
        }

        Key key = key(tonalScores, opening, closing);
        TempoEstimator.Estimate tempo = TempoEstimator.estimate(passages, rate);
        var sorted = new ArrayList<>(envelope);
        sorted.sort(null);
        double low = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 10);
        double high = sorted.isEmpty() ? 0 : sorted.get(sorted.size() * 9 / 10);
        double dynamics = 20 * Math.log10(Math.max(1e-5, high) / Math.max(1e-5, low));
        int arousal = bound(energy * .7 + activity * .2 + (tempo.bpm() > 0 ? (tempo.bpm() - 60) / 2 : energy) * .1);
        String mood = rms < .001 ? "Unknown" : arousal < 34 ? "Calm" : arousal > 68 ? "Energetic" : "Balanced";
        String note = key.index() < 0 ? "" : NOTES[key.index() / 2];
        String mode = key.index() < 0 ? "" : key.index() % 2 == 0 ? "major" : "minor";
        return new AudioFeatures(AudioFeatures.VERSION, size, modified, seconds, db(rms), db(peak), dynamics,
            powerSum == 0 ? 0 : weightedFrequency / powerSum, tempo.bpm(), tempo.evidence(),
            note, mode, key.evidence(), arousal, 50, mood, 0, energyCurve(envelope), passages.size(),
            tempo.low(), tempo.high(), tempo.windows(), key.candidates());
    }

    private static int audibleEnd(Passage passage, double rate, double peak) {
        int block = Math.max(1, (int) Math.round(rate / 10));
        double minimumRms = Math.max(.001, peak * .01);
        for (int end = passage.length(); end > 0; end -= block) {
            int start = Math.max(0, end - block);
            double power = 0;
            for (int i = start; i < end; i++) power += passage.signal()[i] * passage.signal()[i];
            if (power > minimumRms * minimumRms * (end - start)) return end;
        }
        return 0;
    }

    static double addPeaks(double[] power, double rate, double maxPower, double[] chroma) {
        if (maxPower < .01) return 0;
        double threshold = Math.max(1e-4, maxPower * .0004);
        int firstBin = Math.max(1, (int) (45 * FFT / rate));
        int lastBin = Math.min(power.length - 2, (int) Math.ceil(4000 * FFT / rate));
        double sum = 0;
        for (int bin = firstBin; bin <= lastBin; bin++) {
            double value = power[bin];
            if (value <= threshold || value <= power[bin - 1] || value < power[bin + 1]) continue;
            double left = Math.log(Math.max(1e-20, power[bin - 1]));
            double middle = Math.log(value);
            double right = Math.log(Math.max(1e-20, power[bin + 1]));
            double correction = .5 * (left - right) / (left - 2 * middle + right);
            double hz = (bin + correction) * rate / FFT;
            int midi = (int) Math.round(69 + 12 * Math.log(hz / 440) / Math.log(2));
            int pitch = Math.floorMod(midi, 12);
            double strength = Math.exp(.5 * (middle - .25 * (left - right) * correction));
            chroma[pitch] += strength;
            sum += strength;
        }
        return sum;
    }

    private static boolean hasTonalMaterial(double[] chroma) {
        double peak = Arrays.stream(chroma).max().orElse(0);
        return Arrays.stream(chroma).filter(value -> value > peak * .12).count() >= 3;
    }

    private static double[] scoreKeys(double[] chroma) {
        double[] scores = new double[24];
        for (int index = 0; index < scores.length; index++) {
            double[] profile = index % 2 == 0 ? MAJOR : MINOR;
            double[] tonal = index % 2 == 0 ? TONAL_MAJOR : TONAL_MINOR;
            scores[index] = .3 * correlation(chroma, profile, index / 2) + .7 * correlation(chroma, tonal, index / 2);
        }
        return scores;
    }

    private static Key key(List<double[]> sections, double[] opening, double[] closing) {
        if (sections.isEmpty()) return new Key(-1, 0, List.of());
        double[] average = new double[24];
        for (double[] section : sections) {
            for (int i = 0; i < average.length; i++) average[i] += section[i] / sections.size();
        }
        int sharedEdge = -1;
        if (hasTonalMaterial(opening) && hasTonalMaterial(closing)) {
            double[] first = scoreKeys(opening), last = scoreKeys(closing);
            int firstKey = bestIndex(first), lastKey = bestIndex(last);
            if (firstKey == lastKey && first[firstKey] >= .6 && last[lastKey] >= .6) {
                sharedEdge = firstKey;
                for (int i = 0; i < average.length; i++) average[i] = .7 * average[i] + .15 * (first[i] + last[i]);
            }
        }
        int best = bestIndex(average);
        double score = average[best];
        average[best] = -2;
        int runnerUp = bestIndex(average);
        double margin = score - average[runnerUp];
        double agreement = sections.stream().filter(section -> bestIndex(section) == best).count() / (double) sections.size();
        if (best == sharedEdge) agreement = Math.max(.5, agreement);
        double evidence = Math.min(.9, margin * 3) * agreement;
        if (score >= .5 && margin >= .05 && (agreement > .5 || best == sharedEdge)) {
            return new Key(best, evidence, List.of());
        }
        List<String> candidates = score >= .55 && average[runnerUp] >= .45
            ? List.of(keyName(best), keyName(runnerUp)) : List.of();
        return new Key(-1, evidence, candidates);
    }

    private static String keyName(int index) { return NOTES[index / 2] + (index % 2 == 0 ? " major" : " minor"); }

    private static int bestIndex(double[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) if (scores[i] > scores[best]) best = i;
        return best;
    }

    private static double correlation(double[] chroma, double[] profile, int root) {
        double mean = Arrays.stream(chroma).average().orElse(0);
        double profileMean = Arrays.stream(profile).average().orElse(0);
        double cross = 0, first = 0, second = 0;
        for (int i = 0; i < 12; i++) {
            double x = chroma[(i + root) % 12] - mean;
            double y = profile[i] - profileMean;
            cross += x * y;
            first += x * x;
            second += y * y;
        }
        return cross / Math.sqrt(Math.max(1e-20, first * second));
    }

    private static List<Integer> energyCurve(List<Double> envelope) {
        List<Integer> curve = new ArrayList<>();
        for (int block = 0; block < 12; block++) {
            int start = block * envelope.size() / 12;
            int end = (block + 1) * envelope.size() / 12;
            double average = 0;
            for (int i = start; i < end; i++) average += envelope.get(i);
            curve.add(bound((db(average / Math.max(1, end - start)) + 50) * 2));
        }
        return curve;
    }

    static double db(double amplitude) { return 20 * Math.log10(Math.max(1e-6, amplitude)); }
    private static int bound(double value) { return (int) Math.max(0, Math.min(100, Math.round(value))); }

    static void transform(double[] real, double[] imaginary) {
        int size = real.length;
        for (int i = 1, j = 0; i < size; i++) {
            int bit = size >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double swap = real[i];
                real[i] = real[j];
                real[j] = swap;
            }
        }
        for (int length = 2; length <= size; length *= 2) {
            double angle = -2 * Math.PI / length;
            double cosine = Math.cos(angle), sine = Math.sin(angle);
            for (int start = 0; start < size; start += length) {
                double wr = 1, wi = 0;
                for (int j = 0; j < length / 2; j++) {
                    int a = start + j, b = a + length / 2;
                    double tr = wr * real[b] - wi * imaginary[b], ti = wr * imaginary[b] + wi * real[b];
                    real[b] = real[a] - tr;
                    imaginary[b] = imaginary[a] - ti;
                    real[a] += tr;
                    imaginary[a] += ti;
                    double next = wr * cosine - wi * sine;
                    wi = wr * sine + wi * cosine;
                    wr = next;
                }
            }
        }
    }
}
