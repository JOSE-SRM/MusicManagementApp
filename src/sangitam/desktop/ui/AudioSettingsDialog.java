package sangitam.desktop.ui;

import sangitam.desktop.playback.*;
import sangitam.desktop.storage.AudioSettingsStore;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.Consumer;

/** Modeless shell; the editor owns preview and Save/Cancel behavior. */
public final class AudioSettingsDialog extends JDialog {
    private final AudioSettingsPanel editor;
    public AudioSettingsDialog(Window owner, AudioSettings initial, PlayerService player,
                               AudioSettingsStore store, Consumer<AudioSettings> committed) {
        super(owner, "Audio & equalizer", ModalityType.MODELESS);
        editor = new AudioSettingsPanel(initial, player, store, committed, this::finish);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); setContentPane(editor);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { editor.cancel(); } });
        getRootPane().registerKeyboardAction(event -> editor.cancel(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack(); setMinimumSize(getSize()); setLocationRelativeTo(owner);
    }
    boolean isSaving() { return editor.isSaving(); }
    private void finish() { super.dispose(); }
    @Override public void dispose() { if (editor != null) editor.cancel(); else super.dispose(); }
}
