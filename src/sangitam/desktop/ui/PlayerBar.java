package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.playback.PlaybackState;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import javax.swing.plaf.metal.MetalSliderUI;

/** Swing transport view. It renders audio state but delegates every control to MainFrame. */
public final class PlayerBar extends JPanel {
    private static final long serialVersionUID = 1L;

    public interface Actions {
        void playPause();
        void previous();
        void next();
        void seek(double fraction);
        void volume(double fraction);
        default void stop() { }
        default void toggleShuffle() { }
        default void toggleRepeat() { }
    }

    private final JLabel title = UiFactory.label("Nothing playing", Theme.BODY_BOLD, Theme.TEXT);
    private final JLabel artist = UiFactory.label("Import music to begin", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel time = UiFactory.label("0:00 / 0:00", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton playPause;
    private final JSlider progress = new JSlider(0, 1_000_000, 0);
    private final JSlider volumeSlider = new JSlider(0, 100, 72);
    private final JButton shuffle;
    private final JButton repeat;
    private boolean updatingProgress;
    private PlaybackState displayed = PlaybackState.idle();

    public PlayerBar(Actions actions) {
        super(new BorderLayout(20, 0));
        setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER), Theme.padding(12, 22, 12, 22)));
        setPreferredSize(new Dimension(0, 88));

        JPanel track = new JPanel();
        track.setOpaque(false);
        track.setLayout(new BoxLayout(track, BoxLayout.Y_AXIS));
        track.add(title);
        track.add(Box.createVerticalStrut(4));
        track.add(artist);
        track.setPreferredSize(new Dimension(190, 50));
        add(track, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout(0, 5));
        center.setOpaque(false);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        controls.setOpaque(false);
        shuffle = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.SHUFFLE, 15, Theme.TEXT_MUTED), "Shuffle off");
        repeat = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.REPEAT, 15, Theme.TEXT_MUTED), "Repeat off");
        JButton stop = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.STOP, 14, Theme.TEXT_MUTED), "Stop");
        JButton previous = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PREVIOUS, 16), "Previous track");
        playPause = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PLAY, 18, Theme.BLUE), "Play");
        JButton next = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.NEXT, 16), "Next track");
        previous.addActionListener(event -> actions.previous());
        next.addActionListener(event -> actions.next());
        playPause.addActionListener(event -> actions.playPause());
        stop.addActionListener(event -> actions.stop());
        shuffle.addActionListener(event -> actions.toggleShuffle());
        repeat.addActionListener(event -> actions.toggleRepeat());
        controls.add(shuffle);
        controls.add(stop);
        controls.add(previous);
        controls.add(playPause);
        controls.add(next);
        controls.add(repeat);
        center.add(controls, BorderLayout.NORTH);

        progress.setOpaque(false);
        progress.setEnabled(false);
        progress.setToolTipText("Track position");
        progress.getAccessibleContext().setAccessibleName("Track position");
        progress.setUI(new MetalSliderUI() {
            @Override protected TrackListener createTrackListener(JSlider slider) {
                return new TrackListener() {
                    @Override public void mousePressed(MouseEvent event) {
                        if (!slider.isEnabled() || !SwingUtilities.isLeftMouseButton(event)) return;
                        calculateGeometry();
                        slider.requestFocusInWindow();
                        slider.setValueIsAdjusting(true);
                        slider.setValue(valueForXPosition(event.getX()));
                    }

                    @Override public void mouseDragged(MouseEvent event) {
                        if (slider.getValueIsAdjusting()) slider.setValue(valueForXPosition(event.getX()));
                    }

                    @Override public void mouseReleased(MouseEvent event) {
                        if (slider.getValueIsAdjusting() && SwingUtilities.isLeftMouseButton(event)) {
                            slider.setValue(valueForXPosition(event.getX()));
                            slider.setValueIsAdjusting(false);
                        }
                    }
                };
            }
        });
        progress.addChangeListener(event -> {
            if (updatingProgress || !progress.isEnabled()) return;
            double fraction = progress.getValue() / (double) progress.getMaximum();
            showTime(fraction * displayed.durationSeconds(), displayed.durationSeconds());
            if (!progress.getValueIsAdjusting()) actions.seek(fraction);
        });
        center.add(progress, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel volume = new JPanel(new BorderLayout(8, 0));
        volume.setOpaque(false);
        volume.add(UiFactory.label("Volume", Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.WEST);
        volumeSlider.setOpaque(false);
        volumeSlider.setPreferredSize(new Dimension(105, 30));
        volumeSlider.addChangeListener(event -> actions.volume(volumeSlider.getValue() / 100.0));
        volume.add(volumeSlider, BorderLayout.CENTER);
        volume.add(time, BorderLayout.SOUTH);
        add(volume, BorderLayout.EAST);
    }

    public void setShuffle(boolean enabled) {
        shuffle.setIcon(AppIcons.of(AppIcons.Kind.SHUFFLE, 15, enabled ? Theme.BLUE : Theme.TEXT_MUTED));
        shuffle.setToolTipText(enabled ? "Shuffle on" : "Shuffle off");
        shuffle.getAccessibleContext().setAccessibleName(shuffle.getToolTipText());
    }

    public void setRepeat(boolean enabled) {
        repeat.setIcon(AppIcons.of(AppIcons.Kind.REPEAT, 15, enabled ? Theme.BLUE : Theme.TEXT_MUTED));
        repeat.setToolTipText(enabled ? "Repeat current track on" : "Repeat current track off");
        repeat.getAccessibleContext().setAccessibleName(repeat.getToolTipText());
    }

    public void setVolumeFraction(double fraction) {
        int value = (int) Math.round(Math.max(0, Math.min(1, fraction)) * 100);
        if (volumeSlider.getValue() != value) volumeSlider.setValue(value);
    }

    public void setState(PlaybackState state) {
        Track current = state.track();
        title.setText(current == null ? "Nothing playing" : current.title());
        if (state.status() == PlaybackState.Status.ERROR) artist.setText(state.error());
        else if (state.status() == PlaybackState.Status.LOADING) artist.setText("Loading audio…");
        else artist.setText(current == null ? "Import music to begin" : current.artist());
        boolean playing = state.status() == PlaybackState.Status.PLAYING;
        boolean loading = state.status() == PlaybackState.Status.LOADING;
        playPause.setIcon(AppIcons.of(playing ? AppIcons.Kind.PAUSE : AppIcons.Kind.PLAY, 18, Theme.BLUE));
        playPause.setEnabled(!loading);
        String action = loading ? "Loading audio" : playing ? "Pause" : "Play";
        playPause.setToolTipText(action);
        playPause.getAccessibleContext().setAccessibleName(action);
        boolean seekable = current != null && state.seekable() && state.durationSeconds() > 0;
        updatingProgress = true;
        if (displayed.track() != current || !seekable) progress.setValueIsAdjusting(false);
        displayed = state;
        progress.setEnabled(seekable);
        if (!progress.getValueIsAdjusting()) {
            int value = state.durationSeconds() <= 0 ? 0
                : (int) Math.round(progress.getMaximum() * state.positionSeconds() / state.durationSeconds());
            progress.setValue(value);
            showTime(state.positionSeconds(), state.durationSeconds());
        }
        updatingProgress = false;
    }

    /** Shows a remembered track before its decoder is created. */
    public void showRemembered(Track track, double positionSeconds) {
        setState(new PlaybackState(track, PlaybackState.Status.PAUSED, positionSeconds,
            track.durationSeconds(), null));
    }

    private void showTime(double position, double duration) {
        time.setText(clock(position) + " / " + clock(duration));
    }

    private String clock(double seconds) {
        long whole = Math.max(0, Math.round(seconds));
        return String.format("%d:%02d", whole / 60, whole % 60);
    }
}
