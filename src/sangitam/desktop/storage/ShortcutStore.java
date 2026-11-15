package sangitam.desktop.storage;

import com.google.gson.Gson;
import sangitam.desktop.model.ShortcutBindings;
import java.sql.SQLException;
import java.util.Map;

/** Versioned non-secret app state in the existing SQLite database. */
public final class ShortcutStore {
    private record Saved(int version, Map<ShortcutBindings.Command, String> bindings) { }
    private final LibraryStore library;
    private final int menuMask;
    public ShortcutStore(LibraryStore library, int menuMask) { this.library = library; this.menuMask = menuMask; }
    public ShortcutBindings load() throws SQLException {
        try (var connection = library.connect(); var sql = connection.createStatement(); var row = sql.executeQuery("SELECT value FROM app_state WHERE key='shortcuts'")) {
            if (!row.next()) return ShortcutBindings.defaults(menuMask);
            try {
                Saved data = new Gson().fromJson(row.getString(1), Saved.class);
                if (data == null || data.version() != 1 || data.bindings() == null) throw new IllegalArgumentException("Unknown shortcut format");
                return new ShortcutBindings(data.bindings());
            } catch (RuntimeException error) { throw new SQLException("Saved shortcuts could not be read. Open Keyboard shortcuts and save new bindings.", error); }
        }
    }
    public void save(ShortcutBindings shortcuts) throws SQLException {
        try (var connection = library.connect(); var sql = connection.prepareStatement("INSERT INTO app_state VALUES('shortcuts',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            sql.setString(1, new Gson().toJson(new Saved(1, shortcuts.bindings()))); sql.executeUpdate();
        }
    }
}
