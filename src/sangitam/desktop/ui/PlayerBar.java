package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/** Bottom transport bar. IMPORTANT: Playback controls are visual placeholders until an audio engine is connected. */
public final class PlayerBar extends JPanel {
    private static final long serialVersionUID = 1L;
    private boolean playing;
    private final JButton playPause;

    public PlayerBar(Consumer<String> notify) {
        super(new BorderLayout(20, 0));
        setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER), Theme.padding(12, 22, 12, 22)));
        setPreferredSize(new Dimension(0, 88));

        JPanel track = new JPanel();
        track.setOpaque(false);
        track.setLayout(new BoxLayout(track, BoxLayout.Y_AXIS));
        track.add(UiFactory.label("Midnight Drive", Theme.BODY_BOLD, Theme.TEXT));
        track.add(Box.createVerticalStrut(4));
        track.add(UiFactory.label("Aster Avenue", Theme.SMALL, Theme.TEXT_MUTED));
        track.setPreferredSize(new Dimension(190, 50));
        add(track, BorderLayout.WEST);

        JPanel center = new JPanel(new BorderLayout(0, 5));
        center.setOpaque(false);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        controls.setOpaque(false);
        JButton previous = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PREVIOUS, 16), "Previous");
        playPause = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PLAY, 18, Theme.BLUE), "Play");
        JButton next = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.NEXT, 16), "Next");
        previous.addActionListener(event -> notify.accept("Previous track pressed"));
        next.addActionListener(event -> notify.accept("Next track pressed"));
        playPause.addActionListener(event -> togglePlayback(notify));
        controls.add(previous); controls.add(playPause); controls.add(next);
        center.add(controls, BorderLayout.NORTH);

        JSlider progress = new JSlider(0, 100, 34);
        progress.setOpaque(false);
        progress.setToolTipText("Track position");
        progress.addChangeListener(event -> {
            if (progress.getValueIsAdjusting()) notify.accept("Seek position: " + progress.getValue() + "%");
        });
        center.add(progress, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);

        JPanel volume = new JPanel(new BorderLayout(8, 0));
        volume.setOpaque(false);
        volume.add(UiFactory.label("Volume", Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.WEST);
        JSlider volumeSlider = new JSlider(0, 100, 72);
        volumeSlider.setOpaque(false);
        volumeSlider.setPreferredSize(new Dimension(105, 30));
        volume.add(volumeSlider, BorderLayout.CENTER);
        add(volume, BorderLayout.EAST);
    }

    private void togglePlayback(Consumer<String> notify) {
        playing = !playing;
        playPause.setIcon(AppIcons.of(playing ? AppIcons.Kind.PAUSE : AppIcons.Kind.PLAY, 18, Theme.BLUE));
        playPause.setToolTipText(playing ? "Pause" : "Play");
        notify.accept(playing ? "Play pressed" : "Pause pressed");
    }
}
