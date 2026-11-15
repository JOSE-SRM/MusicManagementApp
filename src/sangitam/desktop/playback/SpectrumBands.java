package sangitam.desktop.playback;

import java.util.Arrays;

/** Hann-windowed stereo power spectrum, reduced to logarithmic frequency bands in dBFS. */
final class SpectrumBands {
    static final int RATE = 24_000;
    static final int SIZE = 4_096;
    static final int HOP = 512;
    static final int CHANNELS = 2;
    static final int BAND_COUNT = 96;
    private static final double LOW = 35;
    private static final double HIGH = 11_000;
    private static final double LOG_RANGE = Math.log(HIGH / LOW);
    private final double[] real = new double[SIZE];
    private final double[] imaginary = new double[SIZE];
    private final double[] window = new double[SIZE];
    private final int[] reversed = new int[SIZE];
    private final double[] cosine = new double[SIZE / 2];
    private final double[] sine = new double[SIZE / 2];
    private final double[] power = new double[SIZE / 2];
    private final double[] edges = new double[BAND_COUNT + 1];

    SpectrumBands() {
        for (int i = 0; i < SIZE; i++) {
            window[i] = (0.5 - 0.5 * Math.cos(2 * Math.PI * i / SIZE)) / 32768d;
            reversed[i] = Integer.reverse(i) >>> (Integer.SIZE - Integer.numberOfTrailingZeros(SIZE));
        }
        for (int i = 0; i < cosine.length; i++) {
            cosine[i] = Math.cos(-2 * Math.PI * i / SIZE);
            sine[i] = Math.sin(-2 * Math.PI * i / SIZE);
        }
        for (int i = 0; i <= BAND_COUNT; i++)
            edges[i] = (LOW * Math.exp(LOG_RANGE * i / BAND_COUNT)) * SIZE / RATE;
    }

    float[] measure(short[] pcm) {
        Arrays.fill(power, 0);
        for (int channel = 0; channel < CHANNELS; channel++) addChannel(pcm, channel);
        float[] bands = new float[BAND_COUNT];
        double normalization = 16.0 / (SIZE * (double) SIZE * CHANNELS);
        for (int band = 0; band < bands.length; band++) {
            double center = Math.sqrt(edges[band] * edges[band + 1]);
            int bin = (int) center;
            double magnitude = power[bin] + (power[bin + 1] - power[bin]) * (center - bin);
            for (int i = (int) Math.ceil(edges[band]); i < edges[band + 1]; i++)
                magnitude = Math.max(magnitude, power[i]);
            bands[band] = (float) Math.max(-80, 10 * Math.log10(magnitude * normalization));
        }
        return bands;
    }

    private void addChannel(short[] pcm, int channel) {
        Arrays.fill(imaginary, 0);
        for (int i = 0; i < SIZE; i++) {
            real[reversed[i]] = pcm[i * CHANNELS + channel] * window[i];
        }
        for (int width = 2; width <= SIZE; width *= 2) {
            int half = width / 2;
            int step = SIZE / width;
            for (int offset = 0; offset < half; offset++) {
                double cos = cosine[offset * step];
                double sin = sine[offset * step];
                for (int left = offset; left < SIZE; left += width) {
                    int right = left + half;
                    double r = real[right] * cos - imaginary[right] * sin;
                    double im = real[right] * sin + imaginary[right] * cos;
                    real[right] = real[left] - r;
                    imaginary[right] = imaginary[left] - im;
                    real[left] += r;
                    imaginary[left] += im;
                }
            }
        }
        for (int bin = 1; bin < power.length; bin++)
            power[bin] += real[bin] * real[bin] + imaginary[bin] * imaginary[bin];
    }
}
