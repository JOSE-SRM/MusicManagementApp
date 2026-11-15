package sangitam.desktop.ui;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.SpectrumPreferences.Heights;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumMotionTest {
    @Test void markersHoldEachLocalPeakAndThenFallTowardTheBar() {
        var motion = new SpectrumMotion();
        long start = 1_000_000_000L;
        motion.setSamples(new float[]{-3, -25}, start);
        float first = motion.peak(0);
        float second = motion.peak(1);
        assertTrue(first > .9f);
        assertTrue(first - second > .35f);
        motion.advance(start + 16_000_000L, 16);
        long quieter = start + 125_000_000L;
        motion.setSamples(new float[]{-32, -42}, quieter);
        motion.advance(quieter, 64);
        assertEquals(first, motion.peak(0));
        assertEquals(second, motion.peak(1));
        motion.advance(start + 370_000_000L, 16);
        assertTrue(motion.peak(0) < first);
        assertTrue(motion.peak(0) >= motion.level(0));
        motion.clear();
        assertEquals(0, motion.size());
    }

    @Test void selectableCurvesChangeTheSameMeasuredBandDifferently() {
        var motion = new SpectrumMotion();
        long now = 1_000_000_000L;
        motion.setHeights(Heights.EQUALIZED, now);
        motion.setSamples(new float[]{-38}, now);
        assertEquals(.5f, motion.peak(0), .001f);
        motion.setHeights(Heights.EXPONENTIAL, now);
        assertTrue(motion.peak(0) < .3f);
        motion.setSamples(new float[]{-11.4f}, now);
        assertTrue(motion.peak(0) > .76f, "Strong peaks should exceed the old quadratic height");
        motion.setSamples(new float[]{-38}, now);
        motion.setHeights(Heights.LOGARITHMIC, now);
        assertTrue(motion.peak(0) > .5f);
    }
}
