package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.history.HistoryService;
import sangitam.desktop.history.QualifiedPlayTracker;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.JavaFxPlayerService;
import sangitam.desktop.playback.PlaybackState;
import sangitam.desktop.playback.PlayerService;
import sangitam.desktop.session.SessionPlanner;
import sangitam.desktop.storage.AppPaths;
import sangitam.desktop.storage.LibraryStore;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Composes Swing screens and connects them to one library and one audio service. */
public final class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screens = new JPanel(screenLayout);
    private final NotificationCenter notifications = new NotificationCenter();
    private final LibraryStore store = new LibraryStore(AppPaths.libraryDatabase());
    private final LibraryService library = new LibraryService(store);
    private final HistoryService history = new HistoryService(store);
    private final QualifiedPlayTracker playTracker = new QualifiedPlayTracker();
    private final PlayerService player = new JavaFxPlayerService();
    private final SessionPlanner planner = new SessionPlanner();
    private DashboardPanel dashboard;
    private SearchPanel search;
    private DiscoverPanel discover;
    private RediscoverPanel rediscover;
    private LibraryPanel libraryPanel;
    private PlayerBar playerBar;
    private List<Track> tracks = List.of();
    private Track selectedTrack;
    private List<SessionPlanner.Choice> sessionQueue = List.of();
    private int sessionIndex = -1;
    private boolean sessionActive;
    private long playGeneration;
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
            () -> showScreen("search"), () -> showScreen("discover"),
            notifications::show, this::closeApp));
        installKeyboardShortcuts();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateResponsiveLayout(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { player.close(); history.close(); }
        });
        library.addCatalogListener(this::onLibraryChanged);
        player.setListener(this::onPlaybackState);
        library.loadInitial(notifications::show);
    }

    private JComponent buildWindow() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BLACK);
        screens.setBackground(Theme.BLACK);
        dashboard = new DashboardPanel(this::openImportDialog, this::playTrack, notifications::show,
            this::buildSession, new SessionQueuePanel.Actions() {
                @Override public void start(List<SessionPlanner.Choice> choices) { startSession(choices); }
                @Override public void play(SessionPlanner.Choice choice, List<SessionPlanner.Choice> choices) {
                    startSessionAt(choice, choices);
                }
                @Override public void edited(List<SessionPlanner.Choice> choices) { editSession(choices); }
            });
        search = new SearchPanel(this::playTrack);
        discover = new DiscoverPanel(notifications::show);
        rediscover = new RediscoverPanel(history, this::playTrack, notifications::show);
        screens.add(dashboard, "home");
        screens.add(search, "search");
        screens.add(discover, "discover");
        screens.add(rediscover, "rediscover");

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

    private void onLibraryChanged(LibraryService.Catalog catalog) {
        List<Track> updated = catalog.tracks();
        tracks = updated;
        dashboard.setTracks(updated);
        search.setTracks(updated, catalog.tags());
        libraryPanel.setTracks(updated, catalog.tags());
        rediscover.setCatalog(catalog);
        if (selectedTrack != null) {
            selectedTrack = updated.stream().filter(track -> track.path().equals(selectedTrack.path()))
                .findFirst().orElse(null);
        }
        if (selectedTrack == null && !updated.isEmpty()) selectedTrack = updated.get(0);
    }

    private void onPlaybackState(PlaybackState state) {
        playerBar.setState(state);
        if (state.status() == PlaybackState.Status.ERROR) notifications.show(state.error());
        if (state.track() != null && selectedTrack != null
            && state.track().path().equals(selectedTrack.path())
            && playTracker.observe(state, playGeneration))
            library.recordPlay(state.track().path(), notifications::show);
        if (state.status() == PlaybackState.Status.ERROR && sessionActive
            && state.track() != null && selectedTrack != null
            && state.track().path().equals(selectedTrack.path())) {
            if (sessionIndex + 1 < sessionQueue.size()) SwingUtilities.invokeLater(this::nextTrack);
            else sessionActive = false;
        }
        if (state.status() == PlaybackState.Status.ENDED && state.track() != null
            && selectedTrack != null && state.track().path().equals(selectedTrack.path())) {
            if (sessionActive) {
                if (sessionIndex + 1 < sessionQueue.size()) playSessionIndex(sessionIndex + 1);
                else {
                    sessionActive = false;
                    notifications.show("Session complete");
                }
            } else {
                int index = indexOf(tracks, state.track());
                if (index >= 0 && index + 1 < tracks.size()) playTrack(tracks.get(index + 1));
            }
        }
    }

    private void playTrack(Track track) {
        sessionActive = false;
        selectedTrack = track;
        playGeneration++;
        player.play(track);
    }

    private void buildSession(SessionPlanner.Request request) {
        LibraryService.Catalog source = library.catalog();
        if (source.tracks().isEmpty()) {
            dashboard.showPlan(new SessionPlanner.Plan(List.of(), request.minutes(), 0));
            notifications.show("Add music to your library before building a session");
            return;
        }
        notifications.show("Choosing music for your session…");
        new SwingWorker<SessionPlanner.Plan, Void>() {
            @Override protected SessionPlanner.Plan doInBackground() {
                List<Track> playable = source.tracks().stream()
                    .filter(track -> Files.isRegularFile(track.path())).toList();
                return planner.build(request, playable, source.tags(), source.playCounts());
            }
            @Override protected void done() {
                try {
                    SessionPlanner.Plan plan = get();
                    sessionQueue = plan.choices();
                    sessionActive = false;
                    sessionIndex = -1;
                    dashboard.showPlan(plan);
                    notifications.show(plan.choices().isEmpty() ? "No playable tracks found"
                        : "Session ready: " + plan.choices().size() + " tracks");
                } catch (Exception error) {
                    dashboard.showPlan(new SessionPlanner.Plan(List.of(), request.minutes(), 0));
                    notifications.show("Could not build session: " + error.getMessage());
                }
            }
        }.execute();
    }

    private void startSession(List<SessionPlanner.Choice> choices) {
        if (choices.isEmpty()) { notifications.show("Build or add a track first"); return; }
        sessionQueue = List.copyOf(choices);
        sessionActive = true;
        playSessionIndex(0);
    }

    private void startSessionAt(SessionPlanner.Choice choice, List<SessionPlanner.Choice> choices) {
        sessionQueue = List.copyOf(choices);
        sessionActive = true;
        int index = sessionQueue.indexOf(choice);
        playSessionIndex(Math.max(0, index));
    }

    private void editSession(List<SessionPlanner.Choice> choices) {
        int previousIndex = sessionIndex;
        sessionQueue = List.copyOf(choices);
        if (!sessionActive || selectedTrack == null) return;
        sessionIndex = indexOfChoices(selectedTrack);
        if (sessionIndex < 0) {
            if (sessionQueue.isEmpty()) sessionActive = false;
            else sessionIndex = Math.min(previousIndex - 1, sessionQueue.size() - 1);
        }
    }

    private void playSessionIndex(int index) {
        if (index < 0 || index >= sessionQueue.size()) return;
        sessionIndex = index;
        selectedTrack = sessionQueue.get(index).track();
        playGeneration++;
        player.play(selectedTrack);
        notifications.show("Session " + (index + 1) + " of " + sessionQueue.size() + " · "
            + selectedTrack.title());
    }

    private int indexOfChoices(Track track) {
        for (int i = 0; i < sessionQueue.size(); i++) {
            if (sessionQueue.get(i).track().path().equals(track.path())) return i;
        }
        return -1;
    }

    private int indexOf(List<Track> list, Track track) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).path().equals(track.path())) return i;
        return -1;
    }

    private void togglePlayback() {
        PlaybackState state = player.state();
        if (state.status() == PlaybackState.Status.PLAYING) {
            player.pause();
        } else if (state.track() != null && selectedTrack != null
            && state.track().path().equals(selectedTrack.path())
            && (state.status() == PlaybackState.Status.PAUSED || state.status() == PlaybackState.Status.ENDED)) {
            if (state.status() == PlaybackState.Status.ENDED) playGeneration++;
            player.resume();
        } else if (selectedTrack != null) {
            playTrack(selectedTrack);
        } else {
            notifications.show("Add music to your library first");
        }
    }

    private void nextTrack() {
        if (sessionActive) {
            if (sessionIndex + 1 < sessionQueue.size()) playSessionIndex(sessionIndex + 1);
            else notifications.show("Last track in session");
            return;
        }
        if (tracks.isEmpty()) { notifications.show("Add music to your library first"); return; }
        int index = selectedTrack == null ? -1 : indexOf(tracks, selectedTrack);
        if (index + 1 >= tracks.size()) { notifications.show("End of library"); return; }
        playTrack(tracks.get(index + 1));
    }

    private void previousTrack() {
        if (sessionActive) {
            if (sessionIndex > 0) playSessionIndex(sessionIndex - 1);
            else player.seek(0);
            return;
        }
        if (tracks.isEmpty()) { notifications.show("Add music to your library first"); return; }
        int index = selectedTrack == null ? -1 : indexOf(tracks, selectedTrack);
        if (index <= 0) { player.seek(0); return; }
        playTrack(tracks.get(index - 1));
    }

    private void showScreen(String name) {
        boolean collectionCategory = name.equals("songs") || name.equals("albums") || name.equals("artists");
        screenLayout.show(screens, collectionCategory ? "search" : name);
        if (collectionCategory) search.selectCategory(name);
        if (name.equals("discover")) discover.focusSearch();
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
        commands.put("Discover online music", () -> showScreen("discover"));
        commands.put("Rediscover most played songs", () -> showScreen("rediscover"));
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
        history.close();
        dispose();
    }
}
