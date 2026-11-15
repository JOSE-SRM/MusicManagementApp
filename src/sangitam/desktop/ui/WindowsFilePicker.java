package sangitam.desktop.ui;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.COM.Unknown;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.WinNT.HRESULT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.awt.EventQueue;
import java.awt.Toolkit;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;

/** Windows Explorer's common file dialog. An empty result means the user cancelled. */
final class WindowsFilePicker {
    private static final int CANCELLED = 0x800704C7;
    private static final int FORCE_FILESYSTEM = 0x40, PICK_FOLDERS = 0x20, ALLOW_MULTIPLE = 0x200;
    private static final int FILE_MUST_EXIST = 0x1000, PATH_MUST_EXIST = 0x800, OVERWRITE_PROMPT = 0x2;
    private static final GUID OPEN = new GUID("{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}");
    private static final GUID SAVE = new GUID("{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}");
    private static final GUID OPEN_INTERFACE = new GUID("{D57C7288-D4AD-4768-BE02-9D969532D960}");
    private static final GUID SAVE_INTERFACE = new GUID("{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}");
    private static final GUID SHELL_ITEM = new GUID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}");
    private static final GUID DOWNLOADS = new GUID("{374DE290-123F-4565-9164-39C4925E467B}");

    record Options(String title, String history, String label, String pattern, boolean folders,
                   boolean multiple, String saveName, String extension) { }

    private WindowsFilePicker() { }

    /** Returns null if the native picker is unavailable so callers can use their portable fallback. */
    static List<Path> choose(Window owner, Options options) {
        if (!Platform.isWindows() || !EventQueue.isDispatchThread()) return null;
        Pointer parent;
        try { parent = owner == null || !owner.isDisplayable() ? null : Native.getWindowPointer(owner); }
        catch (RuntimeException | LinkageError unavailable) { return null; }
        var loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        var result = new AtomicReference<List<Path>>();
        Thread.ofPlatform().daemon().name("Windows file picker").start(() -> {
            try { result.set(show(parent, options)); }
            catch (RuntimeException | LinkageError unavailable) { /* Portable picker remains available. */ }
            finally { EventQueue.invokeLater(loop::exit); }
        });
        loop.enter();
        return result.get();
    }

    private static List<Path> show(Pointer owner, Options options) {
        var ole = Ole32.INSTANCE;
        check(ole.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).intValue());
        try {
            boolean save = options.saveName() != null;
            var pointer = new PointerByReference();
            check(ole.CoCreateInstance(save ? SAVE : OPEN, null, 1,
                save ? SAVE_INTERFACE : OPEN_INTERFACE, pointer).intValue());
            try (var dialog = new Interface(pointer.getValue())) {
                return select(owner, options, save, dialog);
            }
        } finally { ole.CoUninitialize(); }
    }

    private static List<Path> select(Pointer owner, Options options, boolean save, Interface dialog) {
        var flags = new IntByReference();
        check(dialog.call(10, flags)); // IFileDialog::GetOptions
        int requested = FORCE_FILESYSTEM | PATH_MUST_EXIST | (save ? OVERWRITE_PROMPT : FILE_MUST_EXIST);
        if (options.folders()) requested = (requested & ~FILE_MUST_EXIST) | PICK_FOLDERS;
        if (options.multiple()) requested |= ALLOW_MULTIPLE;
        check(dialog.call(9, flags.getValue() | requested));
        check(dialog.call(17, new WString(options.title())));
        if (options.pattern() != null) {
            try (var name = wide(options.label()); var pattern = wide(options.pattern()); var filter = new Memory(2L * Native.POINTER_SIZE)) {
                filter.setPointer(0, name);
                filter.setPointer(Native.POINTER_SIZE, pattern);
                check(dialog.call(4, 1, filter));
            }
        }
        if (save) {
            check(dialog.call(15, new WString(options.saveName())));
            if (options.extension() != null) check(dialog.call(22, new WString(options.extension())));
        }
        var preferences = Preferences.userNodeForPackage(WindowsFilePicker.class);
        var last = preferences.get(options.history(), "");
        Path start = last.isBlank() ? null : Path.of(last);
        if ((start == null || !Files.isDirectory(start)) && options.history().equals("score")) start = downloads();
        if (start != null && Files.isDirectory(start)) {
            var item = new PointerByReference();
            if (Parsing.INSTANCE.SHCreateItemFromParsingName(new WString(start.toString()), null, SHELL_ITEM, item).intValue() >= 0) {
                try (var folder = new Interface(item.getValue())) { dialog.call(12, folder.getPointer()); }
            }
        }
        int shown = dialog.call(3, owner);
        if (shown == CANCELLED) return List.of();
        check(shown);
        var paths = options.multiple() ? results(dialog) : List.of(singleResult(dialog));
        if (!paths.isEmpty()) {
            Path folder = options.folders() ? paths.getFirst() : paths.getFirst().getParent();
            if (folder != null) preferences.put(options.history(), folder.toString());
        }
        return paths;
    }

    private static List<Path> results(Interface dialog) {
        var value = new PointerByReference();
        check(dialog.call(27, value)); // IFileOpenDialog::GetResults
        try (var array = new Interface(value.getValue())) {
            var count = new IntByReference();
            check(array.call(7, count));
            var results = new ArrayList<Path>(count.getValue());
            for (int index = 0; index < count.getValue(); index++) {
                check(array.call(8, index, value));
                results.add(path(value.getValue()));
            }
            return List.copyOf(results);
        }
    }

    private static Path singleResult(Interface dialog) {
        var value = new PointerByReference();
        check(dialog.call(20, value)); // IFileDialog::GetResult
        return path(value.getValue());
    }

    private static Path path(Pointer item) {
        try (var shellItem = new Interface(item)) {
            var name = new PointerByReference();
            check(shellItem.call(5, 0x80058000, name)); // IShellItem::GetDisplayName, SIGDN_FILESYSPATH
            try { return Path.of(name.getValue().getWideString(0)); }
            finally { Ole32.INSTANCE.CoTaskMemFree(name.getValue()); }
        }
    }

    static Path downloads() {
        if (Platform.isWindows()) {
            var name = new PointerByReference();
            if (Shell32.INSTANCE.SHGetKnownFolderPath(DOWNLOADS, 0, null, name).intValue() >= 0) {
                try { return Path.of(name.getValue().getWideString(0)); }
                finally { Ole32.INSTANCE.CoTaskMemFree(name.getValue()); }
            }
        }
        return Path.of(System.getProperty("user.home"), "Downloads");
    }

    private static Memory wide(String value) {
        var memory = new Memory((value.length() + 1L) * Native.WCHAR_SIZE);
        memory.setWideString(0, value);
        return memory;
    }

    private static void check(int result) {
        if (result < 0) throw new IllegalStateException("Windows file dialog HRESULT " + Integer.toHexString(result));
    }

    private interface Parsing extends StdCallLibrary {
        Parsing INSTANCE = Native.load("shell32", Parsing.class, W32APIOptions.UNICODE_OPTIONS);
        HRESULT SHCreateItemFromParsingName(WString path, Pointer binding, GUID type, PointerByReference item);
    }

    private static final class Interface extends Unknown implements AutoCloseable {
        Interface(Pointer pointer) { super(pointer); }
        int call(int method, Object... arguments) {
            Object[] nativeArguments = new Object[arguments.length + 1];
            nativeArguments[0] = getPointer();
            System.arraycopy(arguments, 0, nativeArguments, 1, arguments.length);
            return ((HRESULT) _invokeNativeObject(method, nativeArguments, HRESULT.class)).intValue();
        }
        @Override public void close() { Release(); }
    }
}
