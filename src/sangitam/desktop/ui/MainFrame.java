package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.history.HistoryService;
import sangitam.desktop.history.QualifiedPlayTracker;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.model.PlaybackMemory;
import sangitam.desktop.playback.JavaFxPlayerService;
import sangitam.desktop.playback.PlaybackState;
import sangitam.desktop.playback.PlayerService;
import sangitam.desktop.session.SessionPlanner;
import sangitam.desktop.storage.AppPaths;
import sangitam.desktop.storage.LibraryStore;
import sangitam.desktop.storage.PlaybackMemoryService;

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
    private final JPanel workspace = new JPanel(new BorderLayout());
    private final NotificationCenter notifications = new NotificationCenter();
    private final LibraryStore store = new LibraryStore(AppPaths.libraryDatabase());
    private final LibraryService library = new LibraryService(store);
    private final HistoryService history = new HistoryService(store);
    private final PlaybackMemoryService memory = new PlaybackMemoryService(store);
    private final QualifiedPlayTracker playTracker = new QualifiedPlayTracker();
    private PlayerService player;
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
    private boolean workspaceReady;
    private boolean userSelectedTrack;
    private PlaybackMemory remembered;
    private double lastSavedPosition = -10;
    private double desiredVolume = 0.72;

    public MainFrame() {
        super("Sangitam — Music Management");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1360, 820);
        setLocationRelativeTo(null);
        setContentPane(buildShell());
        setJMenuBar(new AppMenuBar(this::openImportDialog, this::exportCurrentPlaylist,
            this::openCommandPalette,
            this::togglePlayback, this::previousTrack, this::nextTrack,
            () -> showScreen("search"), () -> showScreen("discover"),
            notifications::show, this::closeApp));
        installKeyboardShortcuts();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateResponsiveLayout(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeServices(); }
        });
    }

    /** Called after the first paint so the transport is visible before other screens are built. */
    public void loadWorkspace() {
        if (workspaceReady || !isDisplayable()) return;
        dashboard = new DashboardPanel(this::openImportDialog, this::playTrack, notifications::show,
            this::buildSession, new SessionQueuePanel.Actions() {
                @Override public void start(List<SessionPlanner.Choice> choices) { startSession(choices); }
                @Override public void play(SessionPlanner.Choice choice, List<SessionPlanner.Choice> choices) {
                    startSessionAt(choice, choices);
                }
                @Override public void edited(List<SessionPlanner.Choice> choices) { editSession(choices); }
                @Override public void save(String name, List<SessionPlanner.Choice> choices) {
                    savePlaylist(name, choices);
                }
                @Override public void load(Playlist playlist) { loadPlaylist(playlist); }
                @Override public void delete(Playlist playlist) { deletePlaylist(playlist); }
            });
        search = new SearchPanel(this::playTrack);
        discover = new DiscoverPanel(notifications::show);
        rediscover = new RediscoverPanel(history, this::playTrack, notifications::show);
        screens.removeAll();
        screens.add(dashboard, "home");
        screens.add(search, "search");
        screens.add(discover, "discover");
        screens.add(rediscover, "rediscover");
        libraryPanel = new LibraryPanel(this::openImportDialog, this::playTrack, this::editTags);
        workspace.add(libraryPanel, BorderLayout.EAST);
        workspaceReady = true;
        workspace.revalidate();
        workspace.repaint();
        updateResponsiveLayout();
        library.addCatalogListener(this::onLibraryChanged);
        memory.load(this::restorePlayback, notifications::show);
        library.loadInitial(notifications::show);
        refreshPlaylists();
    }

    private JComponent buildShell() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BLACK);
        // Construct the player before the dashboard, catalog, or audio decoder.
        playerBar = new PlayerBar(new PlayerBar.Actions() {
            @Override public void playPause() { togglePlayback(); }
            @Override public void previous() { previousTrack(); }
            @Override public void next() { nextTrack(); }
            @Override public void seek(double fraction) { seekTo(fraction); }
            @Override public void volume(double fraction) {
                desiredVolume = fraction;
                if (player != null) player.setVolume(fraction);
            }
        });
        JPanel lower = new JPanel(new BorderLayout());
        lower.add(playerBar, BorderLayout.CENTER);
        lower.add(notifications, BorderLayout.SOUTH);
        root.add(lower, BorderLayout.SOUTH);
        screens.setBackground(Theme.BLACK);
        workspace.setBackground(Theme.BLACK);
        screens.add(UiFactory.label("Loading your music…", Theme.HEADING, Theme.TEXT_MUTED), "loading");
        workspace.add(screens, BorderLayout.CENTER);
        root.add(new NavigationPanel(this::showScreen, this::openImportDialog,
            this::toggleLibrary, this::openCommandPalette, notifications::show), BorderLayout.WEST);
        root.add(workspace, BorderLayout.CENTER);
        return root;
    }

    private void onLibraryChanged(LibraryService.Catalog catalog) {
        List<Track> updated = catalog.tracks();
        tracks = updated;
        dashboard.setTracks(updated);
        search.setTracks(updated, catalog.tags(), catalog.manualTags());
        libraryPanel.setTracks(updated, catalog.tags(), catalog.manualTags());
        rediscover.setCatalog(catalog);
        if (selectedTrack != null) {
            selectedTrack = updated.stream().filter(track -> track.path().equals(selectedTrack.path()))
                .findFirst().orElse(null);
        }
        restoreLastIfAvailable();
        if (selectedTrack == null && !updated.isEmpty()) selectedTrack = updated.get(0);
    }

    private void restorePlayback(PlaybackMemory saved) {
        remembered = saved;
        restoreLastIfAvailable();
    }

    private void restoreLastIfAvailable() {
        if (remembered == null || userSelectedTrack || tracks.isEmpty()) return;
        for (Track track : tracks) {
            if (track.path().equals(remembered.path())) {
                selectedTrack = track;
                double position = Math.min(remembered.positionSeconds(), Math.max(0, track.durationSeconds() - 1));
                playerBar.showRemembered(track, position);
                lastSavedPosition = position;
                return;
            }
        }
    }

    private void onPlaybackState(PlaybackState state) {
        if (state.status() == PlaybackState.Status.IDLE && remembered != null && !userSelectedTrack) return;
        playerBar.setState(state);
        if (state.track() != null && state.status() == PlaybackState.Status.PLAYING
            && Math.abs(state.positionSeconds() - lastSavedPosition) >= 5) {
            savePosition(state.track(), state.positionSeconds());
        } else if (state.track() != null && (state.status() == PlaybackState.Status.PAUSED
            || state.status() == PlaybackState.Status.ENDED)) {
            savePosition(state.track(), state.status() == PlaybackState.Status.ENDED ? 0 : state.positionSeconds());
        }
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
        userSelectedTrack = true;
        selectedTrack = track;
        playGeneration++;
        savePosition(track, 0);
        ensurePlayer().play(track);
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

    private void refreshPlaylists() {
        new SwingWorker<List<Playlist>, Void>() {
            @Override protected List<Playlist> doInBackground() throws Exception {
                synchronized (store) { store.initialize(); return store.loadPlaylists(); }
            }
            @Override protected void done() {
                try { dashboard.setPlaylists(get()); }
                catch (Exception error) { notifications.show("Could not load saved playlists"); }
            }
        }.execute();
    }

    private void savePlaylist(String name, List<SessionPlanner.Choice> choices) {
        if (choices.isEmpty()) { notifications.show("Add tracks before saving a playlist"); return; }
        new SwingWorker<List<Playlist>, Void>() {
            @Override protected List<Playlist> doInBackground() throws Exception {
                synchronized (store) {
                    store.savePlaylist(name, choices.stream().map(choice -> choice.track().path()).toList());
                    return store.loadPlaylists();
                }
            }
            @Override protected void done() {
                try {
                    dashboard.setPlaylists(get());
                    dashboard.selectPlaylist(name);
                    notifications.show("Saved playlist: " + name);
                }
                catch (Exception error) { notifications.show("Could not save playlist"); }
            }
        }.execute();
    }

    private void loadPlaylist(Playlist playlist) {
        Map<java.nio.file.Path, Track> available = new java.util.HashMap<>();
        tracks.forEach(track -> available.put(track.path(), track));
        List<SessionPlanner.Choice> choices = playlist.tracks().stream().map(available::get)
            .filter(java.util.Objects::nonNull)
            .map(track -> new SessionPlanner.Choice(track, "Saved in " + playlist.name())).toList();
        if (choices.isEmpty()) { notifications.show("No tracks from this playlist are in the library"); return; }
        sessionActive = false;
        sessionQueue = choices;
        sessionIndex = -1;
        int seconds = choices.stream().mapToInt(choice -> choice.track().durationSeconds() > 0
            ? (int) Math.round(choice.track().durationSeconds()) : 180).sum();
        dashboard.showPlaylist(playlist, new SessionPlanner.Plan(choices, Math.max(1, seconds / 60), seconds));
        int missing = playlist.tracks().size() - choices.size();
        notifications.show("Loaded " + playlist.name() + (missing == 0 ? "" : " · " + missing + " unavailable tracks"));
    }

    private void deletePlaylist(Playlist playlist) {
        new SwingWorker<List<Playlist>, Void>() {
            @Override protected List<Playlist> doInBackground() throws Exception {
                synchronized (store) { store.deletePlaylist(playlist.id()); return store.loadPlaylists(); }
            }
            @Override protected void done() {
                try { dashboard.setPlaylists(get()); notifications.show("Deleted playlist: " + playlist.name()); }
                catch (Exception error) { notifications.show("Could not delete playlist"); }
            }
        }.execute();
    }

    private void exportCurrentPlaylist() {
        if (!workspaceReady || dashboard.currentQueue().isEmpty()) {
            notifications.show("Build, load, or add tracks to a playlist first");
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export current playlist");
        chooser.setSelectedFile(new java.io.File("Sangitam Playlist.m3u8"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("M3U8 playlist", "m3u8"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.nio.file.Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8"))
            target = target.resolveSibling(target.getFileName() + ".m3u8");
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "Replace the existing playlist file?",
            "Export playlist", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        java.nio.file.Path destination = target;
        List<SessionPlanner.Choice> choices = dashboard.currentQueue();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                StringBuilder text = new StringBuilder("#EXTM3U\n");
                for (SessionPlanner.Choice choice : choices) {
                    Track track = choice.track();
                    int seconds = (int) Math.round(track.durationSeconds());
                    String label = (track.artist() + " - " + track.title()).replace('\n', ' ').replace('\r', ' ');
                    text.append("#EXTINF:").append(seconds).append(',').append(label).append('\n');
                    text.append(track.path()).append('\n');
                }
                Files.writeString(destination, text, java.nio.charset.StandardCharsets.UTF_8);
                return null;
            }
            @Override protected void done() {
                try { get(); notifications.show("Exported playlist to " + destination.getFileName()); }
                catch (Exception error) { notifications.show("Could not export playlist file"); }
            }
        }.execute();
    }

    private void editTags(Track track) {
        var tags = library.catalog().tagsFor(track);
        JTextField genre = new JTextField(track.genre().equals("Unknown genre") ? "" : track.genre(), 24);
        JSlider energy = new JSlider(0, 100, tags.energy());
        JSlider movement = new JSlider(0, 100, tags.activity());
        energy.setMajorTickSpacing(25); energy.setPaintTicks(true); energy.setPaintLabels(true);
        movement.setMajorTickSpacing(25); movement.setPaintTicks(true); movement.setPaintLabels(true);
        JPanel form = new JPanel(new GridLayout(6, 1, 0, 6));
        form.add(UiFactory.label("Genre (from file or your correction)", Theme.SMALL, Theme.TEXT));
        form.add(genre);
        form.add(UiFactory.label("Energy", Theme.SMALL, Theme.TEXT));
        form.add(energy);
        form.add(UiFactory.label("Movement", Theme.SMALL, Theme.TEXT));
        form.add(movement);
        if (JOptionPane.showConfirmDialog(this, form, "Edit tags · " + track.title(),
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        String value = genre.getText().strip();
        library.updateTags(track, value.isEmpty() ? "Unknown genre" : value,
            energy.getValue(), movement.getValue(), notifications::show, notifications::show);
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
        userSelectedTrack = true;
        playGeneration++;
        savePosition(selectedTrack, 0);
        ensurePlayer().play(selectedTrack);
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
        PlaybackState state = player == null ? PlaybackState.idle() : player.state();
        if (state.status() == PlaybackState.Status.PLAYING) {
            player.pause();
        } else if (state.track() != null && selectedTrack != null
            && state.track().path().equals(selectedTrack.path())
            && (state.status() == PlaybackState.Status.PAUSED || state.status() == PlaybackState.Status.ENDED)) {
            if (state.status() == PlaybackState.Status.ENDED) playGeneration++;
            player.resume();
        } else if (selectedTrack != null) {
            if (remembered != null && selectedTrack.path().equals(remembered.path()) && !userSelectedTrack) {
                userSelectedTrack = true;
                playGeneration++;
                ensurePlayer().play(selectedTrack, lastSavedPosition);
            } else playTrack(selectedTrack);
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
            else if (player != null) player.seek(0);
            return;
        }
        if (tracks.isEmpty()) { notifications.show("Add music to your library first"); return; }
        int index = selectedTrack == null ? -1 : indexOf(tracks, selectedTrack);
        if (index <= 0) { if (player != null) player.seek(0); return; }
        playTrack(tracks.get(index - 1));
    }

    private void showScreen(String name) {
        if (!workspaceReady) { notifications.show("Workspace is loading…"); return; }
        boolean collectionCategory = name.equals("songs") || name.equals("albums") || name.equals("artists");
        screenLayout.show(screens, collectionCategory ? "search" : name.equals("playlists") ? "home" : name);
        if (collectionCategory) search.selectCategory(name);
        if (name.equals("playlists")) SwingUtilities.invokeLater(dashboard::focusPlaylists);
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
        commands.put("Saved playlists", () -> showScreen("playlists"));
        commands.put("Add files or a folder", this::openImportDialog);
        commands.put("Show or hide library", this::toggleLibrary);
        commands.put("Play or pause", this::togglePlayback);
        commands.put("Previous track", this::previousTrack);
        commands.put("Next track", this::nextTrack);
        new CommandPaletteDialog(this, commands).setVisible(true);
    }

    private void toggleLibrary() {
        if (libraryPanel == null) { notifications.show("Library is loading…"); return; }
        libraryPanel.setVisible(!libraryPanel.isVisible());
        libraryPanel.getParent().revalidate();
        notifications.show(libraryPanel.isVisible() ? "Library shown" : "Library hidden");
    }

    /** On narrower windows the library yields space to the main task. */
    private void updateResponsiveLayout() {
        if (libraryPanel == null) return;
        boolean nowCompact = getWidth() < 1180;
        if (nowCompact == compactLayout) return;
        compactLayout = nowCompact;
        libraryPanel.setVisible(!nowCompact);
        libraryPanel.getParent().revalidate();
    }

    private void closeApp() {
        closeServices();
        dispose();
    }

    private PlayerService ensurePlayer() {
        if (player == null) {
            player = new JavaFxPlayerService();
            player.setListener(this::onPlaybackState);
            player.setVolume(desiredVolume);
        }
        return player;
    }

    private void seekTo(double fraction) {
        if (selectedTrack == null) return;
        double position = Math.max(0, Math.min(1, fraction)) * selectedTrack.durationSeconds();
        if (player == null || player.state().track() == null) {
            remembered = new PlaybackMemory(selectedTrack.path(), position);
            lastSavedPosition = position;
            playerBar.showRemembered(selectedTrack, position);
            memory.save(remembered);
        } else {
            player.seek(fraction);
            savePosition(selectedTrack, position);
        }
    }

    private void savePosition(Track track, double position) {
        lastSavedPosition = position;
        remembered = new PlaybackMemory(track.path(), position);
        memory.save(remembered);
    }

    private void closeServices() {
        if (player != null) player.close();
        memory.close();
        history.close();
    }
}
