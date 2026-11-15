package sangitam.desktop.ui;

import sangitam.desktop.library.ScorePdf;
import sangitam.desktop.library.ScoreSync;
import sangitam.desktop.library.ScoreWork;
import sangitam.desktop.library.ScoreMovementPages;
import sangitam.desktop.library.EngravedScoreMap;
import sangitam.desktop.library.OpticalScoreRecognition;
import sangitam.desktop.discovery.MutopiaScores;
import sangitam.desktop.discovery.CommonsScores;
import sangitam.desktop.discovery.ImslpScores;
import sangitam.desktop.discovery.NoctaveScores;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.PlaybackState;
import sangitam.desktop.storage.SheetMusicStore;
import sangitam.desktop.storage.SheetMusicStore.Edition;
import sangitam.desktop.storage.ManagedScoreStore;
import sangitam.desktop.storage.AppPreferences;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Modeless score viewer. Rendering and catalog writes never run on Swing's event thread. */
final class SheetMusicDialog extends JFrame {
    private static final float[] ZOOM_LEVELS = {.50f, .625f, .75f, .875f, 1f, 1.125f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};
    private static final int UNMARKED_PAGE_LIMIT = 12;
    private static final java.util.regex.Pattern SAVED_RANGE =
        java.util.regex.Pattern.compile("^range/(?:opening-v[23]/)?\\d+:(\\d+):(\\d+):");
    private record Prepared(ManagedScoreStore.Recording recording, ScoreSync.Reference reference, EngravedScoreMap layout) { }

    private Track track;
    private final SheetMusicStore store;
    private final ManagedScoreStore managed;
    private final MutopiaScores provider;
    private final ImslpScores imslp = new ImslpScores();
    private ScoreWork composition;
    private final ScorePlayhead playhead;
    private final DoubleConsumer seek;
    private final JComboBox<Edition> editions = new JComboBox<>();
    private final JButton attach = UiFactory.button("Attach PDF…", null, true);
    private final JButton remove = UiFactory.button("Remove PDF…", null, false);
    private final JButton download = UiFactory.button("Download score", null, true);
    private final JButton sources = UiFactory.button("Sources…", null, false);
    private final JButton align = UiFactory.button("Sync recording", null, false);
    private final JButton previous = UiFactory.button("Previous", null, false);
    private final JButton next = UiFactory.button("Next", null, false);
    private final JButton pageNumber = UiFactory.button("Page 0000 / 0000", null, false);
    private final JComboBox<String> zoom = new JComboBox<>();
    private final JButton fullscreen = UiFactory.button("Full screen", null, false);
    private final JButton transport = PlayerBar.control(AppIcons.Kind.PLAY, "Play", true);
    private final JButton shuffle = PlayerBar.control(AppIcons.Kind.SHUFFLE, "Shuffle", false);
    private final JButton repeat = PlayerBar.control(AppIcons.Kind.REPEAT, "Repeat current track", false);
    private final JSlider position = new JSlider(0, 100_000);
    private final JLabel playbackTime = new JLabel("0:00 / 0:00");
    private final JButton cancel = UiFactory.button("Cancel", null, false);
    private final JCheckBox follow = new JCheckBox("Follow playback");
    private final JButton mark = UiFactory.button("Set page time…", null, false);
    private final JButton jump = UiFactory.button("Seek to page", null, false);
    private final JLabel timing = new JLabel();
    private final JLabel status = new JLabel("Loading scores…");
    private final ScoreCanvas image = new ScoreCanvas(this::visiblePage, status::setText);
    private final PixelScrollPane scroll = new PixelScrollPane(image);
    private String displayedEdition;
    private boolean updatingZoom;
    private long userPannedAt;

    private boolean busy;
    private boolean updatingEditions;
    private boolean closed;
    private boolean editingTime;
    private boolean downloadOnOpen;
    private ManagedScoreStore.Recording managedRecording;
    private EngravedScoreMap layout;
    private EngravedScoreMap.Position cursor;
    private int page = 1;
    private PlaybackState playback = PlaybackState.idle();
    private SwingWorker<?, Void> operation;
    private SwingWorker<ManagedScoreStore.Asset, Void> browserImport;
    private boolean showNotes, transportUpdate;
    private java.awt.GraphicsDevice fullScreenDevice;
    private Rectangle windowBounds;

    record Transport(Runnable playPause, Runnable previous, Runnable next, Runnable shuffle, Runnable repeat) { }

    SheetMusicDialog(Frame owner, Track track, SheetMusicStore store, ManagedScoreStore managed, MutopiaScores provider,
                     boolean downloadOnOpen, DoubleConsumer seek, Transport actions) {
        super("Sheet music — " + track.title());
        this.track = track;
        this.store = store;
        this.managed = managed;
        this.provider = provider;
        this.downloadOnOpen = downloadOnOpen;
        composition = ScoreWork.of(track);
        this.seek = seek;
        if (owner != null) setIconImages(owner.getIconImages());
        playhead = new ScorePlayhead(seek, this::updateCursor);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        editions.getAccessibleContext().setAccessibleName("Score edition");
        editions.setPrototypeDisplayValue(new Edition("", "Choose a score edition", 0, new java.util.TreeMap<>()));
        pageNumber.setPreferredSize(pageNumber.getPreferredSize());
        pageNumber.setToolTipText("Go to a page");
        zoom.getAccessibleContext().setAccessibleName("Page zoom");
        for (float level : ZOOM_LEVELS) zoom.addItem(Math.round(level * 100) + "%");
        zoom.setSelectedIndex(4);
        zoom.setToolTipText("Pinch or Ctrl + wheel to zoom. Scroll to pan.");
        follow.setSelected(true);
        follow.setToolTipText("Turn pages at the marked times for this recording");
        jump.setToolTipText("Seek within the current recording to the marked page time");
        image.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (event.isPopupTrigger()) { scoreMenu().show(image, event.getX(), event.getY()); return; }
                if (!SwingUtilities.isLeftMouseButton(event)) return;
                if (layout == null || !playback.seekable()) return;
                int clickedPage = image.pageAt(event.getY());
                var bounds = image.pageBounds(clickedPage);
                if (!bounds.contains(event.getPoint())) return;
                double point = layout.referenceAt(clickedPage, (event.getX() - bounds.x) / (double) bounds.width,
                    (event.getY() - bounds.y) / (double) bounds.height, playhead.scoreTime());
                if (Double.isFinite(point)) seek.accept(playhead.audioTime(point));
            }
            @Override public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) scoreMenu().show(image, event.getX(), event.getY());
            }
        });
        // Windows drivers can deliver a trackpad pinch as a Ctrl-modified wheel event.
        image.addMouseWheelListener(event -> {
            if (event.isControlDown()) zoomWheel(event);
            else {
                userPannedAt = System.nanoTime();
                scroll.dispatchEvent(SwingUtilities.convertMouseEvent(image, event, scroll));
            }
            event.consume();
        });
        scroll.getViewport().addChangeListener(event -> image.viewportChanged());
        scroll.getVerticalScrollBar().addAdjustmentListener(event -> {
            if (event.getValueIsAdjusting()) userPannedAt = System.nanoTime();
        });
        status.setBorder(Theme.padding(4, 8, 6, 8));
        timing.setBorder(Theme.padding(4, 8, 6, 8));
        var top = new JPanel(new BorderLayout());
        top.add(row(editions, download, attach, remove), BorderLayout.NORTH);
        top.add(row(previous, pageNumber, next, zoom, sources, fullscreen), BorderLayout.SOUTH);
        var bottom = new JPanel(new BorderLayout());
        bottom.add(playhead, BorderLayout.NORTH);
        var controls = new JPanel(new BorderLayout());
        controls.add(row(follow, mark, jump, align, cancel), BorderLayout.NORTH);
        var player = new JPanel(new BorderLayout(10, 0));
        player.setBorder(Theme.padding(3, 7, 3, 7));
        var backTrack = PlayerBar.control(AppIcons.Kind.PREVIOUS, "Previous track", false);
        var nextTrack = PlayerBar.control(AppIcons.Kind.NEXT, "Next track", false);
        player.add(row(shuffle, backTrack, transport, nextTrack, repeat), BorderLayout.NORTH);
        PlayerBar.style(position);
        player.add(position, BorderLayout.CENTER);
        player.add(playbackTime, BorderLayout.EAST);
        controls.add(player, BorderLayout.SOUTH);
        bottom.add(controls, BorderLayout.CENTER);
        var feedback = new JPanel(new BorderLayout());
        feedback.add(status, BorderLayout.CENTER);
        feedback.add(timing, BorderLayout.EAST);
        bottom.add(feedback, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        attach.addActionListener(event -> attach(true));
        remove.addActionListener(event -> remove());
        download.addActionListener(event -> download());
        sources.addActionListener(event -> sources());
        align.addActionListener(event -> prepare(true));
        cancel.addActionListener(event -> { if (operation != null) operation.cancel(true); });
        fullscreen.addActionListener(event -> toggleFullscreen());
        transport.addActionListener(event -> actions.playPause().run());
        backTrack.addActionListener(event -> actions.previous().run());
        nextTrack.addActionListener(event -> actions.next().run());
        shuffle.addActionListener(event -> actions.shuffle().run());
        repeat.addActionListener(event -> actions.repeat().run());
        position.getAccessibleContext().setAccessibleName("Playback position");
        position.addChangeListener(event -> {
            if (!transportUpdate && !position.getValueIsAdjusting() && playback.seekable())
                seek.accept(position.getValue() * playback.durationSeconds() / position.getMaximum());
        });
        var rootKeys = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        rootKeys.put(KeyStroke.getKeyStroke("F11"), "scoreFullscreen");
        getRootPane().getActionMap().put("scoreFullscreen", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { toggleFullscreen(); }
        });
        rootKeys.put(KeyStroke.getKeyStroke("ESCAPE"), "leaveScoreFullscreen");
        getRootPane().getActionMap().put("leaveScoreFullscreen", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { if (fullScreenDevice != null) toggleFullscreen(); }
        });
        editions.addActionListener(event -> { if (!updatingEditions) changeEdition(false); });
        previous.addActionListener(event -> browsePage(page - 1));
        next.addActionListener(event -> browsePage(page + 1));
        pageNumber.addActionListener(event -> choosePage());
        zoom.setEditable(false);
        zoom.addActionListener(event -> {
            if (updatingZoom || zoom.getSelectedIndex() < 0) return;
            var view = scroll.getViewport();
            image.zoom(ZOOM_LEVELS[zoom.getSelectedIndex()], new Point(view.getWidth() / 2, view.getHeight() / 3));
        });
        follow.addActionListener(event -> { userPannedAt = 0; followPlayback(); updateCursor(); });
        mark.addActionListener(event -> editTiming());
        jump.addActionListener(event -> {
            var edition = selected();
            if (edition != null && playback.seekable() && edition.timings().containsKey(page))
                seek.accept(edition.timings().get(page));
        });
        LibraryPanel.bind(scroll, KeyStroke.getKeyStroke("LEFT"), "previousScorePage", () -> browsePage(page - 1));
        LibraryPanel.bind(scroll, KeyStroke.getKeyStroke("RIGHT"), "nextScorePage", () -> browsePage(page + 1));
        var area = owner == null ? GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds()
            : new Rectangle(owner.getGraphicsConfiguration().getBounds());
        if (owner != null) {
            var insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(owner.getGraphicsConfiguration());
            area.setBounds(area.x + insets.left, area.y + insets.top,
                area.width - insets.left - insets.right, area.height - insets.top - insets.bottom);
        }
        setMinimumSize(new Dimension(715, 385));
        setSize(Math.min(710, area.width), Math.min(850, Math.max(325, area.height - 120)));
        setLocation(area.x + area.width - getWidth(), area.y);
        updateTransport();
        loadEditions(null);
    }

    private void toggleFullscreen() {
        if (fullScreenDevice == null) {
            windowBounds = getBounds();
            fullScreenDevice = getGraphicsConfiguration().getDevice();
            fullScreenDevice.setFullScreenWindow(this);
            fullscreen.setText("Exit full screen");
        } else {
            fullScreenDevice.setFullScreenWindow(null);
            fullScreenDevice = null;
            if (windowBounds != null) setBounds(windowBounds);
            fullscreen.setText("Full screen");
        }
    }

    private JPopupMenu scoreMenu() {
        var menu = new JPopupMenu();
        var notes = new JCheckBoxMenuItem("Show note strip", showNotes);
        notes.addActionListener(ignored -> {
            showNotes = notes.isSelected();
            playhead.showNotes(showNotes);
        });
        var screen = new JMenuItem(fullScreenDevice == null ? "Full screen" : "Exit full screen");
        screen.addActionListener(ignored -> toggleFullscreen());
        menu.add(notes);
        var attachPrivate = new JMenuItem("Attach PDF to this recording only…");
        attachPrivate.setEnabled(!busy);
        attachPrivate.addActionListener(ignored -> attach(false));
        menu.add(attachPrivate);
        var scope = new JMenuItem("Sync a page range…");
        scope.setEnabled(align.isEnabled());
        scope.addActionListener(ignored -> chooseSyncRange());
        menu.add(scope);
        menu.add(screen);
        return menu;
    }

    private void zoomWheel(java.awt.event.MouseWheelEvent event) {
        if (selected() == null || event.getPreciseWheelRotation() == 0) return;
        zoomAt(Math.pow(1.13, -event.getPreciseWheelRotation()), SwingUtilities.convertPoint(image, event.getPoint(), scroll.getViewport()));
    }

    private void zoomAt(double factor, Point anchor) {
        userPannedAt = System.nanoTime();
        image.zoom(image.zoom() * factor, anchor);
        int percentage = (int) Math.round(image.zoom() * 100);
        updatingZoom = true;
        String label = percentage + "%";
        var model = (DefaultComboBoxModel<String>) zoom.getModel();
        if (model.getIndexOf(label) < 0) model.setSelectedItem(label);
        else zoom.setSelectedItem(label);
        updatingZoom = false;
    }

    private void updateTransport() {
        boolean playable = playback.seekable() && playback.durationSeconds() > 0;
        boolean playing = playback.status() == PlaybackState.Status.PLAYING;
        String label = playing ? "Pause" : "Play";
        transport.setIcon(AppIcons.of(playing ? AppIcons.Kind.PAUSE : AppIcons.Kind.PLAY, 28, Theme.BLACK));
        transport.setToolTipText(label);
        transport.getAccessibleContext().setAccessibleName(label);
        transport.setEnabled(playback.status() != PlaybackState.Status.LOADING);
        position.setEnabled(playable);
        if (!position.getValueIsAdjusting()) {
            transportUpdate = true;
            position.setValue(playable ? (int) Math.round(position.getMaximum() * playback.positionSeconds() / playback.durationSeconds()) : 0);
            transportUpdate = false;
        }
        playbackTime.setText(shortTime(playback.positionSeconds()) + " / " + shortTime(playback.durationSeconds()));
    }

    private static String shortTime(double seconds) {
        int value = Math.max(0, (int) seconds);
        return "%d:%02d".formatted(value / 60, value % 60);
    }

    private static JPanel row(JComponent... components) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEADING, 7, 4));
        for (var component : components) row.add(component);
        return row;
    }

    private Edition selected() { return (Edition) editions.getSelectedItem(); }

    Track track() { return track; }

    void updateModes(boolean shuffled, boolean repeated) {
        PlayerBar.mode(shuffle, AppIcons.Kind.SHUFFLE, shuffled, "Shuffle");
        PlayerBar.mode(repeat, AppIcons.Kind.REPEAT, repeated, "Repeat current track");
    }

    void showTrack(Track next, boolean download) {
        if (track.path().equals(next.path()) && !download) return;
        if (operation != null) { operation.cancel(true); operation = null; }
        if (browserImport != null) { browserImport.cancel(true); browserImport = null; }
        var nextWork = ScoreWork.of(next);
        String retainedEdition = !composition.key().isBlank() && composition.key().equals(nextWork.key()) ? displayedEdition : null;
        track = next;
        composition = nextWork;
        setTitle("Sheet music — " + next.title());
        downloadOnOpen = download;
        updatingEditions = true;
        editions.removeAllItems();
        updatingEditions = false;
        follow.setSelected(true);
        userPannedAt = 0;
        if (retainedEdition == null) changeEdition(false);
        else {
            managedRecording = null;
            layout = null;
            cursor = null;
            image.cursor(null, false);
            playhead.setAlignment(null, null);
        }
        loadEditions(retainedEdition);
    }

    void updatePlayback(PlaybackState state) {
        boolean availabilityChanged = playback.seekable() != state.seekable()
            || playback.durationSeconds() != state.durationSeconds();
        playback = state;
        updateTransport();
        playhead.update(state);
        updateCursor();
        followPlayback();
        if (availabilityChanged) updateControls();
    }

    private void followPlayback() {
        var edition = selected();
        if (!busy && !editingTime && System.nanoTime() - userPannedAt > 2_000_000_000L && edition != null && follow.isSelected() && playback.seekable() && !edition.timings().isEmpty()) {
            var movement = movement(edition);
            int target = Math.max(movement == null ? 1 : movement.firstPage(), edition.pageAt(playback.positionSeconds()));
            if (target != page) showPage(target);
        }
    }

    private void browsePage(int number) {
        if (selected() == null || number < 1 || number > selected().pages()) return;
        follow.setSelected(false);
        showPage(number);
    }

    private void choosePage() {
        var edition = selected();
        if (edition == null) return;
        var spinner = new JSpinner(new SpinnerNumberModel(page, 1, edition.pages(), 1));
        if (JOptionPane.showConfirmDialog(this, spinner, "Go to page", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION)
            browsePage((Integer) spinner.getValue());
    }

    private void loadEditions(String preferred) {
        Track track = this.track;
        work(() -> {
            var result = new ArrayList<Edition>();
            for (var asset : managed.list(track)) {
                var recording = managed.recording(track, asset);
                result.add(new Edition(asset.id(), asset.name(), asset.pages(), recording.marks(), asset));
            }
            result.addAll(store.editions(track.path()));
            return result;
        }, result -> setEditions(result, preferred));
    }

    private void setEditions(List<Edition> result, String preferred) {
        updatingEditions = true;
        editions.setModel(new DefaultComboBoxModel<>(result.toArray(Edition[]::new)));
        result.stream().filter(edition -> edition.id().equals(preferred)).findFirst().ifPresent(editions::setSelectedItem);
        updatingEditions = false;
        if (downloadOnOpen) {
            downloadOnOpen = false;
            download();
        } else {
            changeEdition(false);
            if (result.isEmpty()) status.setText("No sheet music for this track. Download a score or attach a PDF.");
        }
    }

    private void changeEdition(boolean synchronize) {
        var edition = selected();
        var movement = movement(edition);
        managedRecording = null;
        layout = null;
        cursor = null;
        playhead.setAlignment(null, null);
        showPage(movement == null ? 1 : movement.firstPage());
        followPlayback();
        if (edition != null && edition.asset() != null) prepare(synchronize);
    }

    private ManagedScoreStore.Movement movement(Edition edition) {
        return edition == null || edition.asset() == null ? null : edition.asset().movement(composition.movement());
    }

    private void prepare(boolean synchronize) {
        prepare(synchronize, null);
    }

    private void chooseSyncRange() {
        var edition = selected();
        if (edition == null || edition.asset() == null || busy) return;
        var first = new JSpinner(new SpinnerNumberModel(page, 1, edition.pages(), 1));
        var last = new JSpinner(new SpinnerNumberModel(Math.min(page + UNMARKED_PAGE_LIMIT - 1, edition.pages()), 1, edition.pages(), 1));
        var fields = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
        fields.add(new JLabel("First PDF page")); fields.add(first);
        fields.add(new JLabel("Last PDF page")); fields.add(last);
        if (JOptionPane.showConfirmDialog(this, fields, "Sync only these pages", JOptionPane.OK_CANCEL_OPTION)
            != JOptionPane.OK_OPTION || selected() != edition) return;
        int from = (int) first.getValue(), to = (int) last.getValue();
        if (from > to) { status.setText("Choose a last page at or after the first page."); return; }
        showPage(from);
        prepare(true, new ScoreMovementPages.Range(from, to));
    }

    private void prepare(boolean synchronize, ScoreMovementPages.Range requestedRange) {
        Track track = this.track;
        ScoreWork composition = this.composition;
        var edition = selected();
        var movement = movement(edition);
        if (edition == null || edition.asset() == null) return;
        work(() -> {
            var recording = managed.recording(track, edition.asset());
            String referenceId = recording.alignment() == null ? null : recording.alignment().referenceId();
            boolean recognized = referenceId != null && !referenceId.isBlank();
            if (recognized || synchronize) {
                var ranges = movement == null || movement.number() == 0 ? ScoreMovementPages.verified(
                    edition.asset().file(), edition.pages(), composition.key()) : List.<ScoreMovementPages.Range>of();
                int movementNumber = composition.movement();
                if (movementNumber == 0 && !ranges.isEmpty()) {
                    try { movementNumber = ScoreMovementPages.identify(track.path(), track.durationSeconds(), composition.key()); }
                    catch (java.io.InterruptedIOException cancelled) { throw cancelled; }
                    catch (Exception unavailable) { /* The whole-score search can still be attempted. */ }
                }
                var verified = ScoreMovementPages.movement(movementNumber, ranges);
                if (verified == null && (movement == null || movement.number() == 0))
                    verified = ScoreMovementPages.bookmarks(edition.asset().file(), movementNumber);
                var rememberedRange = savedRange(referenceId, edition.pages());
                var manual = requestedRange == null ? rememberedRange : requestedRange;
                var bounds = manual == null ? verified : manual;
                int first = bounds != null ? bounds.first() : movement == null ? 1 : movement.firstPage();
                int last = bounds != null ? bounds.last() : movement == null ? edition.pages() : movement.lastPage();
                if (bounds == null && (movement == null || movement.number() == 0) && edition.pages() > UNMARKED_PAGE_LIMIT)
                    throw new java.io.IOException("Movement pages are unknown. Right-click the score and choose Sync a page range…");
                var preceding = ScoreMovementPages.movement(movementNumber - 1, ranges);
                boolean sharedStart = manual == null && bounds != null && preceding != null && bounds.first() == preceding.last();
                boolean opening = !sharedStart;
                var engine = OpticalScoreRecognition.locate(AppPreferences.scoreRecognitionExecutable());
                var notation = OpticalScoreRecognition.analyze(engine, edition.asset().file(), first, last, edition.pages(),
                    message -> progress(track, message));
                var variant = notation.written();
                String prefix = manual == null ? "" : "range/";
                if (notation.repeated() != null && (prefix + ScoreSync.opticalReferenceId(notation, notation.repeated(), opening)).equals(referenceId))
                    variant = notation.repeated();
                if ((prefix + ScoreSync.opticalReferenceId(notation, variant, opening)).equals(referenceId))
                    return new Prepared(recording, variant.reference(), EngravedScoreMap.recognized(variant.marks()));
                if (!synchronize && !opening && bounds == null)
                    return new Prepared(new ManagedScoreStore.Recording(recording.marks(), null), null, null);
                progress(track, "Matching recognized notes to this recording…");
                long size = Files.size(track.path());
                var revision = Files.getLastModifiedTime(track.path());
                boolean search = movement == null || movement.number() == 0 || manual != null;
                var result = ScoreSync.alignOptical(track.path(), notation, search, opening);
                if (Files.size(track.path()) != size || !Files.getLastModifiedTime(track.path()).equals(revision))
                    throw new java.io.IOException("The recording changed during alignment. Try Sync recording again.");
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                var match = result.alignment();
                if (manual != null) match = new ScoreSync.Alignment(match.offset(), match.step(), match.scoreSeconds(),
                    match.evidence(), prefix + match.referenceId());
                recording = new ManagedScoreStore.Recording(recording.marks(), match);
                managed.saveRecording(track, edition.asset(), recording);
                return new Prepared(recording, result.notation().reference(), EngravedScoreMap.recognized(result.notation().marks()));
            }
            if (recording.alignment() == null || movement == null || movement.midi().isBlank())
                return new Prepared(recording, null, null);
            var reference = ScoreSync.reference(edition.asset().file().resolveSibling(movement.midi()));
            EngravedScoreMap mapping = null;
            if (recording.alignment() != null && sangitam.desktop.library.WorkDetails.fold(composition.instrument()).contains("piano")) {
                try { mapping = EngravedScoreMap.read(edition.asset().file(), movement.firstPage(), movement.lastPage(), reference); }
                catch (java.io.InterruptedIOException cancelled) { throw cancelled; }
                catch (java.io.IOException unsupported) { /* The aligned reference remains usable. */ }
            }
            return new Prepared(recording, reference, mapping);
        }, prepared -> {
            managedRecording = prepared.recording();
            layout = prepared.layout();
            playhead.setAlignment(prepared.reference(), managedRecording.alignment());
            if (layout != null && page == 1 && playback.positionSeconds() < 1 && follow.isSelected()) {
                var start = layout.positionAt(managedRecording.alignment().scoreAt(0));
                if (start != null && start.page() != 1) showPage(start.page());
            }
            updateCursor();
            if (managedRecording.alignment() != null) status.setText(layout != null && layout.anchors() > 0
                ? "Recording aligned · cursor follows recognized notation" : "Recording aligned");
        });
    }

    private static ScoreMovementPages.Range savedRange(String reference, int pages) {
        if (reference == null) return null;
        var matcher = SAVED_RANGE.matcher(reference);
        if (!matcher.find()) return null;
        try {
            int first = Integer.parseInt(matcher.group(1)), last = Integer.parseInt(matcher.group(2));
            return first >= 1 && last >= first && last <= pages ? new ScoreMovementPages.Range(first, last) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private void progress(Track source, String message) {
        if (!Thread.currentThread().isInterrupted())
            SwingUtilities.invokeLater(() -> { if (!closed && busy && track == source) status.setText(message); });
    }

    private void updateCursor() {
        if (closed) return;
        var edition = selected();
        double time = playhead.scoreTime();
        cursor = layout == null || !Double.isFinite(time) ? null : layout.positionAt(time);
        boolean following = cursor != null && edition != null && !busy && !editingTime && follow.isSelected()
            && edition.timings().isEmpty() && System.nanoTime() - userPannedAt > 2_000_000_000L;
        image.cursor(cursor, following);
        if (following && page != cursor.page()) visiblePage(cursor.page());
    }

    private void download() {
        Track track = this.track;
        ScoreWork composition = this.composition;
        var available = new ArrayList<Edition>();
        for (int i = 0; i < editions.getItemCount(); i++) {
            var edition = editions.getItemAt(i);
            if (edition.asset() != null && !edition.asset().sources().isEmpty() && !edition.asset().name().startsWith("Historical scan · "))
                available.add(edition);
        }
        work(() -> {
            if (composition.key().isBlank()) throw new java.io.IOException("Add the composer and work in Edit details before looking for a score.");
            URI engraved = NoctaveScores.source(composition);
            if (engraved != null) {
                for (var edition : available) if (edition.asset().sources().contains(engraved.toString())
                    && Files.isRegularFile(edition.asset().file())) return edition.asset();
                progress(track, "Downloading a complete engraved score from Noctave…");
                NoctaveScores.Download result = null;
                try {
                    result = NoctaveScores.download(composition);
                } catch (java.io.IOException unavailable) {
                    if (Thread.currentThread().isInterrupted()) throw unavailable;
                }
                if (result != null) return managed.save(track, composition.title() + " · Noctave", List.of(
                    new ManagedScoreStore.Part(0, result.pdf(), null, result.source().toString())));
            }
            for (var edition : available) if (Files.isRegularFile(edition.asset().file())) return edition.asset();
            progress(track, "Looking for an engraved score on Mutopia…");
            try {
                var items = provider.search(composition);
                if (!items.isEmpty()) {
                    var parts = new ArrayList<ManagedScoreStore.Part>();
                    for (var item : items) {
                        var part = provider.download(item);
                        parts.add(new ManagedScoreStore.Part(part.movement(), part.pdf(), part.midi(), part.source().toString()));
                    }
                    String name = items.size() == 1 ? items.getFirst().title() + " · Mutopia" : composition.title() + " · Mutopia";
                    return managed.save(track, name, parts);
                }
            } catch (java.io.IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
            }
            progress(track, "Checking complete editions on IMSLP…");
            boolean editionsFound = false;
            try {
                var candidates = imslp.search(composition);
                editionsFound = !candidates.isEmpty();
                for (var candidate : candidates.stream().limit(3).toList()) {
                    var result = imslp.download(candidate);
                    if (result != null) return saveImslp(result, track, composition);
                }
            } catch (java.io.IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
            }
            progress(track, "Looking for a public-domain score scan…");
            try {
                var result = new CommonsScores().download(composition);
                return managed.save(track, composition.title() + " · Wikimedia Commons", List.of(
                    new ManagedScoreStore.Part(0, result.pdf(), null, result.source().toString())));
            } catch (java.io.IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                if (editionsFound) throw new java.io.IOException("IMSLP listed editions that need its browser download. Choose Sources → Find editions on IMSLP.");
                throw new java.io.IOException("No matching downloadable score found. Try Sources or attach a PDF.");
            }
        }, asset -> {
            downloadOnOpen = false;
            loadDownloaded(asset);
        });
    }

    private ManagedScoreStore.Asset saveImslp(ImslpScores.Download result, Track track, ScoreWork composition) throws Exception {
        String title = composition.title() + " · IMSLP";
        if (!result.edition().details().isBlank()) title += " · " + result.edition().details();
        return managed.save(track, title, List.of(new ManagedScoreStore.Part(0, result.pdf(), null, result.edition().link().toString())));
    }

    private void loadDownloaded(ManagedScoreStore.Asset asset) {
        Track track = this.track;
        work(() -> managed.recording(track, asset), recording -> {
            Edition imported = new Edition(asset.id(), asset.name(), asset.pages(), recording.marks(), asset);
            for (int i = 0; i < editions.getItemCount(); i++) {
                if (editions.getItemAt(i).id().equals(asset.id())) { imported = editions.getItemAt(i); break; }
            }
            updatingEditions = true;
            boolean listed = false;
            for (int i = 0; i < editions.getItemCount(); i++) listed |= editions.getItemAt(i).id().equals(asset.id());
            if (!listed) editions.insertItemAt(imported, 0);
            editions.setSelectedItem(imported);
            updatingEditions = false;
            changeEdition(true);
        });
    }

    private void sources() {
        Track track = this.track;
        ScoreWork composition = this.composition;
        var menu = new JPopupMenu();
        var edition = selected();
        if (edition != null && edition.asset() != null) {
            var folder = new JMenuItem("Open managed score folder");
            folder.addActionListener(event -> work(() -> {
                Desktop.getDesktop().open(edition.asset().file().getParent().toFile());
                return null;
            }, ignored -> { }));
            menu.add(folder);
            int number = 0;
            for (String source : edition.asset().sources()) {
                String providerName = source.startsWith("https://commons.wikimedia.org/") ? "Wikimedia Commons scan"
                    : source.startsWith("https://imslp.org/") ? "IMSLP edition"
                    : source.startsWith("https://noctavepiano.com/") ? "Noctave edition" : "Mutopia edition";
                var item = new JMenuItem(providerName + (edition.asset().sources().size() > 1 ? " · Movement " + (++number) : ""));
                item.addActionListener(event -> openSource(URI.create(source)));
                menu.add(item);
            }
        }
        var scan = new JMenuItem("Download historical scan…");
        scan.addActionListener(event -> work(() -> {
            var result = new CommonsScores().download(composition);
            return managed.save(track, "Historical scan · " + result.title(), List.of(
                new ManagedScoreStore.Part(0, result.pdf(), null, result.source().toString())));
        }, asset -> loadEditions(asset.id())));
        menu.add(scan);
        var original = new JMenuItem("Find editions on IMSLP…");
        original.addActionListener(event -> findImslp());
        menu.add(original);
        var engine = new JMenuItem("Set up Audiveris…");
        engine.addActionListener(event -> configureRecognition());
        menu.add(engine);
        menu.show(sources, 0, sources.getHeight());
    }

    private void findImslp() {
        Track track = this.track;
        ScoreWork composition = this.composition;
        work(() -> imslp.search(composition), choices -> {
            if (choices.isEmpty()) {
                openSource(URI.create("https://imslp.org/index.php?title=Special:Search&search="
                    + URLEncoder.encode(composition.composer() + " " + composition.title(), StandardCharsets.UTF_8)));
                return;
            }
            var list = new JList<>(choices.toArray(ImslpScores.Edition[]::new));
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setSelectedIndex(0);
            var pane = new PixelScrollPane(list);
            pane.setPreferredSize(new Dimension(610, 220));
            Object[] options = {"Download selected edition", "Open browser and import", "Cancel"};
            int answer = JOptionPane.showOptionDialog(this, pane, "IMSLP editions", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            var edition = list.getSelectedValue();
            if (edition == null || this.track != track) return;
            if (answer == 0) work(() -> {
                var result = imslp.download(edition);
                return result == null ? null : saveImslp(result, track, composition);
            }, asset -> {
                if (asset != null) loadDownloaded(asset);
                else if (JOptionPane.showConfirmDialog(this, "IMSLP needs your browser. Sangitam can import this edition after you download it to Downloads.",
                    "IMSLP download", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) browserDownload(edition);
            });
            else if (answer == 1) browserDownload(edition);
        });
    }

    private void browserDownload(ImslpScores.Edition edition) {
        Track track = this.track;
        ScoreWork composition = this.composition;
        if (browserImport != null) browserImport.cancel(true);
        status.setText("Download the selected IMSLP edition to Downloads…");
        browserImport = new SwingWorker<>() {
            @Override protected ManagedScoreStore.Asset doInBackground() throws Exception {
                Path downloads = WindowsFilePicker.downloads();
                String filename = edition.file().replaceFirst("^File:", "");
                String expected = downloadName(filename);
                long opened = System.currentTimeMillis();
                Desktop.getDesktop().browse(edition.link());
                if (!Files.isDirectory(downloads)) return null;
                try (var watcher = downloads.getFileSystem().newWatchService()) {
                    downloads.register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE,
                        java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY);
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(4);
                    while (!isCancelled() && System.nanoTime() < deadline) {
                        Path match = null;
                        try (var paths = Files.list(downloads)) {
                            for (Path candidate : paths.toList()) {
                                if (Files.isRegularFile(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                    && expected.equals(downloadName(candidate.getFileName().toString()))
                                    && Files.getLastModifiedTime(candidate).toMillis() >= opened) { match = candidate; break; }
                            }
                        }
                        if (match != null) {
                            try {
                                byte[] bytes = ScorePdf.bytes(match);
                                ScorePdf.validate(bytes);
                                return saveImslp(new ImslpScores.Download(edition, bytes), track, composition);
                            } catch (java.io.IOException unfinished) { /* Wait for the browser to finish writing. */ }
                        }
                        var key = watcher.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                        if (key != null) { key.pollEvents(); key.reset(); }
                    }
                }
                return null;
            }

            @Override protected void done() {
                if (closed || browserImport != this) return;
                browserImport = null;
                try {
                    var asset = get();
                    if (asset != null && !busy) loadDownloaded(asset);
                    else status.setText(asset != null ? "IMSLP score saved · reopen Sheet music to view it"
                        : "Download was not detected. Use Attach PDF to import it from another folder.");
                } catch (Exception error) { showError(error); }
            }
        };
        browserImport.execute();
    }

    private static String downloadName(String text) {
        String filename = text.replaceFirst("(?i)\\s*\\(\\d+\\)(?=\\.pdf$)", "");
        return sangitam.desktop.library.WorkDetails.fold(filename).replace(" ", "");
    }

    private void configureRecognition() {
        Object[] options = {"Choose executable…", "Open Audiveris downloads", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, "Audiveris recognizes the notation on your computer.",
            "Score recognition", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
        if (choice == 1) {
            openSource(URI.create("https://github.com/Audiveris/audiveris/releases/latest"));
        } else if (choice == 0) {
            var files = FileBrowserDialog.chooseFiles(this, "Choose the Audiveris executable", "audiveris",
                "Audiveris executable", false, "exe");
            if (files.isEmpty()) return;
            Path file = files.getFirst();
            String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!filename.equals("audiveris.exe") && !filename.equals("audiveris")) {
                status.setText("Select the Audiveris executable from its installation folder.");
                return;
            }
            AppPreferences.setScoreRecognitionExecutable(file);
            status.setText("Audiveris configured. Choose Sync recording to analyze the score.");
        }
    }

    private void openSource(URI source) {
        work(() -> { Desktop.getDesktop().browse(source); return null; }, ignored -> { });
    }

    private void attach(boolean shareWork) {
        Track track = this.track;
        boolean share = shareWork && !composition.key().isBlank();
        var files = FileBrowserDialog.chooseFiles(this, "Attach score PDFs", "score", "PDF sheet music", true, "pdf");
        if (files.isEmpty() || this.track != track) return;
        work(() -> {
            ManagedScoreStore.Asset first = null;
            for (Path path : files) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                var pdf = ScorePdf.read(path);
                var saved = managed.save(track, path.getFileName().toString(), List.of(new ManagedScoreStore.Part(0, pdf.pdf(), null, "")), share);
                if (first == null) first = saved;
            }
            return first;
        }, edition -> loadEditions(edition.id()));
    }

    private void remove() {
        var edition = selected();
        String scope = edition != null && edition.asset() != null ? " from the work and its recordings" : " and its page times";
        if (edition == null || JOptionPane.showConfirmDialog(this, "Remove “" + edition.name() + "”" + scope + "?",
            "Remove score", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        work(() -> {
            if (edition.asset() == null) store.remove(edition.id()); else managed.remove(edition.id());
            return null;
        }, ignored -> loadEditions(null));
    }

    private void editTiming() {
        Track track = this.track;
        var edition = selected();
        if (edition == null) return;
        int markedPage = page;
        Double existing = edition.timings().get(markedPage);
        var field = new JTextField(existing != null ? formatTime(existing)
            : playback.seekable() ? formatTime(playback.positionSeconds()) : "0:00", 10);
        var prompt = new JPanel(new BorderLayout(0, 6));
        prompt.add(new JLabel("Playback time in seconds or m:ss. Clear to remove the mark."), BorderLayout.NORTH);
        prompt.add(field, BorderLayout.SOUTH);
        int result;
        editingTime = true;
        try {
            result = JOptionPane.showConfirmDialog(this, prompt, "Page " + markedPage + " time", JOptionPane.OK_CANCEL_OPTION);
        } finally {
            editingTime = false;
        }
        if (result != JOptionPane.OK_OPTION || this.track != track) return;
        try {
            Double time = field.getText().isBlank() ? null : parseTime(field.getText().strip());
            double duration = playback.durationSeconds() > 0 ? playback.durationSeconds() : track.durationSeconds();
            if (time != null && duration > 0 && time >= duration) throw new IllegalArgumentException("Enter a time before the end of the track.");
            work(() -> saveTiming(track, edition, markedPage, time), updated -> {
                updatingEditions = true;
                int index = editions.getSelectedIndex();
                editions.removeItemAt(index);
                editions.insertItemAt(updated, index);
                editions.setSelectedIndex(index);
                updatingEditions = false;
                showPage(markedPage);
                status.setText(time == null ? "Page time removed" : "Page time saved");
            });
        } catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
        }
    }

    private Edition saveTiming(Track track, Edition edition, int page, Double time) throws Exception {
        if (edition.asset() == null) return store.saveTiming(edition, page, time);
        var marks = new java.util.TreeMap<>(edition.timings());
        var previous = marks.lowerEntry(page);
        var next = marks.higherEntry(page);
        if (time == null) marks.remove(page);
        else if (previous != null && time <= previous.getValue() || next != null && time >= next.getValue())
            throw new IllegalArgumentException("Page times must increase in page order.");
        else marks.put(page, time);
        var recording = managed.recording(track, edition.asset());
        managed.saveRecording(track, edition.asset(), new ManagedScoreStore.Recording(marks, recording.alignment()));
        return new Edition(edition.id(), edition.name(), edition.pages(), marks, edition.asset());
    }

    private static double parseTime(String text) {
        if (!text.matches("(?:[0-9]+:[0-5][0-9]|[0-9]+)(?:\\.[0-9]+)?"))
            throw new IllegalArgumentException("Enter seconds or a time such as 2:15.5.");
        int colon = text.indexOf(':');
        double seconds = colon < 0 ? Double.parseDouble(text)
            : 60 * Double.parseDouble(text.substring(0, colon)) + Double.parseDouble(text.substring(colon + 1));
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Enter a valid playback time.");
        return seconds;
    }

    private static String formatTime(double seconds) {
        long millis = Math.round(seconds * 1000);
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60000, millis / 1000 % 60, millis % 1000);
    }

    private void visiblePage(int number) {
        if (page == number || selected() == null) return;
        page = number;
        updateControls();
    }

    private void showPage(int number) {
        var edition = selected();
        page = edition == null ? 1 : Math.clamp(number, 1, edition.pages());
        if (edition == null) {
            image.clear();
            displayedEdition = null;
        } else if (!edition.id().equals(displayedEdition)) {
            displayedEdition = edition.id();
            image.open(edition.asset() == null ? null : edition.asset().file(), () -> store.pdf(edition.id()), page);
        } else image.go(page);
        updateControls();
    }

    private <T> void work(Callable<T> action, Consumer<T> complete) {
        busy = true;
        status.setText("Working…");
        updateControls();
        var worker = new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return action.call(); }
            @Override protected void done() {
                if (closed || operation != this) return;
                operation = null;
                busy = false;
                try {
                    status.setText(" ");
                    complete.accept(get());
                } catch (java.util.concurrent.CancellationException stopped) {
                    status.setText("Cancelled");
                } catch (Exception error) {
                    showError(error);
                }
                updateControls();
            }
        };
        operation = worker;
        worker.execute();
    }

    private void showError(Exception error) {
        Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
        status.setText(cause.getMessage() == null ? "The score operation failed." : cause.getMessage());
    }

    private void updateControls() {
        var edition = selected();
        boolean usable = edition != null && !busy;
        boolean hasScore = edition != null;
        cancel.setVisible(busy);
        attach.setEnabled(!busy);
        download.setEnabled(!busy);
        sources.setEnabled(!busy);
        editions.setEnabled(usable);
        remove.setEnabled(usable);
        previous.setEnabled(hasScore && page > 1);
        next.setEnabled(hasScore && page < edition.pages());
        pageNumber.setEnabled(hasScore);
        pageNumber.setText(edition == null ? "Page" : "Page " + page + " / " + edition.pages());
        zoom.setEnabled(hasScore);
        follow.setEnabled(hasScore);
        mark.setEnabled(usable);
        align.setEnabled(usable && edition.asset() != null);
        Double seconds = edition == null ? null : edition.timings().get(page);
        jump.setEnabled(usable && seconds != null && playback.seekable() && seconds < playback.durationSeconds());
        timing.setText(edition == null ? "" : seconds != null ? formatTime(seconds)
            : managedRecording != null && managedRecording.alignment() != null ? "Recording synced" : "Page time unset");
    }

    @Override public void dispose() {
        closed = true;
        if (fullScreenDevice != null) fullScreenDevice.setFullScreenWindow(null);
        playhead.stop();
        if (operation != null) operation.cancel(true);
        if (browserImport != null) browserImport.cancel(true);
        image.clear();
        super.dispose();
    }
}
