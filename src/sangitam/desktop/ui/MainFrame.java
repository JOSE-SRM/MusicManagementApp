package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.history.HistoryService;
import sangitam.desktop.history.QualifiedPlayTracker;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.LibraryCollections;
import sangitam.desktop.model.LibraryCollections.Key;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.model.PlaybackMemory;
import sangitam.desktop.playback.VlcjPlayerService;
import sangitam.desktop.playback.GlobalMediaKeys;
import sangitam.desktop.playback.PlaybackState;
import sangitam.desktop.playback.PlayerService;
import sangitam.desktop.session.SessionPlanner;
import sangitam.desktop.storage.AppPaths;
import sangitam.desktop.storage.CollectionStore;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Composes Swing screens and connects them to one library and one audio service. */
public final class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screens = new JPanel(screenLayout);
    private final JPanel workspace = new JPanel(new BorderLayout());
    private final NotificationCenter notifications = new NotificationCenter();
    private final LibraryStore store = new LibraryStore(AppPaths.libraryDatabase());
    private final CollectionStore collections = new CollectionStore(store);
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
    private LibraryDetailPanel collectionPage;
    private Key openCollection;
    private long collectionRevision;
    private long shownCollectionRevision;
    private boolean collectionStorageReady;
    private PlayerBar playerBar;
    private AppMenuBar menuBar;
    private List<Track> tracks = List.of();
    private Track selectedTrack;
    private List<SessionPlanner.Choice> sessionQueue = List.of();
    private final Set<Integer> sessionPlayed = new HashSet<>();
    private int sessionIndex = -1;
    private boolean sessionActive;
    private boolean pendingLibraryQueue;
    private long playGeneration;
    private boolean compactLayout;
    private boolean workspaceReady;
    private boolean userSelectedTrack;
    private PlaybackMemory remembered;
    private double lastSavedPosition = -10;
    private double desiredVolume = 0.72;
    private final GlobalMediaKeys globalMediaKeys;
    private boolean shuffleEnabled;
    private boolean repeatCurrent;

    public MainFrame() {
        super("Sangitam — Music Management");
        setIconImage(AppIcons.image(AppIcons.Kind.MUSIC, 64, Theme.BLUE, Theme.SURFACE));
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1360, 820);
        setLocationRelativeTo(null);
        setContentPane(buildShell());
        menuBar = new AppMenuBar(this::openImportDialog, this::exportCurrentPlaylist,
            this::openCommandPalette,
            this::togglePlayback, this::previousTrack, this::nextTrack,
            () -> showScreen("search"), () -> showScreen("discover"),
            this::openSettings, this::toggleLibrary,
            () -> setShuffle(!shuffleEnabled), () -> setRepeat(!repeatCurrent), this::rescanLibrary,
            notifications::show, this::closeApp);
        setJMenuBar(menuBar);
        installKeyboardShortcuts();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateResponsiveLayout(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { closeServices(); }
        });
        globalMediaKeys = new GlobalMediaKeys(new GlobalMediaKeys.Actions() {
            @Override public void playPause() { togglePlayback(); }
            @Override public void stop() { stopPlayback(); }
            @Override public void previous() { previousTrack(); }
            @Override public void next() { nextTrack(); }
            @Override public void volume(int delta) { adjustVolume(delta); }
        });
        globalMediaKeys.start();
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
        collectionPage = new LibraryDetailPanel(() -> showScreen("home"), this::playCollection, this::addToQueue,
            track -> libraryPanel.trackMenu(track));
        screens.removeAll();
        screens.add(dashboard, "home");
        screens.add(search, "search");
        screens.add(discover, "discover");
        screens.add(rediscover, "rediscover");
        screens.add(collectionPage, "collection");
        libraryPanel = new LibraryPanel(new LibraryPanel.Actions() {
            @Override public void importMusic(long folder) { openImportDialog(folder); }
            @Override public void rescan() { rescanLibrary(); }
            @Override public void play(List<Track> tracks) {
                if (tracks.size() == 1) playTrack(tracks.getFirst());
                else playCollection(tracks, 0);
            }
            @Override public void queue(List<Track> tracks) { addToQueue(tracks); }
            @Override public void editDetails(List<Track> tracks, boolean group) { editTrackDetails(tracks, group); }
            @Override public void remove(List<Path> paths, Path scope) {
                library.removeFromLibrary(paths, scope, notifications::show);
            }
            @Override public void open(LibraryEntry entry) { openCollection(entry); }
            @Override public void organize(String message, LibraryPanel.CollectionEdit edit) {
                changeCollections(message, edit, null);
            }
            @Override public void deletePlaylist(long id) { removePlaylist(id); }
            @Override public void entriesChanged() { refreshCollectionPage(); }
        });
        workspace.add(libraryPanel, BorderLayout.EAST);
        workspaceReady = true;
        workspace.revalidate();
        workspace.repaint();
        updateResponsiveLayout();
        library.addCatalogListener(this::onLibraryChanged);
        memory.load(this::restorePlayback, notifications::show);
        library.loadInitial(notifications::show);
        refreshPlaylists();
        ensurePlayer().prepare();
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
                if (player != null) player.setVolume(toLinearGain(fraction));
            }
            @Override public void stop() { stopPlayback(); }
            @Override public void toggleShuffle() { setShuffle(!shuffleEnabled); }
            @Override public void toggleRepeat() { setRepeat(!repeatCurrent); }
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
            this::toggleLibrary, this::openCommandPalette, this::openSettings), BorderLayout.WEST);
        root.add(workspace, BorderLayout.CENTER);
        return root;
    }

    private void onLibraryChanged(LibraryService.Catalog catalog) {
        List<Track> updated = catalog.tracks();
        tracks = updated;
        dashboard.setTracks(updated);
        search.setTracks(updated, catalog.tags(), catalog.manualTags(), catalog.unavailable());
        libraryPanel.setCatalog(catalog);
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
            if (repeatCurrent) {
                playGeneration++;
                if (sessionActive) playSessionIndex(sessionIndex);
                else playTrack(selectedTrack);
            } else if (sessionActive) {
                if (shuffleEnabled) nextTrack();
                else if (sessionIndex + 1 < sessionQueue.size()) playSessionIndex(sessionIndex + 1);
                else {
                    sessionActive = false;
                    notifications.show("Session complete");
                }
            } else if (shuffleEnabled && tracks.size() > 1) {
                nextTrack();
            } else {
                Track next = adjacentAvailable(state.track(), 1);
                if (next != null) playTrack(next);
            }
        }
    }

    private void playTrack(Track track) {
        if (pendingLibraryQueue) {
            var queued = new java.util.ArrayList<SessionPlanner.Choice>();
            queued.add(new SessionPlanner.Choice(track, "Selected in library"));
            sessionQueue.stream().filter(choice -> !choice.track().path().equals(track.path())).forEach(queued::add);
            startSession(queued);
            showQueue();
            return;
        }
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
                    .filter(track -> !source.unavailable().contains(track.path()))
                    .filter(track -> Files.isRegularFile(track.path())).toList();
                return planner.build(request, playable, source.tags(), source.playCounts());
            }
            @Override protected void done() {
                try {
                    SessionPlanner.Plan plan = get();
                    sessionQueue = plan.choices();
                    sessionActive = false;
                    pendingLibraryQueue = false;
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
        changeCollections(null, ignored -> { }, null);
    }

    private void savePlaylist(String name, List<SessionPlanner.Choice> choices) {
        if (choices.isEmpty()) { notifications.show("Add tracks before saving a playlist"); return; }
        changeCollections("Saved playlist " + name, ignored ->
            store.savePlaylist(name, choices.stream().map(choice -> choice.track().path()).toList()), name);
    }

    private void loadPlaylist(Playlist playlist) {
        Map<java.nio.file.Path, Track> available = new java.util.HashMap<>();
        tracks.forEach(track -> available.put(track.path(), track));
        List<SessionPlanner.Choice> choices = playlist.tracks().stream().map(available::get)
            .filter(java.util.Objects::nonNull)
            .map(track -> new SessionPlanner.Choice(track, "Saved in " + playlist.name())).toList();
        if (choices.isEmpty()) { notifications.show("No tracks from this playlist are in the library"); return; }
        sessionActive = false;
        pendingLibraryQueue = false;
        sessionQueue = choices;
        sessionIndex = -1;
        int seconds = choices.stream().mapToInt(choice -> choice.track().durationSeconds() > 0
            ? (int) Math.round(choice.track().durationSeconds()) : 180).sum();
        dashboard.showPlaylist(playlist, new SessionPlanner.Plan(choices, Math.max(1, seconds / 60), seconds));
        int missing = playlist.tracks().size() - choices.size();
        notifications.show("Loaded " + playlist.name() + (missing == 0 ? "" : " · " + missing + " unavailable tracks"));
    }

    private void deletePlaylist(Playlist playlist) {
        removePlaylist(playlist.id());
    }

    private void removePlaylist(long id) {
        changeCollections("Playlist deleted", ignored -> store.deletePlaylist(id), null);
    }

    private record CollectionsUpdate(LibraryCollections data, long revision) { }

    private void changeCollections(String message, LibraryPanel.CollectionEdit edit, String selection) {
        new SwingWorker<CollectionsUpdate, Void>() {
            @Override protected CollectionsUpdate doInBackground() throws Exception {
                synchronized (store) {
                    if (!collectionStorageReady) {
                        store.initialize();
                        collectionStorageReady = true;
                    }
                    edit.run(collections);
                    return new CollectionsUpdate(collections.load(), ++collectionRevision);
                }
            }
            @Override protected void done() {
                try {
                    CollectionsUpdate update = get();
                    if (update.revision() > shownCollectionRevision) {
                        shownCollectionRevision = update.revision();
                        libraryPanel.setCollections(update.data());
                        dashboard.setPlaylists(update.data().playlists());
                        if (selection != null) dashboard.selectPlaylist(selection);
                    }
                    if (message != null) notifications.show(message);
                } catch (Exception error) {
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    String reason = cause.getMessage();
                    if (reason != null && reason.contains("SQLITE_CONSTRAINT_UNIQUE")) {
                        reason = "That name already exists in the destination folder.";
                    }
                    notifications.show("Could not update collections" + (reason == null ? "" : " · " + reason));
                }
            }
        }.execute();
    }

    private void openCollection(LibraryEntry entry) {
        openCollection = entry.key();
        collectionPage.showEntry(entry, library.catalog());
        libraryPanel.collapse();
        screenLayout.show(screens, "collection");
    }

    private void refreshCollectionPage() {
        if (openCollection != null) collectionPage.showEntry(libraryPanel.entry(openCollection), library.catalog());
    }

    private void exportCurrentPlaylist() {
        if (!workspaceReady || dashboard.currentQueue().isEmpty()) {
            notifications.show("Build, load, or add tracks to a playlist first");
            return;
        }
        java.nio.file.Path target = FileBrowserDialog.chooseSaveFile(this,
            "Export current playlist", "Sangitam Playlist.m3u8", "m3u8");
        if (target == null) return;
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

    private void editTrackDetails(List<Track> tracks, boolean group) {
        if (tracks.isEmpty()) return;
        Track track = tracks.getFirst();
        var tags = library.catalog().tagsFor(track);
        JTextField title = new JTextField(track.title(), 24);
        JTextField artist = new JTextField(commonValue(tracks, Track::artist), 24);
        JTextField album = new JTextField(commonValue(tracks, Track::album), 24);
        JTextField genre = new JTextField(commonValue(tracks, Track::genre), 24);
        String previousArtist = artist.getText();
        String previousAlbum = album.getText();
        String previousGenre = genre.getText();
        JSlider energy = new JSlider(0, 100, tags.energy());
        JSlider movement = new JSlider(0, 100, tags.activity());
        JCheckBox ratings = new JCheckBox(group ? "Apply ratings to all tracks" : "Set energy and movement");
        energy.setEnabled(false);
        movement.setEnabled(false);
        energy.setMajorTickSpacing(25);
        energy.setPaintLabels(true);
        movement.setMajorTickSpacing(25);
        movement.setPaintLabels(true);
        ratings.addActionListener(event -> {
            energy.setEnabled(ratings.isSelected());
            movement.setEnabled(ratings.isSelected());
        });
        JPanel form = new JPanel(new GridLayout(0, 1, 0, 6));
        if (!group) addDetailField(form, "Title", title);
        addDetailField(form, "Artist", artist);
        addDetailField(form, "Album", album);
        addDetailField(form, "Genre / tag", genre);
        form.add(ratings);
        addDetailField(form, "Energy", energy);
        addDetailField(form, "Movement", movement);
        if (group) form.add(UiFactory.label("Blank fields keep each track’s existing value.", Theme.SMALL, Theme.TEXT_MUTED));
        if (JOptionPane.showConfirmDialog(this, form, group ? "Edit album details" : "Edit track details",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        LibraryStore.DetailChanges changes = new LibraryStore.DetailChanges(
            group ? null : changedText(title, track.title(), null),
            changedText(artist, previousArtist, group ? null : "Unknown artist"),
            changedText(album, previousAlbum, group ? null : "Unknown album"),
            changedText(genre, previousGenre, group ? null : "Unknown genre"),
            ratings.isSelected() ? energy.getValue() : null,
            ratings.isSelected() ? movement.getValue() : null);
        library.editDetails(tracks.stream().map(Track::path).toList(), changes, notifications::show);
    }

    private String commonValue(List<Track> tracks, java.util.function.Function<Track, String> property) {
        String value = property.apply(tracks.getFirst());
        return tracks.stream().allMatch(track -> property.apply(track).equals(value)) ? value : "";
    }

    private String changedText(JTextField field, String previous, String blankValue) {
        String value = field.getText().strip();
        if (value.equals(previous)) return null;
        return value.isEmpty() ? blankValue : value;
    }

    private void addDetailField(JPanel form, String name, JComponent field) {
        JLabel label = UiFactory.label(name, Theme.SMALL, Theme.TEXT);
        label.setLabelFor(field);
        form.add(label);
        form.add(field);
    }

    private void playCollection(List<Track> tracks, int startIndex) {
        if (tracks.isEmpty()) return;
        Track requested = tracks.get(startIndex);
        List<SessionPlanner.Choice> choices = playableChoices(tracks);
        if (choices.isEmpty()) { notifications.show("No available tracks in this collection"); return; }
        SessionPlanner.Choice start = choices.stream().filter(choice -> choice.track().path().equals(requested.path()))
            .findFirst().orElse(choices.getFirst());
        startSessionAt(start, choices);
        showQueue();
    }

    private List<SessionPlanner.Choice> playableChoices(List<Track> tracks) {
        Set<Path> unavailable = library.catalog().unavailable();
        return tracks.stream().filter(track -> !unavailable.contains(track.path()))
            .map(track -> new SessionPlanner.Choice(track, "From your library")).toList();
    }

    private void addToQueue(List<Track> tracks) {
        var updated = new java.util.ArrayList<SessionPlanner.Choice>();
        boolean anchoredToPlayback = false;
        if (sessionActive || pendingLibraryQueue) updated.addAll(sessionQueue);
        else {
            PlaybackState state = player == null ? PlaybackState.idle() : player.state();
            Track current = state.track();
            if (current != null && (state.status() == PlaybackState.Status.LOADING
                || state.status() == PlaybackState.Status.PLAYING || state.status() == PlaybackState.Status.PAUSED)) {
                updated.add(new SessionPlanner.Choice(current, "Currently playing"));
                anchoredToPlayback = true;
            }
            if (sessionIndex == -1) {
                Path anchor = anchoredToPlayback ? current.path() : null;
                sessionQueue.stream().filter(choice -> !choice.track().path().equals(anchor))
                    .forEach(updated::add);
            }
        }
        var paths = new HashSet<Path>();
        updated.forEach(choice -> paths.add(choice.track().path()));
        List<SessionPlanner.Choice> added = playableChoices(tracks).stream()
            .filter(choice -> paths.add(choice.track().path())).toList();
        if (added.isEmpty()) { notifications.show("No new available tracks to queue"); return; }
        if (anchoredToPlayback) {
            sessionActive = true;
            sessionIndex = 0;
            sessionPlayed.clear();
            sessionPlayed.add(0);
        }
        updated.addAll(added);
        sessionQueue = List.copyOf(updated);
        pendingLibraryQueue = !sessionActive;
        showQueue();
        notifications.show("Added " + added.size() + " to the queue");
    }

    private void showQueue() {
        dashboard.setQueue(sessionQueue);
    }

    private void startSession(List<SessionPlanner.Choice> choices) {
        if (choices.isEmpty()) { notifications.show("Build or add a track first"); return; }
        sessionQueue = List.copyOf(choices);
        pendingLibraryQueue = false;
        sessionPlayed.clear();
        sessionActive = true;
        playSessionIndex(0);
    }

    private void startSessionAt(SessionPlanner.Choice choice, List<SessionPlanner.Choice> choices) {
        sessionQueue = List.copyOf(choices);
        pendingLibraryQueue = false;
        sessionPlayed.clear();
        sessionActive = true;
        int index = sessionQueue.indexOf(choice);
        playSessionIndex(Math.max(0, index));
    }

    private void editSession(List<SessionPlanner.Choice> choices) {
        int previousIndex = sessionIndex;
        sessionQueue = List.copyOf(choices);
        if (sessionQueue.isEmpty()) pendingLibraryQueue = false;
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
        sessionPlayed.add(index);
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
        if (track == null) return -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).path().equals(track.path())) return i;
        return -1;
    }

    private Track adjacentAvailable(Track from, int step) {
        Set<Path> unavailable = library.catalog().unavailable();
        for (int index = indexOf(tracks, from) + step; index >= 0 && index < tracks.size(); index += step) {
            Track candidate = tracks.get(index);
            if (!unavailable.contains(candidate.path())) return candidate;
        }
        return null;
    }

    private void togglePlayback() {
        PlaybackState state = player == null ? PlaybackState.idle() : player.state();
        if (pendingLibraryQueue) { startSession(sessionQueue); return; }
        if (state.status() == PlaybackState.Status.LOADING) return;
        if (state.status() == PlaybackState.Status.PLAYING) {
            player.pause();
        } else if (state.track() != null && selectedTrack != null
            && state.track().path().equals(selectedTrack.path())
            && (state.status() == PlaybackState.Status.PAUSED || state.status() == PlaybackState.Status.STOPPED
                || state.status() == PlaybackState.Status.ENDED)) {
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
        if (pendingLibraryQueue) { startSession(sessionQueue); return; }
        if (sessionActive) {
            if (shuffleEnabled) {
                List<Integer> candidates = new java.util.ArrayList<>();
                for (int i = 0; i < sessionQueue.size(); i++) if (!sessionPlayed.contains(i)) candidates.add(i);
                if (candidates.isEmpty() && repeatCurrent) {
                    sessionPlayed.clear();
                    for (int i = 0; i < sessionQueue.size(); i++) if (i != sessionIndex) candidates.add(i);
                }
                if (candidates.isEmpty()) {
                    sessionActive = false;
                    notifications.show("Session complete");
                    return;
                }
                playSessionIndex(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
                return;
            }
            if (sessionIndex + 1 < sessionQueue.size()) playSessionIndex(sessionIndex + 1);
            else notifications.show("Last track in session");
            return;
        }
        if (tracks.isEmpty()) { notifications.show("Add music to your library first"); return; }
        if (shuffleEnabled && tracks.size() > 1) {
            Set<Path> unavailable = library.catalog().unavailable();
            List<Track> candidates = tracks.stream()
                .filter(track -> selectedTrack == null || !track.path().equals(selectedTrack.path()))
                .filter(track -> !unavailable.contains(track.path())).toList();
            if (!candidates.isEmpty()) {
                playTrack(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
                return;
            }
        }
        Track next = adjacentAvailable(selectedTrack, 1);
        if (next != null) playTrack(next);
        else notifications.show("No next available track");
    }

    private void previousTrack() {
        if (sessionActive) {
            if (sessionIndex > 0) playSessionIndex(sessionIndex - 1);
            else if (player != null) player.seek(0);
            return;
        }
        if (tracks.isEmpty()) { notifications.show("Add music to your library first"); return; }
        Track previous = adjacentAvailable(selectedTrack, -1);
        if (previous != null) playTrack(previous);
        else if (player != null) player.seek(0);
    }

    private void stopPlayback() {
        if (player != null) player.stop();
        if (selectedTrack != null) savePosition(selectedTrack, 0);
    }

    private void setShuffle(boolean enabled) {
        shuffleEnabled = enabled;
        playerBar.setShuffle(enabled);
        if (menuBar != null) menuBar.setShuffleSelected(enabled);
        notifications.show(enabled ? "Shuffle enabled" : "Shuffle disabled");
    }

    private void setRepeat(boolean enabled) {
        repeatCurrent = enabled;
        playerBar.setRepeat(enabled);
        if (menuBar != null) menuBar.setRepeatSelected(enabled);
        notifications.show(enabled ? "Repeat current track enabled" : "Repeat disabled");
    }

    private void adjustVolume(int steps) {
        desiredVolume = Math.max(0, Math.min(1, desiredVolume + steps * 0.05));
        playerBar.setVolumeFraction(desiredVolume);
        if (player != null) player.setVolume(toLinearGain(desiredVolume));
        notifications.show("Volume " + Math.round(desiredVolume * 100) + "%");
    }

    private double toLinearGain(double slider) {
        if (slider <= 0) return 0;
        double decibels = -60.0 * (1.0 - Math.min(1.0, slider));
        return Math.pow(10.0, decibels / 20.0);
    }

    private void showScreen(String name) {
        if (!workspaceReady) { notifications.show("Workspace is loading…"); return; }
        libraryPanel.collapse();
        openCollection = null;
        boolean collectionCategory = name.equals("songs") || name.equals("albums") || name.equals("artists");
        screenLayout.show(screens, collectionCategory ? "search" : name.equals("playlists") ? "home" : name);
        if (collectionCategory) search.selectCategory(name);
        if (name.equals("playlists")) SwingUtilities.invokeLater(dashboard::focusPlaylists);
        if (name.equals("discover")) discover.focusSearch();
        notifications.show(Character.toUpperCase(name.charAt(0)) + name.substring(1) + " opened");
    }

    private void openImportDialog() {
        if (libraryPanel != null) libraryPanel.collapse();
        new ImportMediaDialog(this, library, notifications::show).setVisible(true);
    }

    private void openImportDialog(long folder) {
        new ImportMediaDialog(this, library, notifications::show, paths -> {
            if (folder != 0 && !paths.isEmpty()) {
                changeCollections("Imported tracks added to folder", store -> store.addFolderTracks(folder, paths), null);
            }
        }).setVisible(true);
    }

    private void rescanLibrary() {
        ImportMediaDialog.rescan(this, library, notifications::show);
    }

    private void openSettings() {
        if (libraryPanel != null) libraryPanel.collapse();
        new SettingsDialog(this).setVisible(true);
        if (discover != null) discover.refreshProviders();
    }

    private void installKeyboardShortcuts() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_K, shortcut), "quickActions", this::openCommandPalette);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "playPause", this::togglePlayback);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, shortcut), "nextTrack", this::nextTrack);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, shortcut), "previousTrack", this::previousTrack);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_UP, shortcut), "volumeUp", () -> adjustVolume(1));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, shortcut), "volumeDown", () -> adjustVolume(-1));
    }

    private void bind(KeyStroke stroke, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { action.run(); }
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
        commands.put("Rescan library", this::rescanLibrary);
        commands.put("Show or hide library", this::toggleLibrary);
        commands.put("Play or pause", this::togglePlayback);
        commands.put("Previous track", this::previousTrack);
        commands.put("Next track", this::nextTrack);
        new CommandPaletteDialog(this, commands).setVisible(true);
    }

    private void toggleLibrary() {
        if (libraryPanel == null) { notifications.show("Library is loading…"); return; }
        libraryPanel.collapse();
        libraryPanel.setVisible(!libraryPanel.isVisible());
        libraryPanel.getParent().revalidate();
        if (menuBar != null) menuBar.setLibrarySelected(libraryPanel.isVisible());
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
            player = new VlcjPlayerService();
            player.setListener(this::onPlaybackState);
            player.setVolume(toLinearGain(desiredVolume));
        }
        return player;
    }

    private void seekTo(double fraction) {
        if (selectedTrack == null || !Double.isFinite(fraction)) return;
        PlaybackState state = player == null ? PlaybackState.idle() : player.state();
        if (state.track() == null) {
            double position = Math.max(0, Math.min(1, fraction)) * selectedTrack.durationSeconds();
            remembered = new PlaybackMemory(selectedTrack.path(), position);
            lastSavedPosition = position;
            playerBar.showRemembered(selectedTrack, position);
            memory.save(remembered);
        } else if (state.seekable() && state.track().path().equals(selectedTrack.path())) {
            player.seek(fraction);
        }
    }

    private void savePosition(Track track, double position) {
        lastSavedPosition = position;
        remembered = new PlaybackMemory(track.path(), position);
        memory.save(remembered);
    }

    private void closeServices() {
        globalMediaKeys.close();
        if (libraryPanel != null) libraryPanel.close();
        if (player != null) player.close();
        memory.close();
        history.close();
    }
}
