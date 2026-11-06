package sangitam.desktop.ui;

import sangitam.desktop.model.*;
import sangitam.desktop.model.LibraryCollections.*;
import sangitam.desktop.playback.*;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Transport presentation. Every action uses the same application commands as media controls. */
public final class PlayerBar extends JPanel {
    public interface Actions {
        void playPause(); void previous(); void next(); void seek(double fraction); void volume(double fraction);
        default void stop() { } default void toggleShuffle() { } default void toggleRepeat() { }
        default void audioSettings() { } default void openTrack(Track track) { } default void openArtist(String name) { }
        default void toggleLiked(Track track) { } default void toggleQueue() { }
        default void toggleSpectrum() { }
    }
    private final MarqueeButton title = new MarqueeButton();
    private final JPanel credits = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
    private final JLabel elapsed = UiFactory.label("0:00", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel duration = UiFactory.label("0:00", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel remaining = UiFactory.label("−0:00", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel picture = new JLabel(AppIcons.of(AppIcons.Kind.MUSIC, 40, Theme.BLUE));
    private final LibraryArtwork artwork = new LibraryArtwork(64);
    private Map<java.nio.file.Path, FileStamp> stamps = Map.of();
    private Set<java.nio.file.Path> unavailable = Set.of();
    private final JButton playPause, shuffle, repeat, liked, queue, audio;
    private final JSlider progress = new JSlider(0, 1_000_000, 0), volumeSlider = new JSlider(0, 150, 72);
    private final JLabel volumeValue = UiFactory.label("72%", Theme.SMALL, Theme.TEXT_MUTED);
    private final EqMini mini = new EqMini();
    private boolean updatingProgress;
    private PlaybackState displayed = PlaybackState.idle();
    private final Actions actions;

    public PlayerBar(Actions actions) {
        super(new BorderLayout(16, 0)); this.actions = actions;
        setOpaque(false); setBackground(Theme.SURFACE); setBorder(BorderFactory.createCompoundBorder(UiFactory.roundedBorder(Theme.DIVIDER, 24), Theme.padding(14, 16, 14, 16)));
        setPreferredSize(new Dimension(0, 112));
        JPanel track = new JPanel(new BorderLayout(10, 0)); track.setOpaque(false);
        picture.setPreferredSize(new Dimension(64, 64)); picture.setHorizontalAlignment(SwingConstants.CENTER);
        picture.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        picture.addMouseListener(new java.awt.event.MouseAdapter() { @Override public void mouseClicked(java.awt.event.MouseEvent event) { if (SwingUtilities.isLeftMouseButton(event) && displayed.track() != null) actions.openTrack(displayed.track()); } });
        track.add(picture, BorderLayout.WEST);
        JPanel labels = new JPanel(new BorderLayout(0, 4)); labels.setOpaque(false);
        title.setText("Nothing playing"); title.setEnabled(false);
        title.addActionListener(event -> { if (displayed.track() != null) actions.openTrack(displayed.track()); });
        labels.add(title, BorderLayout.NORTH);
        credits.setOpaque(false);
        JScrollPane names = new JScrollPane(credits, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        UiFactory.styleScroll(names); names.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 5));
        names.setBorder(null); names.setOpaque(false); names.getViewport().setOpaque(false); names.setPreferredSize(new Dimension(180, 38));
        labels.add(names, BorderLayout.CENTER); track.add(labels, BorderLayout.CENTER);
        liked = control(AppIcons.Kind.HEART, "Like song", false); liked.addActionListener(event -> { if (displayed.track() != null) actions.toggleLiked(displayed.track()); });
        track.add(liked, BorderLayout.EAST); track.setPreferredSize(new Dimension(390, 78)); add(track, BorderLayout.WEST);
        addComponentListener(new java.awt.event.ComponentAdapter() { @Override public void componentResized(java.awt.event.ComponentEvent event) {
            track.setPreferredSize(new Dimension(Math.max(230, Math.min(460, getWidth() / 3)), 78)); revalidate();
        } });
        JPanel center = new JPanel(new BorderLayout(0, 7)); center.setOpaque(false);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0)); controls.setOpaque(false);
        shuffle = control(AppIcons.Kind.SHUFFLE, "Shuffle off", false); repeat = control(AppIcons.Kind.REPEAT, "Repeat off", false);
        JButton stop = control(AppIcons.Kind.STOP, "Stop", false), previous = control(AppIcons.Kind.PREVIOUS, "Previous track", false), next = control(AppIcons.Kind.NEXT, "Next track", false);
        playPause = control(AppIcons.Kind.PLAY, "Play", true);
        shuffle.addActionListener(event -> actions.toggleShuffle()); repeat.addActionListener(event -> actions.toggleRepeat());
        stop.addActionListener(event -> actions.stop()); previous.addActionListener(event -> actions.previous()); next.addActionListener(event -> actions.next()); playPause.addActionListener(event -> actions.playPause());
        for (JButton button : List.of(shuffle, previous, playPause, next, repeat, stop)) controls.add(button);
        center.add(controls, BorderLayout.NORTH);
        progress.setEnabled(false); progress.setToolTipText("Track position"); progress.getAccessibleContext().setAccessibleName("Track position"); style(progress);
        progress.addChangeListener(event -> {
            if (updatingProgress || !progress.isEnabled()) return;
            double fraction = progress.getValue() / (double) progress.getMaximum(); showTime(fraction * displayed.durationSeconds(), displayed.durationSeconds());
            if (!progress.getValueIsAdjusting()) actions.seek(fraction);
        });
        JPanel timeline = new JPanel(new BorderLayout(8, 0)); timeline.setOpaque(false);
        timeline.add(elapsed, BorderLayout.WEST); timeline.add(progress, BorderLayout.CENTER);
        JPanel end = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); end.setOpaque(false); end.add(remaining); end.add(duration); timeline.add(end, BorderLayout.EAST);
        center.add(timeline, BorderLayout.CENTER); add(center, BorderLayout.CENTER);
        JPanel tools = new JPanel(new BorderLayout(6, 6)); tools.setOpaque(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)); top.setOpaque(false);
        queue = control(AppIcons.Kind.QUEUE, "Queue and recently played", false); queue.addActionListener(event -> actions.toggleQueue());
        audio = control(AppIcons.Kind.EQ, "Audio settings and equalizer", false); audio.addActionListener(event -> actions.audioSettings());
        JButton spectrum = control(AppIcons.Kind.SPECTRUM, "Show or hide spectrum", false); spectrum.addActionListener(event -> actions.toggleSpectrum());
        mini.setToolTipText("Saved equalizer curve · click EQ to edit"); top.add(queue); top.add(spectrum); top.add(audio); top.add(mini); tools.add(top, BorderLayout.NORTH);
        JPanel volume = new JPanel(new BorderLayout(6, 0)); volume.setOpaque(false);
        JButton mute = control(AppIcons.Kind.VOLUME, "Mute or restore volume", false);
        final int[] beforeMute = {72}; mute.addActionListener(event -> { if (volumeSlider.getValue() > 0) { beforeMute[0] = volumeSlider.getValue(); volumeSlider.setValue(0); } else volumeSlider.setValue(beforeMute[0]); });
        volume.add(mute, BorderLayout.WEST); style(volumeSlider); volumeSlider.setPreferredSize(new Dimension(100, 26));
        volumeSlider.getAccessibleContext().setAccessibleName("Volume"); volumeSlider.addChangeListener(event -> { updateVolumeLabel(); actions.volume(volumeSlider.getValue() / 100.0); });
        volume.add(volumeSlider, BorderLayout.CENTER); volumeValue.setPreferredSize(new Dimension(44, 24)); volume.add(volumeValue, BorderLayout.EAST);
        tools.add(volume, BorderLayout.SOUTH); tools.setPreferredSize(new Dimension(222, 78)); add(tools, BorderLayout.EAST); updateVolumeLabel();
    }
    private static void style(JSlider slider) { slider.setOpaque(false); slider.setForeground(Theme.BLUE); slider.setUI(new RoundSliderUI(slider)); }
    static JButton control(AppIcons.Kind kind, String name, boolean primary) {
        JButton button = new JButton(AppIcons.of(kind, primary ? 28 : 22, primary ? Theme.BLACK : Theme.TEXT));
        button.setToolTipText(name); button.getAccessibleContext().setAccessibleName(name);
        button.setPreferredSize(new Dimension(primary ? 46 : 32, primary ? 46 : 36));
        button.setBorderPainted(false); button.setContentAreaFilled(false); button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override public void paint(Graphics graphics, JComponent component) {
                Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(primary ? (button.getModel().isRollover() ? Color.WHITE : Theme.BLUE) : button.getModel().isRollover() ? Theme.SURFACE_HOVER : Theme.SURFACE);
                g.fillRoundRect(0, 0, component.getWidth(), component.getHeight(), primary ? 46 : 16, primary ? 46 : 16);
                if (button.hasFocus()) { g.setColor(Theme.TEXT); g.drawRoundRect(1, 1, component.getWidth() - 3, component.getHeight() - 3, 18, 18); } g.dispose(); super.paint(graphics, component);
            }
        });
        return button;
    }
    private void updateVolumeLabel() {
        String label = AudioGain.label(volumeSlider.getValue() / 100.0); volumeValue.setText(label);
        Color color = volumeSlider.getValue() == 0 ? Theme.TEXT_MUTED : volumeSlider.getValue() > 100 ? new Color(255, 174, 95) : volumeSlider.getValue() > 70 ? Theme.BLUE : Theme.SUCCESS;
        volumeSlider.setForeground(color); volumeValue.setForeground(color);
        volumeSlider.setToolTipText("Volume: " + label + " · 0–" + volumeSlider.getMaximum() + "%" + (volumeSlider.getMaximum() == 100 ? " (JavaFX fallback limit)" : " (VLC scale)"));
        volumeSlider.getAccessibleContext().setAccessibleDescription(volumeSlider.getToolTipText());
    }
    public void setShuffle(boolean enabled) { mode(shuffle, AppIcons.Kind.SHUFFLE, enabled, "Shuffle"); }
    public void setRepeat(boolean enabled) { mode(repeat, AppIcons.Kind.REPEAT, enabled, "Repeat current track"); }
    private void mode(JButton button, AppIcons.Kind icon, boolean enabled, String name) { button.setIcon(AppIcons.of(icon, 22, enabled ? Theme.BLUE : Theme.TEXT_MUTED)); button.setToolTipText(name + (enabled ? " on" : " off")); button.getAccessibleContext().setAccessibleName(button.getToolTipText()); }
    public void setLiked(boolean enabled) { mode(liked, AppIcons.Kind.HEART, enabled, enabled ? "Liked song" : "Like song"); }
    public void setQueueVisible(boolean enabled) { mode(queue, AppIcons.Kind.QUEUE, enabled, "Queue and recently played"); }
    public void setAudioSettings(AudioSettings settings) { mini.settings = settings; mini.repaint(); audio.setIcon(AppIcons.of(AppIcons.Kind.EQ, 22, settings.equalizerEnabled() ? Theme.BLUE : Theme.TEXT_MUTED)); }
    public void setCoverState(Map<java.nio.file.Path, FileStamp> stamps, Set<java.nio.file.Path> unavailable) { this.stamps = stamps; this.unavailable = unavailable; updateCover(); }
    public void setVolumeFraction(double fraction) { int value = Math.min(volumeSlider.getMaximum(), AudioGain.vlcVolume(fraction)); if (volumeSlider.getValue() != value) volumeSlider.setValue(value); }
    public void setMaximumVolume(double maximum) { int percent = AudioGain.vlcVolume(maximum); if (volumeSlider.getMaximum() != percent) volumeSlider.setMaximum(percent); updateVolumeLabel(); }
    public void setState(PlaybackState state) {
        Track current = state.track(); boolean changed = !java.util.Objects.equals(displayed.track(), current);
        title.setText(current == null ? "Nothing playing" : current.title()); title.setEnabled(current != null); liked.setEnabled(current != null);
        if (changed) {
            credits.removeAll();
            if (current != null) for (var credit : ArtistCredits.of(current)) {
                if (credits.getComponentCount() > 0) credits.add(UiFactory.label("·", Theme.SMALL, Theme.TEXT_MUTED));
                JButton link = new JButton(credit.name()); link.putClientProperty("html.disable", true); link.setFont(Theme.SMALL); link.setForeground(Theme.TEXT_MUTED);
                link.setBorder(Theme.padding(0, 1, 0, 5)); link.setContentAreaFilled(false); link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                link.setToolTipText(credit.role() + " · Open artist"); link.addActionListener(event -> actions.openArtist(credit.name())); credits.add(link);
                link.addFocusListener(new java.awt.event.FocusAdapter() { @Override public void focusGained(java.awt.event.FocusEvent event) { link.scrollRectToVisible(new Rectangle(0, 0, link.getWidth(), link.getHeight())); } });
            }
            if (current == null) credits.add(UiFactory.label("Import music to begin", Theme.SMALL, Theme.TEXT_MUTED));
            credits.revalidate(); credits.repaint();
        }
        boolean playing = state.status() == PlaybackState.Status.PLAYING, loading = state.status() == PlaybackState.Status.LOADING;
        playPause.setIcon(AppIcons.of(playing ? AppIcons.Kind.PAUSE : AppIcons.Kind.PLAY, 28, Theme.BLACK)); playPause.setEnabled(!loading);
        String action = loading ? "Loading audio" : playing ? "Pause" : "Play"; playPause.setToolTipText(action); playPause.getAccessibleContext().setAccessibleName(action);
        boolean seekable = current != null && state.seekable() && state.durationSeconds() > 0; updatingProgress = true;
        if (changed || !seekable) progress.setValueIsAdjusting(false); displayed = state; progress.setEnabled(seekable);
        if (!progress.getValueIsAdjusting()) { progress.setValue(state.durationSeconds() <= 0 ? 0 : (int) Math.round(progress.getMaximum() * state.positionSeconds() / state.durationSeconds())); showTime(state.positionSeconds(), state.durationSeconds()); }
        updatingProgress = false; if (changed) updateCover();
    }
    private void updateCover() {
        Track current = displayed.track(); Icon cover = null;
        if (current != null) cover = artwork.forEntry(new LibraryEntry(new Key(Kind.SONG, current.path().toString()), current.title(), current.artist(), List.of(current), 0, 0, 0), unavailable, stamps, this::updateCover);
        picture.setIcon(cover == null ? AppIcons.of(AppIcons.Kind.MUSIC, 40, Theme.BLUE) : cover);
    }
    public void showRemembered(Track track, double positionSeconds) { setState(new PlaybackState(track, PlaybackState.Status.PAUSED, positionSeconds, track.durationSeconds(), null)); }
    private void showTime(double position, double total) { elapsed.setText(clock(position)); duration.setText(clock(total)); remaining.setText("−" + clock(Math.max(0, total - position))); remaining.setToolTipText("Remaining time"); duration.setToolTipText("Total duration"); }
    static String clock(double seconds) { long whole = Math.max(0, (long) seconds); return String.format("%d:%02d", whole / 60, whole % 60); }
    public void close() { artwork.close(); }
    @Override protected void paintComponent(Graphics graphics) { Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g.setColor(Theme.SURFACE); g.fillRoundRect(0, 0, getWidth(), getHeight(), 24, 24); g.dispose(); super.paintComponent(graphics); }
    private static final class EqMini extends JComponent {
        private AudioSettings settings = AudioSettings.defaults();
        EqMini() { setPreferredSize(new Dimension(50, 34)); getAccessibleContext(); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(settings.equalizerEnabled() ? Theme.BLUE : Theme.TEXT_MUTED); g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < 9; i++) g.drawLine(3 + i * 5, 17 - (int) (settings.bands().get(i) * .9), 8 + i * 5, 17 - (int) (settings.bands().get(i + 1) * .9));
            g.dispose();
        }
    }
}
