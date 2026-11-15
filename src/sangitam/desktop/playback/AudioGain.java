package sangitam.desktop.playback;

/** VLC-style percentage volume: 1 means 100%, with a cubic gain curve and boost up to 150%. */
public final class AudioGain {
    private AudioGain() { }
    public static final double MAX_VOLUME = 1.5;
    public static double clamp(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(MAX_VOLUME, value)) : 0; }
    public static double decibels(double volume) { return 20 * Math.log10(linear(volume)); }
    public static double linear(double volume) { return Math.pow(clamp(volume), 3); }
    public static double fromDecibels(double db) { return Math.pow(10, db / 20); }
    public static String label(double slider) {
        return Math.round(clamp(slider) * 100) + "%";
    }
    /** VLC 3's Windows MMDevice and software outputs cube their percentage volume. */
    public static int vlcVolume(double volume) { return (int) Math.round(100 * clamp(volume)); }

    /** VLC 3's single-pass EQ scales its dry signal by 0.25; offset that so UI 0 dB is unity. */
    public static float vlcEqualizerPreamp(double userDb) { return (float) (20 * Math.log10(4) + userDb); }
}
