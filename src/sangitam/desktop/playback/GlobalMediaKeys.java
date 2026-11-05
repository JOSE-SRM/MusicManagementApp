package sangitam.desktop.playback;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;

import javax.swing.SwingUtilities;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Optional Windows-wide media-key bridge. It ignores all keys except standard media controls. */
public final class GlobalMediaKeys implements AutoCloseable, NativeKeyListener {
    public interface Actions {
        void playPause();
        void stop();
        void previous();
        void next();
        void volume(int delta);
    }

    private final Actions actions;
    private volatile boolean registered;
    private volatile boolean closed;
    private long lastEventAt;
    private int lastKey;

    public GlobalMediaKeys(Actions actions) { this.actions = actions; }

    public void start() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        Thread startup = new Thread(() -> {
            if (closed) return;
            Logger.getLogger(GlobalScreen.class.getPackageName()).setLevel(Level.OFF);
            try {
                if (!GlobalScreen.isNativeHookRegistered()) GlobalScreen.registerNativeHook();
                if (closed) {
                    GlobalScreen.unregisterNativeHook();
                    return;
                }
                GlobalScreen.addNativeKeyListener(this);
                registered = true;
            } catch (NativeHookException | UnsatisfiedLinkError | SecurityException ignored) {
                // The player remains usable with its in-window controls if native hooks are unavailable.
            }
        }, "sangitam-global-media-keys");
        startup.setDaemon(true);
        startup.start();
    }

    @Override public void nativeKeyPressed(NativeKeyEvent event) {
        int key = event.getKeyCode();
        long now = System.nanoTime();
        synchronized (this) {
            if (key == lastKey && now - lastEventAt < 250_000_000L) return;
            lastKey = key;
            lastEventAt = now;
        }
        Runnable action = switch (key) {
            case NativeKeyEvent.VC_MEDIA_PLAY -> actions::playPause;
            case NativeKeyEvent.VC_MEDIA_STOP -> actions::stop;
            case NativeKeyEvent.VC_MEDIA_PREVIOUS -> actions::previous;
            case NativeKeyEvent.VC_MEDIA_NEXT -> actions::next;
            case NativeKeyEvent.VC_VOLUME_UP -> () -> actions.volume(1);
            case NativeKeyEvent.VC_VOLUME_DOWN -> () -> actions.volume(-1);
            default -> null;
        };
        if (action != null) SwingUtilities.invokeLater(action);
    }

    @Override public void nativeKeyReleased(NativeKeyEvent event) { }
    @Override public void nativeKeyTyped(NativeKeyEvent event) { }

    @Override public void close() {
        closed = true;
        if (!registered) return;
        registered = false;
        GlobalScreen.removeNativeKeyListener(this);
        try { GlobalScreen.unregisterNativeHook(); }
        catch (NativeHookException ignored) { }
    }
}
