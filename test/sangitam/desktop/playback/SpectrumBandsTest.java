package sangitam.desktop.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumBandsTest {
    @Test void antiPhaseStereoKeepsTheToneAndSilenceUsesTheFloor() {
        var analyzer = new SpectrumBands();
        short[] pcm = new short[SpectrumBands.SIZE * SpectrumBands.CHANNELS];
        assertEquals(-80, analyzer.measure(pcm)[55]);
        for (int frame = 0; frame < SpectrumBands.SIZE; frame++) {
            short value = (short) (16_384 * Math.sin(2 * Math.PI * 168 * frame / SpectrumBands.SIZE));
            pcm[frame * 2] = value;
            pcm[frame * 2 + 1] = (short) -value;
        }
        float[] result = analyzer.measure(pcm);
        assertEquals(96, result.length);
        assertEquals(-6, result[55], .25);
        assertTrue(result[74] < -60);
    }
}
