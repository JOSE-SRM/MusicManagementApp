package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.JavaFxPlayerService;
import sangitam.desktop.playback.PlaybackState;
import sangitam.desktop.playback.PlayerService;
import sangitam.desktop.storage.AppPaths;
import sangitam.desktop.storage.LibraryStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Composes Swing screens and connects them to one library and one audio service. */
public final class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screens = new JPanel(screenLayout);
    private final NotificationCenter notifications = new NotificationCenter();
    private final LibraryService library = new LibraryService(new LibraryStore(AppPaths.libraryDatabase()));
    private final PlayerService player = new JavaFxPlayerService();
    private DashboardPanel dashboard;
    private SearchPanel search;
    private LibraryPanel libraryPanel;
    private PlayerBar playerBar;
    private List<Track> tracks = List.of();
    private Track selectedTrack;
    private boolean compactLayout;

    public MainFrame() {
        super("Sangitam — Music Management");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1360, 820);
        setLocationRelativeTo(null);
        setContentPane(buildWindow());
        setJMenuBar(new AppMenuBar(this::openImportDialog, this::openCommandPalette,
            this::togglePlayback, this::previousTrack, this::nextTrack,
            () -> showScreen("search"), notifications::show, this::closeApp));
        installKeyboardShortcuts();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateResponsiveLayout(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { player.close(); }
        });
        library.addListener(this::onLibraryChanged);
        player.setListener(this::onPlaybackState);
        library.loadInitial(notifications::show);
    }

    private JComponent buildWindow() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BLACK);
        screens.setBackground(Theme.BLACK);
        dashboard = new DashboardPanel(this::openImportDialog, this::playTrack, notifications::show);
        search = new SearchPanel(this::playTrack);
        screens.add(dashboard, "home");
        screens.add(search, "search");

        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setBackground(Theme.BLACK);
        workspace.add(screens, BorderLayout.CENTER);
        libraryPanel = new LibraryPanel(this::openImportDialog, this::playTrack);
        workspace.add(libraryPanel, BorderLayout.EAST);
        root.add(new NavigationPanel(this::showScreen, this::openImportDialog,
            this::toggleLibrary, this::openCommandPalette, notifications::show), BorderLayout.WEST);
        root.add(workspace, BorderLayout.CENTER);

        JPanel lower = new JPanel(new BorderLayout());
        playerBar = new PlayerBar(new PlayerBar.Actions() {
            @Override public void playPause() { togglePlayback(); }
            @Override public void previous() { previousTrack(); }
            @Override public void next() { nextTrack(); }
            @Override public void seek(double fraction) { player.seek(fraction); }
            @Override public void volume(double fraction) { player.setVolume(fraction); }
        });
        lower.add(playerBar, BorderLayout.CENTER);
        lower.add(notifications, BorderLayout.SOUTH);
        root.add(lower, BorderLayout.SOUTH);
        return root;
    }

    private void onLibraryChanged(List<Track> updated) {
        tracks = updated;
        dashboard.setTracks(updated);
        search.setTracks(updated);
        libraryPanel.setTracks(updated);
        if (selectedTrack != null) {
            selectedTrack = updated.stream().filter(track -> track.path().equals(selectedTrack.path()))
                .findFirst().orElse(null);
        }
        if (selectedTrack == null && !updated.isEmpty()) selectedTrack = updated.get(0);
    }

    private void onPlaybackState(PlaybackState state) {
        playerBar.setState(state);
        if (state.status() == PlaybackState.Status.ERROR) notifications.show(state.error());
        if (state.status() == PlaybackState.Status.ENDED && state.track() != null
            && state.track().equals(selectedTrack)) {
            int index = tracks.indexOf(state.track());
            if (index >= 0 && index + 1 < tracks.size()) playTrack(tracks.get(index + 1));
        }
    }

    private void playTrack(Track track) {
        selectedTrack = track;
        player.play(track);
    }

    private void togglePlayback() {
        PlaybackState state = player.state();
        if (state.status() == PlaybackState.Status.PLAYING) {
            player.pause();
        } else if (state.track() != null && state.track().equals(selectedTrack)
            && (state.status() == PlaybackState.Status.PAUSED || state.status() == PlaybackState.Status.ENDED)) {
            player.resume();
        } else if (selectedTrack != null) {
            playTrack(selectedTrack);
        } else {
            notifications.show("Add music to your library first");
        }
    }

    private void nextTrack() {
        if (tracks.isEmpty()) { notifications.show("Add music to your library first"); return; }
        int index = tracks.indexOf(selectedTrack);
        if (index + 1 >= tracks.size()) { notifications.show("End of library"); return; }
        playTrack(tracks.get(index + 1));
    }

    private void previousTrack() {
        if (tracks.isEmpty()) { notifications.show("Add music to your library first"); return; }
        int index = tracks.indexOf(selectedTrack);
        if (index <= 0) { player.seek(0); return; }
        playTrack(tracks.get(index - 1));
    }

    private void showScreen(String name) {
        boolean collectionCategory = name.equals("songs") || name.equals("albums") || name.equals("artists");
        screenLayout.show(screens, collectionCategory ? "search" : name);
        if (collectionCategory) search.selectCategory(name);
        notifications.show(Character.toUpperCase(name.charAt(0)) + name.substring(1) + " opened");
    }

    private void openImportDialog() {
        new ImportMediaDialog(this, library, notifications::show).setVisible(true);
    }

    private void installKeyboardShortcuts() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_K, shortcut), "quickActions");
        getRootPane().getActionMap().put("quickActions", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { openCommandPalette(); }
        });
    }

    private void openCommandPalette() {
        Map<String, Runnable> commands = new LinkedHashMap<>();
        commands.put("Shape a listening session", () -> showScreen("home"));
        commands.put("Search music", () -> showScreen("search"));
        commands.put("Add files or a folder", this::openImportDialog);
        commands.put("Show or hide library", this::toggleLibrary);
        commands.put("Play or pause", this::togglePlayback);
        commands.put("Previous track", this::previousTrack);
        commands.put("Next track", this::nextTrack);
        new CommandPaletteDialog(this, commands).setVisible(true);
    }

    private void toggleLibrary() {
        libraryPanel.setVisible(!libraryPanel.isVisible());
        libraryPanel.getParent().revalidate();
        notifications.show(libraryPanel.isVisible() ? "Library shown" : "Library hidden");
    }

    /** On narrower windows the library yields space to the main task. */
    private void updateResponsiveLayout() {
        boolean nowCompact = getWidth() < 1180;
        if (nowCompact == compactLayout) return;
        compactLayout = nowCompact;
        libraryPanel.setVisible(!nowCompact);
        libraryPanel.getParent().revalidate();
    }

    private void closeApp() {
        player.close();
        dispose();
    }
}
