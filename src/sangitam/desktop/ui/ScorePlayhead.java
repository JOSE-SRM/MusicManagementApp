package sangitam.desktop.ui;

import sangitam.desktop.library.ScoreSync;
import sangitam.desktop.playback.PlaybackState;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.DoubleConsumer;

/** Keeps the PDF cursor clock and optionally displays the aligned notes. */
final class ScorePlayhead extends JComponent {
    private static final double VIEW_SECONDS = 8;
    private final Timer animation;
    private ScoreSync.Reference reference;
    private ScoreSync.Alignment alignment;
    private PlaybackState playback = PlaybackState.idle();
    private long observed;
    private boolean showNotes;

    ScorePlayhead(DoubleConsumer seek, Runnable updateScore) {
        animation = new Timer(32, event -> { repaint(); updateScore.run(); });
        setPreferredSize(new Dimension(500, 82));
        setVisible(false);
        getAccessibleContext().setAccessibleName("Aligned score notes");
        setToolTipText("Aligned score notes. Click a passage to seek the recording.");
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (alignment != null && playback.seekable())
                    seek.accept(alignment.audioAt(start() + VIEW_SECONDS * event.getX() / Math.max(1, getWidth())));
            }
        });
    }

    @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleJComponent() { };
        return accessibleContext;
    }

    void setAlignment(ScoreSync.Reference reference, ScoreSync.Alignment alignment) {
        this.reference = reference;
        this.alignment = alignment;
        setVisible(showNotes && reference != null && alignment != null);
        refresh();
    }

    void showNotes(boolean show) {
        showNotes = show;
        setVisible(show && reference != null && alignment != null);
        refresh();
    }

    void update(PlaybackState value) {
        playback = value;
        observed = System.nanoTime();
        refresh();
    }

    private void refresh() {
        boolean playing = alignment != null && playback.status() == PlaybackState.Status.PLAYING;
        if (playing && !animation.isRunning()) animation.start();
        else if (!playing) animation.stop();
        if (isVisible()) repaint();
    }

    void stop() { animation.stop(); }

    private double position() {
        double estimated = playback.positionSeconds();
        if (playback.status() == PlaybackState.Status.PLAYING)
            estimated += Math.min(.30, (System.nanoTime() - observed) / 1_000_000_000.0);
        return alignment.scoreAt(estimated);
    }

    double scoreTime() { return alignment == null || !playback.seekable() ? Double.NaN : position(); }

    double audioTime(double time) { return alignment == null ? Double.NaN : alignment.audioAt(time); }

    private double start() {
        return Math.clamp(position() - 2, 0, Math.max(0, reference.duration() - VIEW_SECONDS));
    }

    @Override protected void paintComponent(Graphics g) {
        if (reference == null || alignment == null) return;
        int width = getWidth(), height = getHeight();
        g.setColor(Theme.SURFACE_RAISED);
        g.fillRect(0, 0, width, height);
        double time = position();
        double start = Math.clamp(time - 2, 0, Math.max(0, reference.duration() - VIEW_SECONDS));
        double end = start + VIEW_SECONDS;
        for (int pitch = 24; pitch <= 108; pitch += 12) {
            int y = pitchY(pitch, height);
            g.setColor(Theme.DIVIDER);
            g.drawLine(0, y, width, y);
        }
        for (var note : reference.notes()) {
            if (note.start() > end) break;
            if (note.end() < start) continue;
            int x = x(Math.max(start, note.start()), start, width);
            int until = x(Math.min(end, note.end()), start, width);
            g.setColor(note.start() <= time && time < note.end() ? Theme.BLUE : Theme.SCROLL_THUMB);
            g.fillRoundRect(x, pitchY(note.pitch(), height), Math.max(2, until - x), 3, 2, 2);
        }
        if (playback.seekable()) {
            int cursor = x(time, start, width);
            g.setColor(new Color(255, 199, 112));
            g.fillRect(cursor, 0, 2, height);
        }
    }

    private static int pitchY(int pitch, int height) { return Math.clamp((108 - pitch) * (height - 4) / 84, 0, height - 4); }
    private static int x(double time, double start, int width) { return (int) Math.round((time - start) * width / VIEW_SECONDS); }
}
