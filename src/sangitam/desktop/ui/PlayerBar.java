package sangitam.desktop.ui;

import sangitam.desktop.model.Track;
import sangitam.desktop.playback.PlaybackState;

import javax.swing.*;
import java.awt.*;

/** Swing transport view. It renders audio state but delegates every control to MainFrame. */
public final class PlayerBar extends JPanel {
    private static final long serialVersionUID = 1L;

    public interface Actions {
        void playPause();
        void previous();
        void next();
        void seek(double fraction);
        void volume(double fraction);
    }

    private final JLabel title = UiFactory.label("Nothing playing", Theme.BODY_BOLD, Theme.TEXT);
    private final JLabel artist = UiFactory.label("Import music to begin", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel time = UiFactory.label("0:00 / 0:00", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton playPause;
    private final JSlider progress = new JSlider(0, 1000, 0);
    private boolean updatingProgress;

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
        JButton previous = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PREVIOUS, 16), "Previous track");
        playPause = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PLAY, 18, Theme.BLUE), "Play");
        JButton next = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.NEXT, 16), "Next track");
        previous.addActionListener(event -> actions.previous());
        next.addActionListener(event -> actions.next());
        playPause.addActionListener(event -> actions.playPause());
        controls.add(previous);
        controls.add(playPause);
        controls.add(next);
        center.add(controls, BorderLayout.NORTH);

        progress.setOpaque(false);
        progress.setEnabled(false);
        progress.setToolTipText("Track position");
        progress.addChangeListener(event -> {
            if (!updatingProgress && !progress.getValueIsAdjusting()) {
                actions.seek(progress.getValue() / 1000.0);
            }
        });
        center.add(progress, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel volume = new JPanel(new BorderLayout(8, 0));
        volume.setOpaque(false);
        volume.add(UiFactory.label("Volume", Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.WEST);
        JSlider volumeSlider = new JSlider(0, 100, 72);
        volumeSlider.setOpaque(false);
        volumeSlider.setPreferredSize(new Dimension(105, 30));
        volumeSlider.addChangeListener(event -> actions.volume(volumeSlider.getValue() / 100.0));
        volume.add(volumeSlider, BorderLayout.CENTER);
        volume.add(time, BorderLayout.SOUTH);
        add(volume, BorderLayout.EAST);
    }

    public void setState(PlaybackState state) {
        Track current = state.track();
        title.setText(current == null ? "Nothing playing" : current.title());
        if (state.status() == PlaybackState.Status.ERROR) artist.setText(state.error());
        else if (state.status() == PlaybackState.Status.LOADING) artist.setText("Loading audio…");
        else artist.setText(current == null ? "Import music to begin" : current.artist());
        boolean playing = state.status() == PlaybackState.Status.PLAYING;
        playPause.setIcon(AppIcons.of(playing ? AppIcons.Kind.PAUSE : AppIcons.Kind.PLAY, 18, Theme.BLUE));
        playPause.setToolTipText(playing ? "Pause" : "Play");
        playPause.getAccessibleContext().setAccessibleName(playing ? "Pause" : "Play");
        progress.setEnabled(current != null && state.durationSeconds() > 0
            && state.status() != PlaybackState.Status.LOADING);
        if (!progress.getValueIsAdjusting()) {
            updatingProgress = true;
            int value = state.durationSeconds() <= 0 ? 0
                : (int) Math.round(1000 * state.positionSeconds() / state.durationSeconds());
            progress.setValue(Math.max(0, Math.min(1000, value)));
            updatingProgress = false;
        }
        time.setText(clock(state.positionSeconds()) + " / " + clock(state.durationSeconds()));
    }

    private String clock(double seconds) {
        long whole = Math.max(0, Math.round(seconds));
        return String.format("%d:%02d", whole / 60, whole % 60);
    }
}
