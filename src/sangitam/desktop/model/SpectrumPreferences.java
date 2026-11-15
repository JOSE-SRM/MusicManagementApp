package sangitam.desktop.model;

/** Display preferences shared by the spectrum view, its menus, and the catalog. */
public record SpectrumPreferences(int primary, int secondary, Colors colors, Heights heights) {
    public enum Palette {
        SLATE("Slate", 0x273744, 0x526774),
        BLUE("Dusk blue", 0x24384d, 0x476b8c),
        GREEN("Forest", 0x293d37, 0x527567),
        PLUM("Plum", 0x3b2e45, 0x765c79),
        AMBER("Warm bronze", 0x40362b, 0x7d6953),
        BLUE_RED("Blue to red", 0x285abe, 0xe85158),
        GREEN_YELLOW("Green to yellow", 0x19804c, 0xf4de4c),
        GOLD("Dark yellow to gold", 0x8b6c16, 0xffca37);

        private final String label;
        private final int primary;
        private final int secondary;

        Palette(String label, int primary, int secondary) {
            this.label = label;
            this.primary = primary;
            this.secondary = secondary;
        }

        public int primary() { return primary; }
        public int secondary() { return secondary; }
        @Override public String toString() { return label; }
    }

    public enum Colors {
        SOLID("Primary color"), ACROSS("Left-to-right gradient"),
        HEIGHT("Gradient by bar height"), VERTICAL("Vertical gradient");
        private final String label;
        Colors(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Heights {
        EQUALIZED("Equalized"), EXPONENTIAL("Exponential"),
        LOGARITHMIC("Logarithmic"), EXTEND_PEAKS("Extend peaks");
        private final String label;
        Heights(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public SpectrumPreferences(Palette palette, Colors colors, Heights heights) {
        this(palette.primary(), palette.secondary(), colors, heights);
    }

    public Palette palette() {
        for (Palette candidate : Palette.values()) {
            if (candidate.primary() == primary && candidate.secondary() == secondary) return candidate;
        }
        return null;
    }

    public static SpectrumPreferences defaults() {
        return new SpectrumPreferences(Palette.BLUE_RED, Colors.VERTICAL, Heights.EXTEND_PEAKS);
    }

    public static SpectrumPreferences fromSaved(String palette, String colors, String heights,
                                                String primary, String secondary) {
        var defaults = defaults();
        var preset = read(palette, defaults.palette());
        return new SpectrumPreferences(readColor(primary, preset.primary()), readColor(secondary, preset.secondary()),
            read(colors, defaults.colors), read(heights, defaults.heights));
    }

    private static int readColor(String value, int fallback) {
        if (value == null) return fallback;
        try {
            int rgb = Integer.parseInt(value);
            return rgb >= 0 && rgb <= 0xffffff ? rgb : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    private static <E extends Enum<E>> E read(String name, E fallback) {
        if (name == null) return fallback;
        try { return Enum.valueOf(fallback.getDeclaringClass(), name); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }
}
