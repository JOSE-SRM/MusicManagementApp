package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.ShortcutBindings;
import sangitam.desktop.model.ShortcutBindings.Command;
import javax.swing.KeyStroke;
import java.awt.event.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShortcutStoreTest {
    @TempDir Path folder;
    @Test void customizedAndDisabledBindingsSurviveReopenWithoutChangingVolume() throws Exception {
        Path db = folder.resolve("library.db"); var library = new LibraryStore(db); library.initialize(); library.saveVolume(1.25);
        var store = new ShortcutStore(library, InputEvent.CTRL_DOWN_MASK); var defaults = store.load();
        assertEquals(Command.values().length, defaults.bindings().size());
        var changed = new EnumMap<Command, String>(Command.class); changed.putAll(defaults.bindings());
        changed.put(Command.QUICK, "ctrl pressed P"); changed.put(Command.NEXT, ""); var saved = new ShortcutBindings(changed); store.save(saved);
        library = new LibraryStore(db); library.initialize(); var reopened = new ShortcutStore(library, InputEvent.CTRL_DOWN_MASK).load();
        assertEquals(saved, reopened); assertNull(reopened.key(Command.NEXT)); assertEquals(1.25, library.loadVolume());
    }
    @Test void conflictingReservedAndCorruptBindingsAreRejected() throws Exception {
        var library = new LibraryStore(folder.resolve("library.db")); library.initialize(); var store = new ShortcutStore(library, InputEvent.CTRL_DOWN_MASK);
        var map = new EnumMap<Command, String>(Command.class); map.put(Command.QUICK, "ctrl pressed P"); map.put(Command.SEARCH, "ctrl pressed P");
        assertThrows(IllegalArgumentException.class, () -> new ShortcutBindings(map));
        for (String key : List.of("pressed ENTER", "pressed ESCAPE", "pressed TAB", "pressed A", "released F5")) assertFalse(ShortcutBindings.allowed(KeyStroke.getKeyStroke(key)));
        store.save(ShortcutBindings.defaults(InputEvent.CTRL_DOWN_MASK));
        try (var connection = library.connect(); var sql = connection.createStatement()) { sql.executeUpdate("UPDATE app_state SET value='{\"version\":99}' WHERE key='shortcuts'"); }
        assertThrows(SQLException.class, store::load);
    }
}
