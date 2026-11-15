package sangitam.desktop.ui;

import sangitam.desktop.model.SpectrumPreferences.Heights;
import java.util.Arrays;

/** Display scaling, ballistics, and a short local peak hold for every frequency band. */
final class SpectrumMotion {
    private static final double FLOOR_DB = -76;
    private static final long PEAK_HOLD_NS = 360_000_000L;
    private static final long SAMPLE_TIMEOUT_NS = 160_000_000L;
    private float[] samples = {};
    private float[] targets = {};
    private float[] levels = {};
    private float[] peaks = {};
    private long[] holdUntil = {};
    private long receivedAt;
    private double ceiling = Double.NaN;
    private Heights heights = Heights.EXTEND_PEAKS;

    int size() { return levels.length; }
    float level(int band) { return levels[band]; }
    float peak(int band) { return peaks[band]; }

    void clear() {
        samples = targets = levels = peaks = new float[0];
        holdUntil = new long[0];
        ceiling = Double.NaN;
        receivedAt = 0;
    }

    void setHeights(Heights value, long now) {
        if (heights == value) return;
        heights = value;
        Arrays.fill(peaks, 0);
        Arrays.fill(holdUntil, 0);
        if (size() > 0 && now - receivedAt < SAMPLE_TIMEOUT_NS) updateTargets(now);
    }

    void setSamples(float[] values, long now) {
        if (values.length != size()) {
            targets = new float[values.length];
            levels = new float[values.length];
            peaks = new float[values.length];
            holdUntil = new long[values.length];
        }
        samples = values;
        double loudest = FLOOR_DB;
        for (float db : values) {
            if (Float.isFinite(db)) loudest = Math.max(loudest, db);
        }
        double nextCeiling = Math.max(-25, loudest + 3.5);
        if (Double.isNaN(ceiling)) ceiling = nextCeiling;
        else {
            double elapsed = Math.clamp((now - receivedAt) * 1e-6, 0, 100);
            ceiling += (nextCeiling - ceiling) * -Math.expm1(-elapsed / (nextCeiling > ceiling ? 55 : 550));
        }
        receivedAt = now;
        updateTargets(now);
    }

    private void updateTargets(long now) {
        for (int band = 0; band < size(); band++) {
            float db = samples[band];
            float target = Float.isFinite(db) ? (float) map(db) : 0;
            targets[band] = target;
            if (target > .01 && target >= peaks[band]) {
                peaks[band] = target;
                holdUntil[band] = now + PEAK_HOLD_NS;
            }
        }
    }

    private double map(double db) {
        double level = Math.clamp((db - FLOOR_DB) / -FLOOR_DB, 0, 1);
        return switch (heights) {
            case EQUALIZED -> level;
            // The slightly steeper power keeps quiet bands low. The upper-range lift gives strong peaks more height.
            case EXPONENTIAL -> Math.pow(level, 2.15) * (1 + .16 * (1 - Math.pow(level, 6)));
            case LOGARITHMIC -> Math.log1p(4 * level) / Math.log(5);
            case EXTEND_PEAKS -> {
                // Reserve headroom as the recording gets louder, so adjacent strong bands stay distinguishable.
                double floor = Math.max(FLOOR_DB, ceiling - 52);
                double relative = Math.clamp((db - floor) / (ceiling - floor), 0, 1);
                yield relative < .65 ? relative * .88 : .572 + (relative - .65) * 1.17;
            }
        };
    }

    boolean advance(long now, double elapsedMs) {
        double attack = -Math.expm1(-elapsedMs / 24);
        double release = -Math.expm1(-elapsedMs / 115);
        boolean fresh = now - receivedAt < SAMPLE_TIMEOUT_NS;
        boolean changed = false;
        for (int band = 0; band < size(); band++) {
            double wanted = fresh ? targets[band] : 0;
            double difference = wanted - levels[band];
            if (Math.abs(difference) > .001) {
                levels[band] += (float) (difference * (difference > 0 ? attack : release));
                changed = true;
            }
            if (now - holdUntil[band] >= 0 && peaks[band] > 0) {
                float next = (float) Math.max(Math.max(levels[band], wanted), peaks[band] - elapsedMs * .00085);
                if (next < .006) next = 0;
                changed |= peaks[band] != next;
                peaks[band] = next;
            }
        }
        return changed;
    }
}
