package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.AudioFeatures;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpectralFeaturesTest {
    private static final int RATE = 11025;

    @Test void periodicChordHasMeasuredTempoSpectrumDynamicsAndTonalEvidence() throws Exception {
        float[] signal = new float[RATE * 20];
        var envelope = new ArrayList<Double>();
        for (int i = 0; i < signal.length; i++) {
            double time = i / (double) RATE;
            double amplitude = time % .5 < .15 ? .4 : .02;
            signal[i] = (float) (amplitude * (Math.sin(2 * Math.PI * 261.63 * time)
                + Math.sin(2 * Math.PI * 329.63 * time) + Math.sin(2 * Math.PI * 392 * time)) / 3);
        }
        for (int i = 0; i < 400; i++) envelope.add(i % 10 < 3 ? .3 : .01);
        var features = measure(List.of(new SpectralFeatures.Passage(signal, signal.length, envelope)));
        assertEquals(120, features.bpm(), 1);
        assertTrue(features.tempoConfidence() > .8);
        assertTrue(features.centroidHz() > 200 && features.centroidHz() < 600);
        assertTrue(features.dynamicRangeDb() > 15);
        assertEquals(12, features.energyCurve().size());
        assertEquals("C", features.key());
        assertEquals("major", features.mode());
    }

    @Test void distinguishesTransposedMajorAndMinorChords() throws Exception {
        String[] names = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
        for (int root = 0; root < 12; root++) {
            for (boolean minor : new boolean[]{false, true}) {
                var features = measure(List.of(chord(48 + root, minor)));
                assertEquals(names[root], features.key(), names[root] + " " + minor + " evidence " + features.keyConfidence());
                assertEquals(minor ? "minor" : "major", features.mode());
                assertTrue(features.keyConfidence() < 1);
                assertEquals(50, features.valence());
            }
        }
    }

    @Test void conflictingPassagesAndSingleNotesLeaveKeyUnresolved() throws Exception {
        var mixed = measure(List.of(chord(48, false), chord(54, false)));
        assertTrue(mixed.key().isEmpty());
        float[] tone = new float[RATE * 4];
        for (int i = 0; i < tone.length; i++) tone[i] = (float) (.3 * Math.sin(2 * Math.PI * 440 * i / RATE));
        assertTrue(measure(List.of(new SpectralFeatures.Passage(tone, tone.length, Collections.nCopies(80, .2)))).key().isEmpty());
    }

    @Test void silenceDoesNotBecomeConfidentHappyMusic() throws Exception {
        var features = measure(List.of(new SpectralFeatures.Passage(new float[22050], 22050, Collections.nCopies(40, 0d))));
        assertEquals("Unknown", features.mood());
        assertEquals(0, features.confidence());
        assertEquals(0, features.bpm());
        assertTrue(features.key().isEmpty());
    }

    @Test void broadbandNoiseLeavesKeyUnresolved() throws Exception {
        var random = new Random(17);
        float[] noise = new float[RATE * 8];
        for (int i = 0; i < noise.length; i++) noise[i] = (random.nextFloat() - .5f) * .4f;
        var features = measure(List.of(new SpectralFeatures.Passage(noise, noise.length, Collections.nCopies(160, .1))));
        assertTrue(features.key().isEmpty());
        assertEquals(0, features.bpm());
    }

    private SpectralFeatures.Passage chord(int root, boolean minor) {
        float[] signal = new float[RATE * 4];
        for (int i = 0; i < signal.length; i++) {
            for (int semitone : new int[]{0, minor ? 3 : 4, 7}) {
                double frequency = 440 * Math.pow(2, (root + semitone - 69) / 12d);
                signal[i] += (float) (.15 * Math.sin(2 * Math.PI * frequency * i / RATE));
            }
        }
        return new SpectralFeatures.Passage(signal, signal.length, Collections.nCopies(80, .2));
    }

    @Test void tempoChangesKeepAReportedPulseAndRange() throws Exception {
        var slow = pulsedTone(88);
        var fast = pulsedTone(144);
        var features = measure(List.of(slow, fast));
        assertTrue(features.tempoWindows() >= 4);
        assertEquals(88, features.tempoLowBpm(), 2);
        assertEquals(144, features.tempoHighBpm(), 2);
        assertTrue(features.tempoDescription().contains("local estimates"));
    }

    private SpectralFeatures.Passage pulsedTone(int bpm) {
        float[] signal = new float[RATE * 19];
        for (int i = 0; i < signal.length; i++) {
            double seconds = i / (double) RATE;
            double beat = seconds * bpm / 60;
            double amplitude = beat - Math.floor(beat) < .22 ? .45 : 0;
            signal[i] = (float) (amplitude * Math.sin(2 * Math.PI * 440 * seconds));
        }
        return new SpectralFeatures.Passage(signal, signal.length, List.of());
    }

    private AudioFeatures measure(List<SpectralFeatures.Passage> passages) throws Exception {
        double squares = 0, peak = 0;
        long count = 0;
        for (var passage : passages) {
            for (int i = 0; i < passage.length(); i++) {
                double sample = passage.signal()[i];
                squares += sample * sample;
                peak = Math.max(peak, Math.abs(sample));
                count++;
            }
        }
        return SpectralFeatures.measure(passages, RATE, count / (double) RATE,
            Math.sqrt(squares / count), peak, 70, 60, 1, 2);
    }
}
