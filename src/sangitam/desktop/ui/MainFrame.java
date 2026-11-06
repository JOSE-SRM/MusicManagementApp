package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.app.StartupTrace;
import sangitam.desktop.history.HistoryService;
import sangitam.desktop.history.QualifiedPlayTracker;
import sangitam.desktop.history.ListeningClock;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.LibraryCollections;
import sangitam.desktop.model.LibraryCollections.Key;
import sangitam.desktop.model.Playlist;
import sangitam.desktop.model.PlaybackMemory;
import sangitam.desktop.playback.VlcjPlayerService;
import sangitam.desktop.playback.GlobalMediaKeys;
import sangitam.desktop.playback.WindowsMediaBridge;
import sangitam.desktop.playback.PlaybackState;
import sangitam.desktop.playback.PlayerService;
import sangitam.desktop.playback.AudioGain;
import sangitam.desktop.playback.AudioSettings;
import sangitam.desktop.storage.AudioSettingsStore;
import sangitam.desktop.session.SessionPlanner;
import sangitam.desktop.storage.AppPaths;
import sangitam.desktop.storage.CollectionStore;
import sangitam.desktop.storage.LibraryStore;
import sangitam.desktop.storage.LibraryDashboardStore;
import sangitam.desktop.storage.LibrarySearchStore;
import sangitam.desktop.storage.SmartPlaylistStore;
import sangitam.desktop.storage.ShortcutStore;
import sangitam.desktop.model.ShortcutBindings;
import sangitam.desktop.model.ShortcutBindings.Command;
import sangitam.desktop.model.SmartPlaylistRule;
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
    private final LibraryDashboardStore homeStore = new LibraryDashboardStore(store);
    private final SmartPlaylistStore smartPlaylists = new SmartPlaylistStore(store);
    private final LibraryService library = new LibraryService(store);
    private final HistoryService history = new HistoryService(store);
    private final PlaybackMemoryService memory = new PlaybackMemoryService(store);
    private final QualifiedPlayTracker playTracker = new QualifiedPlayTracker();
    private PlayerService player;
    private final AudioSettingsStore audioSettingsStore = new AudioSettingsStore(AppPaths.libraryDatabase().resolveSibling("audio.properties"));
    private AudioSettings audioSettings = AudioSettings.defaults();
    private boolean audioSettingsReady;
    private SwingWorker<SessionPlanner.Plan,Void> sessionBuilder;
    private long sessionBuildGeneration;
    private final SessionPlanner planner = new SessionPlanner();
    private DashboardPanel dashboard;
    private SearchPanel search;
    private DiscoverPanel discover;
    private RediscoverPanel rediscover;
    private LibraryPanel libraryPanel;
    private LibraryDetailPanel collectionPage;
    private ArtistPanel artistPage;
    private String openArtist;
    private long artistRefresh;
    private JSplitPane libraryRail;
    private QueueHistoryPanel queueHistory;
    private boolean queueVisible, volumeTouched, servicesClosed;
    private final ListeningClock listeningClock = new ListeningClock();
    private final Timer listeningSave = new Timer(10000, event -> memory.saveListening(listeningClock.drain()));
    private final Timer volumeSave = new Timer(250, event -> memory.saveVolume(this.desiredVolume));
    private Key openCollection;
    private long collectionRevision;
    private long shownCollectionRevision;
    private boolean collectionStorageReady;
    private boolean collectionRefreshActive, collectionRefreshPending;
    private final java.util.concurrent.ExecutorService collectionWorker = java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sangitam-collections");
        thread.setDaemon(true);
        return thread;
    });
    private PlayerBar playerBar;
    private SpectrumPanel spectrumPanel;
    private boolean spectrumVisible;
    private AppMenuBar menuBar;
    private ShortcutBindings shortcuts;
    private final SpaceTransport spaceTransport = new SpaceTransport(() -> isFocused() && !servicesClosed
        && shortcuts != null && plainSpace(shortcuts.key(Command.PLAY_PAUSE)), this::togglePlayback);
    private final Map<Command, Runnable> shortcutActions = new java.util.EnumMap<>(Command.class);
    private boolean shortcutsReady, shortcutsSaving;
    private List<Track> tracks = List.of();
    private Track selectedTrack;
    private List<SessionPlanner.Choice> sessionQueue = List.of();
    private final Set<Integer> sessionPlayed = new HashSet<>();
    private int sessionIndex = -1;
    private boolean sessionActive;
    private boolean draftPreview;
    private Map<Path,Integer> tasteRatings = Map.of();
    private long tasteGeneration;
    private boolean pendingLibraryQueue;
    private long playGeneration;
    private boolean compactLayout;
    private boolean workspaceReady;
    private boolean userSelectedTrack;
    private PlaybackMemory remembered;
    private double lastSavedPosition = -10;
    private double desiredVolume = 0.72;
    private final GlobalMediaKeys globalMediaKeys;
    private final WindowsMediaBridge windowsMedia;
    private boolean shuffleEnabled;
    private boolean repeatCurrent;
    private final StartupTrace startupTrace;
    private boolean shellPainted, firstCatalog, firstLibraryPage;

    public MainFrame(StartupTrace startupTrace) {
        super("Sangitam — Music Management");
        this.startupTrace = startupTrace;
        setIconImage(AppIcons.applicationIcon(64));
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
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
            notifications::show, this::closeApp, this::openAudioSettings);
        setJMenuBar(menuBar);
        menuBar.setKeyboardShortcutsAction(this::openShortcuts);
        menuBar.setRecentAction(() -> { if (workspaceReady) { if (!queueVisible) toggleQueueHistory(); queueHistory.showRecent(); } });
        menuBar.setPlayerVisibilityAction(() -> { Container transport = playerBar.getParent(); transport.setVisible(!transport.isVisible()); transport.getParent().revalidate(); });
        menuBar.setSortAction(index -> { if (workspaceReady) { libraryPanel.setRailVisible(true); libraryPanel.selectSort(index); } });
        installKeyboardShortcuts();
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(spaceTransport);
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowLostFocus(WindowEvent event) { spaceTransport.reset(); }
        });
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
        windowsMedia = new WindowsMediaBridge(command -> {
            switch (command.kind()) {
                case "PLAY" -> { if (player == null || player.state().status() != PlaybackState.Status.PLAYING) togglePlayback(); }
                case "PAUSE" -> { if (player != null && player.state().status() == PlaybackState.Status.PLAYING) player.pause(); }
                case "STOP" -> stopPlayback();
                case "NEXT" -> nextTrack();
                case "PREVIOUS" -> previousTrack();
                case "SEEK" -> { if (player != null && player.state().durationSeconds() > 0) seekTo(command.value() / player.state().durationSeconds()); }
                case "SHUFFLE" -> setShuffle(command.value() == 1);
                case "REPEAT" -> setRepeat(command.value() == 1);
                default -> { }
            }
        }, globalMediaKeys::setNativeTransportActive);
    }

    /** Called after the first paint so the transport is visible before other screens are built. */
    public void loadWorkspace() {
        if (workspaceReady || !isDisplayable()) return;
        sangitam.desktop.storage.ProviderCredentials.preload(null);
        globalMediaKeys.start();
        windowsMedia.start();
        dashboard = new DashboardPanel(this::openImportDialog, this::playTrack, notifications::show,
            this::buildSession, new SessionQueuePanel.Actions() {
                @Override public void start(List<SessionPlanner.Choice> choices) { startSession(choices); }
                @Override public void play(SessionPlanner.Choice choice, List<SessionPlanner.Choice> choices) {
                    startSessionAt(choice, choices);
                }
                @Override public void edited(List<SessionPlanner.Choice> choices) { if(!draftPreview) editSession(choices); }
                @Override public void save(String name, List<SessionPlanner.Choice> choices) {
                    savePlaylist(name, choices);
                }
                @Override public void load(Playlist playlist) { loadPlaylist(playlist); }
                @Override public void delete(Playlist playlist) { deletePlaylist(playlist); }
                @Override public void taste(Track track,int rating) { rateTaste(List.of(track),rating); }
                @Override public void replace(int index,List<SessionPlanner.Choice> choices) { replaceSessionSlot(index,choices); }
            }, this::openCollection, entry -> libraryPanel.menuForEntry(entry));
        search = new SearchPanel(new LibrarySearchStore(store)::search, this::playTrack, (category, hit) -> {
            var kind = category == LibrarySearchStore.Category.ALBUMS ? sangitam.desktop.model.LibraryCollections.Kind.ALBUM : sangitam.desktop.model.LibraryCollections.Kind.ARTIST;
            String id = category == LibrarySearchStore.Category.ALBUMS
                ? LibraryEntry.lower(hit.title()) + "\u001f" + LibraryEntry.lower(hit.artist()) : LibraryEntry.lower(hit.title());
            LibraryEntry entry = libraryPanel.entry(new Key(kind, id));
            if (entry != null) openCollection(entry);
            else notifications.show("Collection is refreshing. Try opening it again.");
        }, track -> libraryPanel.trackMenu(track));
        collectionPage = new LibraryDetailPanel(() -> showScreen("home"), this::playCollection, this::addToQueue,
            track -> libraryPanel.trackMenu(track));
        artistPage = new ArtistPanel(store, (songs, shuffle) -> {
            setShuffle(shuffle);
            if (shuffle) { var shuffled = new java.util.ArrayList<>(songs); java.util.Collections.shuffle(shuffled); playCollection(shuffled, 0); }
            else playCollection(songs, 0);
        }, this::openCollection, this::openArtist, track -> libraryPanel.trackMenu(track), notifications::show);
        artistPage.onEnriched(() -> library.refreshFromStore(ignored -> { }));
        artistPage.setDiscoveryAction(name -> { showScreen("discover"); discover.prepareArtistSearch(name); });
        screens.removeAll();
        screens.add(dashboard, "home");
        screens.add(search, "search");
        screens.add(collectionPage, "collection");
        screens.add(artistPage, "artist");
        libraryPanel = new LibraryPanel(new LibraryPanel.Actions() {
            @Override public void importMusic(long folder) { openImportDialog(folder); }
            @Override public void rescan() { rescanLibrary(); }
            @Override public void play(List<Track> tracks) {
                if (tracks.size() == 1) playTrack(tracks.getFirst());
                else playCollection(tracks, 0);
            }
            @Override public void queue(List<Track> tracks) { addToQueue(tracks); }
            @Override public void taste(List<Track> tracks,int rating) { rateTaste(tracks,rating); }
            @Override public Integer tasteRating(List<Track> tracks) {
                var values=tracks.stream().map(track->tasteRatings.getOrDefault(track.path(),0)).distinct().toList();
                return values.size()==1 ? values.getFirst() : null;
            }
            @Override public void editDetails(List<Track> tracks, boolean group) { editTrackDetails(tracks, group); }
            @Override public void restoreDetails(List<Track> tracks) { restoreTrackDetails(tracks); }
            @Override public void remove(List<Path> paths, Path scope) {
                library.removeFromLibrary(paths, scope, notifications::show);
            }
            @Override public void open(LibraryEntry entry) { openCollection(entry); }
            @Override public void organize(String message, LibraryPanel.CollectionEdit edit) {
                changeCollections(message, edit, null);
            }
            @Override public void deletePlaylist(long id) { removePlaylist(id); }
            @Override public void entriesChanged() { refreshCollectionPage(); }
            @Override public SmartPlaylistStore.Preview previewSmart(SmartPlaylistRule rule) throws Exception { return smartPlaylists.preview(rule); }
        });
        libraryPanel.onFirstPage(() -> { if (firstCatalog && !firstLibraryPage) { firstLibraryPage = true; startupTrace.mark("library_page"); } });
        queueHistory = new QueueHistoryPanel(index -> {
            if (index >= 0 && index < sessionQueue.size()) { sessionActive = true; pendingLibraryQueue = false; playSessionIndex(index); }
        }, this::playTrack, track -> libraryPanel.trackMenu(track), this::toggleQueueHistory);
        libraryRail = new JSplitPane(JSplitPane.VERTICAL_SPLIT, libraryPanel, null);
        libraryRail.setBorder(null); libraryRail.setDividerSize(0); libraryRail.setResizeWeight(.5);
        libraryPanel.setExpansionListener(expanded -> {
            if (expanded && queueVisible) { queueVisible = false; libraryRail.setBottomComponent(null); libraryRail.setDividerSize(0); playerBar.setQueueVisible(false); }
        });
        workspace.add(libraryRail, BorderLayout.EAST);
        workspaceReady = true;
        startupTrace.mark("workspace");
        loadTasteRatings();
        workspace.revalidate();
        workspace.repaint();
        updateResponsiveLayout();
        library.addCatalogListener(this::onLibraryChanged);
        memory.load(this::restorePlayback, notifications::show);
        volumeSave.setRepeats(false);
        memory.loadVolume(volume -> { if (!volumeTouched) { desiredVolume = volume; playerBar.setVolumeFraction(volume); if (player != null) player.setVolume(volume); } });
        listeningSave.start();
        library.loadInitial(notifications::show);
        dashboard.setAnalyzeAction(() -> library.analyzeNow(notifications::show));
        loadAudioSettings();
        loadShortcuts();
    }

    private JComponent buildShell() {
        JPanel root = new JPanel(new BorderLayout()) {
            @Override public void paint(Graphics graphics) {
                super.paint(graphics);
                if (!shellPainted && isShowing()) {
                    shellPainted = true;
                    startupTrace.mark("first_paint");
                    SwingUtilities.invokeLater(MainFrame.this::loadWorkspace);
                }
            }
        };
        root.setBackground(Theme.BLACK);
        // Construct the player before the dashboard, catalog, or audio decoder.
        playerBar = new PlayerBar(new PlayerBar.Actions() {
            @Override public void playPause() { togglePlayback(); }
            @Override public void previous() { previousTrack(); }
            @Override public void next() { nextTrack(); }
            @Override public void seek(double fraction) { seekTo(fraction); }
            @Override public void volume(double fraction) {
                desiredVolume = fraction;
                volumeTouched = true;
                volumeSave.restart();
                if (player != null) player.setVolume(fraction);
            }
            @Override public void stop() { stopPlayback(); }
            @Override public void toggleShuffle() { setShuffle(!shuffleEnabled); }
            @Override public void toggleRepeat() { setRepeat(!repeatCurrent); }
            @Override public void audioSettings() { openAudioSettings(); }
            @Override public void openTrack(Track track) { openPlayingTrack(track); }
            @Override public void openArtist(String name) { MainFrame.this.openArtist(name); }
            @Override public void toggleLiked(Track track) {
                if (!workspaceReady) return;
                var key = new Key(sangitam.desktop.model.LibraryCollections.Kind.SONG, track.path().toString());
                changeCollections("Liked Songs updated", store -> store.togglePinned(key), null);
            }
            @Override public void toggleQueue() { toggleQueueHistory(); }
            @Override public void toggleSpectrum() {
                MainFrame.this.toggleSpectrum();
            }
        });
        JPanel lower = new JPanel(new BorderLayout());
        spectrumPanel = new SpectrumPanel(); spectrumPanel.setVisible(false);
        JPanel transport = new JPanel(new BorderLayout()); transport.add(playerBar, BorderLayout.CENTER); transport.add(spectrumPanel, BorderLayout.SOUTH);
        lower.add(transport, BorderLayout.CENTER);
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
        if (servicesClosed) return;
        List<Track> updated = catalog.tracks();
        tracks = updated;
        dashboard.setTracks(updated); dashboard.setAnalysisCatalog(catalog);
        search.setCatalogState(catalog.tags(), catalog.manualTags(), catalog.unavailable());
        libraryPanel.setCatalog(catalog);
        playerBar.setCoverState(catalog.stamps(), catalog.unavailable());
        queueHistory.setCatalog(catalog);
        if (rediscover != null) rediscover.setCatalog(catalog);
        if (!firstCatalog && library.initialLoaded()) { firstCatalog = true; startupTrace.mark("catalog_" + updated.size()); }
        refreshPlaylists(); // smart membership follows imports, edits, availability and recorded plays
        if (selectedTrack != null) {
            selectedTrack = catalog.byPath().get(selectedTrack.path());
        }
        restoreLastIfAvailable();
        if (selectedTrack == null && !updated.isEmpty()) selectedTrack = updated.get(0);
    }

    private void restorePlayback(PlaybackMemory saved) {
        if (servicesClosed) return;
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
        if (servicesClosed) return;
        if (state.track() != null && selectedTrack != null && state.track().path().equals(selectedTrack.path()))
            state = new PlaybackState(selectedTrack, state.status(), state.positionSeconds(), state.durationSeconds(), state.error(), state.seekable());
        listeningClock.observe(state);
        windowsMedia.publish(state, shuffleEnabled, repeatCurrent);
        if (player != null) playerBar.setMaximumVolume(player.maximumVolume());
        if (state.status() == PlaybackState.Status.IDLE && remembered != null && !userSelectedTrack) return;
        playerBar.setState(state);
        spectrumPanel.setStatus(player != null && player.supportsSpectrum(), state.status() == PlaybackState.Status.PLAYING);
        updateLiked(state.track());
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
        if (queueHistory != null) queueHistory.setQueue(sessionQueue, -1);
        userSelectedTrack = true;
        selectedTrack = track;
        playGeneration++;
        savePosition(track, 0);
        ensurePlayer().play(track);
    }

    private void loadTasteRatings() {
        long generation=tasteGeneration;
        new SwingWorker<Map<Path,Integer>,Void>() {
            protected Map<Path,Integer> doInBackground() throws Exception { synchronized(store){store.initialize();return new sangitam.desktop.storage.TasteStore(store).ratings();} }
            protected void done(){if(servicesClosed||generation!=tasteGeneration)return;try{tasteRatings=get();dashboard.setTaste(tasteRatings);}catch(Exception error){notifications.show("Saved taste preferences could not be loaded");}}
        }.execute();
    }
    private void rateTaste(List<Track> tracks,int rating) {
        tasteGeneration++;
        new SwingWorker<Void,Void>() { protected Void doInBackground() throws Exception { synchronized(store) { new sangitam.desktop.storage.TasteStore(store).rate(tracks.stream().map(Track::path).toList(),rating); } return null; }
            protected void done() { if(servicesClosed)return; try { get(); loadTasteRatings(); notifications.show("Taste profile updated for " + tracks.size() + " recordings"); } catch(Exception e) { notifications.show("Could not update taste profile"); } } }.execute();
    }
    private void replaceSessionSlot(int index,List<SessionPlanner.Choice> choices) {
        if(index<0||index>=choices.size())return;var source=library.catalog();
        new SwingWorker<Track,Void>() { protected Track doInBackground() throws Exception {
            sangitam.desktop.session.SessionContext context; synchronized(store){context=new sangitam.desktop.storage.TasteStore(store).context(java.time.ZonedDateTime.now());}
            var excluded=choices.stream().map(c->c.track().path()).collect(java.util.stream.Collectors.toSet());int target=source.tagsFor(choices.get(index).track()).energy();
            return source.tracks().stream().filter(t->!excluded.contains(t.path())&&!source.unavailable().contains(t.path())&&context.taste().getOrDefault(t.path(),0)>-2&&Files.isRegularFile(t.path()))
                .min(java.util.Comparator.comparingInt(t->Math.abs(source.tagsFor(t).energy()-target)-context.taste().getOrDefault(t.path(),0)*10)).orElse(null);
        } protected void done(){if(servicesClosed)return;try{Track replacement=get();if(replacement==null){notifications.show("No other eligible recording fits this slot");return;}if(!dashboard.currentQueue().equals(choices)){notifications.show("Journey changed; select the slot again");return;}var updated=new java.util.ArrayList<>(choices);updated.set(index,new SessionPlanner.Choice(replacement,"Alternative selected for a similar energy slot"));if(draftPreview)dashboard.setQueue(updated);else{editSession(updated);showQueue();}}catch(Exception error){notifications.show("Could not replace this slot");}} }.execute();
    }
    private void buildSession(SessionPlanner.Request request) {
        LibraryService.Catalog source=library.catalog();
        if(sessionBuilder!=null)sessionBuilder.cancel(true);long generation=++sessionBuildGeneration;
        listeningClock.observe(player==null?PlaybackState.idle():player.state()); memory.saveListening(listeningClock.drain());
        notifications.show("Shaping your temporary listening journey…");
        memory.afterPending(() -> {
            if(servicesClosed||generation!=sessionBuildGeneration)return;
            sessionBuilder=new SwingWorker<>() {
                private String explanation;
                protected SessionPlanner.Plan doInBackground() throws Exception {
                    sangitam.desktop.session.SessionContext context; synchronized(store){context=new sangitam.desktop.storage.TasteStore(store).context(java.time.ZonedDateTime.now());}
                    explanation=request.useContext() ? context.hint(source.tags()).explanation() : "";
                    var playable=source.tracks().stream().filter(t->!source.unavailable().contains(t.path())&&Files.isRegularFile(t.path())).toList();
                    return planner.build(request,playable,source.tags(),source.playCounts(),context);
                }
                protected void done(){if(servicesClosed||isCancelled()||generation!=sessionBuildGeneration)return;try{var plan=get();draftPreview=sessionActive;if(!sessionActive){sessionQueue=plan.choices();pendingLibraryQueue=true;sessionIndex=-1;}dashboard.showPlan(plan);dashboard.setContext(explanation);if(!sessionActive)queueHistory.setQueue(sessionQueue,-1);notifications.show(plan.choices().isEmpty()?"No eligible music. Check availability and taste exclusions.":"Temporary journey ready · "+plan.choices().size()+" recordings");}catch(Exception error){notifications.show("Could not build this journey. Retry after analysis finishes.");}}
            };sessionBuilder.execute();
        });
    }

    private void refreshPlaylists() {
        if (servicesClosed) return;
        if (collectionRefreshActive) { collectionRefreshPending = true; return; }
        collectionRefreshActive = true;
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
        draftPreview = false;
        sessionActive = false;
        pendingLibraryQueue = false;
        sessionQueue = choices;
        sessionIndex = -1;
        int seconds = choices.stream().mapToInt(choice -> choice.track().durationSeconds() > 0
            ? (int) Math.round(choice.track().durationSeconds()) : 180).sum();
        dashboard.showPlaylist(playlist, new SessionPlanner.Plan(choices, Math.max(1, seconds / 60), seconds));
        queueHistory.setQueue(sessionQueue, -1);
        int missing = playlist.tracks().size() - choices.size();
        notifications.show("Loaded " + playlist.name() + (missing == 0 ? "" : " · " + missing + " unavailable tracks"));
    }

    private void deletePlaylist(Playlist playlist) {
        if (playlist.likedSongs()) { notifications.show("Use the heart or Unlike to remove songs from this playlist"); return; }
        removePlaylist(playlist.id());
    }

    private void removePlaylist(long id) {
        changeCollections("Playlist deleted", ignored -> store.deletePlaylist(id), null);
    }

    private record CollectionsUpdate(LibraryCollections data, HomeLibraryData home, List<Track> recent, long revision) { }

    private void changeCollections(String message, LibraryPanel.CollectionEdit edit, String selection) {
        LibraryService.Catalog input = library.catalog();
        SwingWorker<CollectionsUpdate, Void> job = new SwingWorker<>() {
            @Override protected CollectionsUpdate doInBackground() throws Exception {
                synchronized (store) {
                    if (!collectionStorageReady) {
                        store.initialize();
                        collectionStorageReady = true;
                    }
                    edit.run(collections);
                    LibraryCollections data = collections.load().withLikedSongs();
                    List<Track> recent = homeStore.recent(50);
                    return new CollectionsUpdate(data, HomeLibraryData.build(input, data, recent), recent, ++collectionRevision);
                }
            }
            @Override protected void done() {
                if (servicesClosed) return;
                try {
                    CollectionsUpdate update = get();
                    if (update.revision() > shownCollectionRevision) {
                        shownCollectionRevision = update.revision();
                        libraryPanel.setCollections(update.data());
                        dashboard.setPlaylists(update.data().playlists());
                        queueHistory.setRecent(update.recent());
                        updateLiked(selectedTrack);
                        if (openArtist != null) refreshArtist();
                        if (input == library.catalog()) dashboard.setHomeData(update.home());
                        else refreshPlaylists(); // a catalog publish overtook this worker
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
                } finally {
                    if (message == null) {
                        collectionRefreshActive = false;
                        if (collectionRefreshPending) { collectionRefreshPending = false; refreshPlaylists(); }
                    }
                }
            }
        };
        if (!servicesClosed) collectionWorker.execute(job);
    }

    private void openCollection(LibraryEntry entry) {
        if (entry.kind() == sangitam.desktop.model.LibraryCollections.Kind.ARTIST) { openArtist(entry.name()); return; }
        openArtist = null;
        openCollection = entry.key();
        collectionPage.showEntry(entry, library.catalog());
        libraryPanel.collapse();
        screenLayout.show(screens, "collection");
    }

    private void refreshCollectionPage() {
        if (openCollection != null) collectionPage.showEntry(libraryPanel.entry(openCollection), library.catalog());
    }

    private void openPlayingTrack(Track track) {
        if (!workspaceReady) return;
        if (ArtistPageData.hasAlbum(track)) {
            List<Track> album = tracks.stream().filter(value -> ArtistPageData.albumId(value).equals(ArtistPageData.albumId(track))).toList();
            openCollection(new LibraryEntry(new Key(sangitam.desktop.model.LibraryCollections.Kind.ALBUM, ArtistPageData.albumId(track)), track.album(), track.artist(), album, 0, 0, 0));
        } else editTrackDetails(List.of(track), false);
    }
    private void openArtist(String name) {
        if (!workspaceReady) return;
        openArtist = name; openCollection = null; libraryPanel.collapse();
        refreshArtist(); screenLayout.show(screens, "artist");
    }
    private void refreshArtist() {
        String name = openArtist; long generation = ++artistRefresh;
        if (player != null) listeningClock.observe(player.state());
        memory.saveListening(listeningClock.drain());
        memory.afterPending(() -> { if (!servicesClosed && generation == artistRefresh && java.util.Objects.equals(name, openArtist)) artistPage.showArtist(name, library.catalog(), libraryPanel.collections()); });
    }
    private void updateLiked(Track track) {
        playerBar.setLiked(track != null && libraryPanel != null && libraryPanel.collections().pins().contains(new Key(sangitam.desktop.model.LibraryCollections.Kind.SONG, track.path().toString())));
    }
    private void toggleQueueHistory() {
        if (!workspaceReady) return;
        queueVisible = !queueVisible; libraryPanel.collapse();
        libraryRail.setBottomComponent(queueVisible ? queueHistory : null); libraryRail.setDividerSize(queueVisible ? 6 : 0);
        if (queueVisible) { libraryPanel.setRailVisible(true); SwingUtilities.invokeLater(() -> libraryRail.setDividerLocation(.5)); showQueue(); }
        playerBar.setQueueVisible(queueVisible); workspace.revalidate(); workspace.repaint();
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
        TrackDetailsPanel editor = new TrackDetailsPanel(tracks, group, library.catalog().tagsFor(tracks.getFirst()));
        while (JOptionPane.showConfirmDialog(this, editor, group ? "Edit collection details" : "Edit track details",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                LibraryStore.DetailChanges changes = editor.changes();
                library.editDetails(tracks.stream().map(Track::path).toList(), changes, notifications::show);
                return;
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Check details", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void restoreTrackDetails(List<Track> tracks) {
        if (tracks.isEmpty()) return;
        DetailResetPanel editor = new DetailResetPanel(tracks.size());
        while (JOptionPane.showConfirmDialog(this, editor, "Restore file details", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            var selection = editor.selection();
            if (selection.empty()) {
                JOptionPane.showMessageDialog(this, "Select at least one detail to restore.", "Choose details", JOptionPane.INFORMATION_MESSAGE);
                continue;
            }
            notifications.show("Reading file details…");
            library.restoreDetails(tracks.stream().map(Track::path).toList(), selection, notifications::show);
            return;
        }
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
        if (!draftPreview) dashboard.setQueue(sessionQueue);
        if (queueHistory != null) queueHistory.setQueue(sessionQueue, sessionActive ? sessionIndex : -1);
    }

    private void startSession(List<SessionPlanner.Choice> choices) {
        draftPreview=false;
        if (choices.isEmpty()) { notifications.show("Build or add a track first"); return; }
        sessionQueue = List.copyOf(choices);
        pendingLibraryQueue = false;
        sessionPlayed.clear();
        sessionActive = true;
        playSessionIndex(0);
    }

    private void startSessionAt(SessionPlanner.Choice choice, List<SessionPlanner.Choice> choices) {
        draftPreview=false;
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
        if (queueHistory != null) queueHistory.setQueue(sessionQueue, sessionActive ? sessionIndex : -1);
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
        if (queueHistory != null) queueHistory.setQueue(sessionQueue, sessionIndex);
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
        windowsMedia.publish(player == null ? PlaybackState.idle() : player.state(), shuffleEnabled, repeatCurrent);
        if (menuBar != null) menuBar.setShuffleSelected(enabled);
        notifications.show(enabled ? "Shuffle enabled" : "Shuffle disabled");
    }

    private void setRepeat(boolean enabled) {
        repeatCurrent = enabled;
        playerBar.setRepeat(enabled);
        windowsMedia.publish(player == null ? PlaybackState.idle() : player.state(), shuffleEnabled, repeatCurrent);
        if (menuBar != null) menuBar.setRepeatSelected(enabled);
        notifications.show(enabled ? "Repeat current track enabled" : "Repeat disabled");
    }

    private void adjustVolume(int steps) {
        double maximum = player == null ? AudioGain.MAX_VOLUME : player.maximumVolume();
        desiredVolume = Math.max(0, Math.min(maximum, desiredVolume + steps * 0.05));
        playerBar.setVolumeFraction(desiredVolume);
        if (player != null) player.setVolume(desiredVolume);
        notifications.show("Volume " + AudioGain.label(desiredVolume));
    }

    private void showScreen(String name) {
        if (!workspaceReady) { notifications.show("Workspace is loading…"); return; }
        libraryPanel.collapse();
        openCollection = null;
        openArtist = null;
        if (name.equals("discover") && discover == null) {
            discover = new DiscoverPanel(notifications::show);
            screens.add(discover, "discover");
        }
        if (name.equals("rediscover") && rediscover == null) {
            rediscover = new RediscoverPanel(history, store, this::playTrack, notifications::show);
            screens.add(rediscover, "rediscover");
            rediscover.setCatalog(library.catalog());
        }
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

    private void loadAudioSettings() {
        new SwingWorker<AudioSettings, Void>() {
            @Override protected AudioSettings doInBackground() throws Exception { return audioSettingsStore.load(); }
            @Override protected void done() {
                if (servicesClosed) return;
                try { audioSettings = get(); }
                catch (Exception error) { notifications.show("Could not load audio settings. Defaults are in use."); }
                audioSettingsReady = true;
                playerBar.setAudioSettings(audioSettings);
                if (player != null) player.setAudioSettings(audioSettings);
            }
        }.execute();
    }

    private void openAudioSettings() {
        if (!audioSettingsReady) { notifications.show("Audio settings are loading…"); return; }
        if (libraryPanel != null) libraryPanel.collapse();
        new AudioSettingsDialog(this, audioSettings, ensurePlayer(), audioSettingsStore, next -> {
            audioSettings = next;
            playerBar.setAudioSettings(next);
            notifications.show("Audio settings saved");
        }).setVisible(true);
    }

    private void installKeyboardShortcuts() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        shortcutActions.put(Command.IMPORT, this::openImportDialog); shortcutActions.put(Command.QUIT, this::closeApp);
        shortcutActions.put(Command.QUICK, this::openCommandPalette); shortcutActions.put(Command.SEARCH, () -> showScreen("search"));
        shortcutActions.put(Command.PLAY_PAUSE, this::togglePlayback); shortcutActions.put(Command.PREVIOUS, this::previousTrack); shortcutActions.put(Command.NEXT, this::nextTrack);
        shortcutActions.put(Command.VOLUME_UP, () -> adjustVolume(1)); shortcutActions.put(Command.VOLUME_DOWN, () -> adjustVolume(-1));
        shortcutActions.put(Command.STOP, this::stopPlayback); shortcutActions.put(Command.SHUFFLE, () -> setShuffle(!shuffleEnabled)); shortcutActions.put(Command.REPEAT, () -> setRepeat(!repeatCurrent));
        shortcutActions.put(Command.EQ, this::openAudioSettings); shortcutActions.put(Command.QUEUE, this::toggleQueueHistory); shortcutActions.put(Command.LIBRARY, this::toggleLibrary); shortcutActions.put(Command.SPECTRUM, this::toggleSpectrum);
        applyShortcuts(ShortcutBindings.defaults(shortcut));
    }

    private void toggleSpectrum() {
        spectrumVisible = !spectrumVisible; spectrumPanel.setVisible(spectrumVisible);
        PlayerService decoder = ensurePlayer(); decoder.setSpectrumListener(spectrumVisible ? spectrumPanel::setSamples : null);
        spectrumPanel.setStatus(decoder.supportsSpectrum(), decoder.state().status() == PlaybackState.Status.PLAYING); spectrumPanel.getParent().revalidate();
    }
    private void applyShortcuts(ShortcutBindings next) {
        InputMap map = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (shortcuts != null) for (Command command : Command.values()) if (shortcuts.key(command) != null) map.remove(shortcuts.key(command));
        shortcuts = next;
        for (Command command : Command.values()) {
            KeyStroke key = next.key(command); if (key == null) continue;
            if (command == Command.PLAY_PAUSE && plainSpace(key)) continue;
            bind(key, "shortcut-" + command.name(), () -> {
                Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (key.getModifiers() == 0 && key.getKeyCode() == KeyEvent.VK_SPACE && focus instanceof javax.swing.text.JTextComponent) return;
                shortcutActions.get(command).run();
            });
        }
        menuBar.setShortcutBindings(next);
    }
    private static boolean plainSpace(KeyStroke key) {
        return key != null && key.getModifiers() == 0 && key.getKeyCode() == KeyEvent.VK_SPACE;
    }
    private ShortcutStore shortcutStore() { return new ShortcutStore(store, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()); }
    private void loadShortcuts() {
        new SwingWorker<ShortcutBindings, Void>() {
            @Override protected ShortcutBindings doInBackground() throws Exception { synchronized (store) { store.initialize(); return shortcutStore().load(); } }
            @Override protected void done() { if (servicesClosed) return; try { applyShortcuts(get()); } catch (Exception error) { notifications.show("Could not load shortcuts. Defaults are active; save new bindings to replace them."); } shortcutsReady = true; }
        }.execute();
    }
    private void openShortcuts() {
        if (!shortcutsReady || shortcutsSaving) { notifications.show(shortcutsSaving ? "Shortcuts are saving…" : "Shortcuts are loading…"); return; }
        ShortcutPanel editor = new ShortcutPanel(shortcuts, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        while (JOptionPane.showConfirmDialog(this, editor, "Keyboard shortcuts", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            ShortcutBindings next;
            try { next = editor.value(); } catch (IllegalArgumentException error) { JOptionPane.showMessageDialog(this, error.getMessage(), "Check shortcuts", JOptionPane.ERROR_MESSAGE); continue; }
            shortcutsSaving = true;
            new SwingWorker<Void, Void>() {
                @Override protected Void doInBackground() throws Exception { synchronized (store) { shortcutStore().save(next); } return null; }
                @Override protected void done() { shortcutsSaving = false; if (servicesClosed) return; try { get(); applyShortcuts(next); notifications.show("Keyboard shortcuts saved"); } catch (Exception error) { notifications.show("Shortcuts could not be saved. Previous bindings remain active."); } }
            }.execute(); return;
        }
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
        commands.put("Audio settings and equalizer", this::openAudioSettings);
        commands.put("Keyboard shortcuts", this::openShortcuts);
        new CommandPaletteDialog(this, commands).setVisible(true);
    }

    private void toggleLibrary() {
        if (libraryPanel == null) { notifications.show("Library is loading…"); return; }
        libraryPanel.collapse();
        libraryPanel.setRailVisible(!libraryPanel.isRailRequestedVisible());
        libraryPanel.getParent().revalidate();
        if (menuBar != null) menuBar.setLibrarySelected(libraryPanel.isRailRequestedVisible());
        notifications.show(libraryPanel.isRailRequestedVisible() ? "Library shown" : "Library hidden");
    }

    /** On narrower windows the library yields space to the main task. */
    private void updateResponsiveLayout() {
        if (libraryPanel == null) return;
        boolean nowCompact = getWidth() < 1180;
        if (nowCompact == compactLayout) return;
        compactLayout = nowCompact;
        libraryPanel.setRailVisible(!nowCompact || queueVisible);
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
            player.setVolume(desiredVolume);
            player.setAudioSettings(audioSettings);
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
        if (servicesClosed) return; servicesClosed = true;
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(spaceTransport);
        startupTrace.close();
        if(sessionBuilder!=null)sessionBuilder.cancel(true); sessionBuildGeneration++;
        listeningClock.observe(PlaybackState.idle()); listeningSave.stop(); volumeSave.stop();
        memory.saveListening(listeningClock.drain()); if (volumeTouched) memory.saveVolume(desiredVolume);
        library.cancelAnalysis();
        globalMediaKeys.close();
        windowsMedia.close();
        playerBar.close();
        if (artistPage != null) artistPage.close();
        if (libraryPanel != null) libraryPanel.close();
        if (dashboard != null) dashboard.close();
        collectionWorker.shutdownNow();
        if (rediscover != null) rediscover.close();
        if (search != null) search.close();
        if (player != null) player.close();
        memory.close();
        history.close();
    }
}
