package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;

/** Offline guide for the installed application. */
final class UserGuideDialog extends JDialog {
    UserGuideDialog(Window owner, Runnable shortcuts) {
        super(owner, "Sangitam user guide", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(Theme.padding(14, 14, 14, 14));
        content.setBackground(Theme.BLACK);

        JTabbedPane sections = new JTabbedPane();
        addSection(sections, "Import and browse", """
            Choose File → Add files or folder, then select the recordings or a folder on a connected drive. The catalog keeps references to those files.

            Library → General groups songs, albums, artists, playlists and virtual folders. Locations follows the folders on disk. Use Edit → Find to search the local catalog.

            Use Library → Rescan library after adding or moving files in a scanned folder. If a drive is disconnected, reconnect it before rescanning or playing those tracks.
            """);
        addSection(sections, "Playlists and queue", """
            Open Playlists in the General library tab. Use a track’s context menu to add it to a playlist or to the queue. Use the heart to add a song to Liked Songs.

            The queue button in the player opens Queue and Recently played. Double-click a row to play it. Listen Now also provides a session builder and an editable temporary journey.

            File → Export current playlist exports a playlist as M3U8. The playlist references local audio files.
            """);
        addSection(sections, "Playback and shortcuts", """
            Double-click a song to play it. Use the player controls to pause, seek and adjust volume. The default Space binding toggles Play/Pause in the main window. Editable fields keep Space for typing.

            Open Playback → Audio & equalizer to preview EQ while using the main player. Save keeps the settings. Cancel or Escape restores the saved EQ. Saved ReplayGain changes apply at the next track start on VLC.

            Open Keyboard shortcuts below to see or change the active bindings. The View menu can collapse the navigation labels or toggle the separate library panel.
            """);
        addSection(sections, "Troubleshooting", """
            If a track is unavailable, check that the file and its drive are accessible. Confirm the file plays in VLC. Sangitam uses an installed compatible VLC for broader format support. Without it, JavaFX Media supports fewer formats.

            For a portable build, keep the entire Sangitam folder together and open Sangitam.exe. For a source checkout, run build.bat with JDK 21 or newer, then run run.bat in a terminal to see startup errors.
            """);
        content.add(sections, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton bindings = UiFactory.button("Keyboard shortcuts…", null, false);
        bindings.addActionListener(event -> shortcuts.run());
        JButton close = UiFactory.button("Close", null, true);
        close.addActionListener(event -> dispose());
        actions.add(bindings);
        actions.add(close);
        content.add(actions, BorderLayout.SOUTH);

        setContentPane(content);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(640, 410);
        setMinimumSize(new Dimension(360, 270));
        setLocationRelativeTo(owner);
    }

    private static void addSection(JTabbedPane sections, String title, String instructions) {
        JTextArea text = new JTextArea(instructions);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(Theme.BODY);
        text.setBackground(Theme.SURFACE);
        text.setForeground(Theme.TEXT);
        text.setCaretColor(Theme.TEXT);
        text.setBorder(Theme.padding(14, 14, 14, 14));
        text.getAccessibleContext().setAccessibleName(title);
        text.setCaretPosition(0);
        JScrollPane scroll = new PixelScrollPane(text);
        UiFactory.styleScroll(scroll);
        sections.addTab(title, scroll);
    }
}
