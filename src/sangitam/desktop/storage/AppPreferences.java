package sangitam.desktop.storage;

import java.nio.file.Path;
import java.util.prefs.Preferences;

/** Non-secret application preferences. Provider credentials belong in Credential Manager. */
public final class AppPreferences {
    private static final Preferences PREFS = Preferences.userNodeForPackage(AppPreferences.class);
    private AppPreferences() { }

    public static Path lastMediaDirectory() {
        String value = PREFS.get("lastMediaDirectory", System.getProperty("user.home", "."));
        return Path.of(value);
    }
    public static void setLastMediaDirectory(Path path) {
        if (path != null) PREFS.put("lastMediaDirectory", path.toAbsolutePath().normalize().toString());
    }
    public static String appleStorefront() { return PREFS.get("appleStorefront", "in"); }
    public static void setAppleStorefront(String storefront) { PREFS.put("appleStorefront", storefront); }
}
