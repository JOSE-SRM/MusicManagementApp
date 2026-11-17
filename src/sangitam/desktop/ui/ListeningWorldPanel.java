package sangitam.desktop.ui;

import sangitam.desktop.model.TrackTags;
import sangitam.desktop.playback.PlaybackState;
import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.jaudiotagger.audio.AudioFileIO;

/** Immersive Swing listening surface with honest, tag-driven visual motion and a permanent way back. */
public final class ListeningWorldPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    public interface Actions {
        void back();
        void previous();
        void playPause();
        void next();
        void seek(double fraction);
        void playAt(int index);
    }

    private final Actions actions;
    private final WorldStage stage = new WorldStage();
    private final JLabel roomLabel = UiFactory.label("Quiet Orbit", Theme.BODY_BOLD, Theme.BLUE);
    private final JLabel title = UiFactory.label("Choose a song to begin", Theme.font(Font.BOLD, 29), Theme.TEXT);
    private final JLabel artist = UiFactory.label("Your library is ready when you are", Theme.BODY, Theme.TEXT_MUTED);
    private final JLabel traits = UiFactory.label("A listening space shaped by your choices", Theme.SMALL, Theme.TEXT_MUTED);
    private final JCheckBox reduceMotion = new JCheckBox("Reduce motion");
    private final JLabel trackCount = UiFactory.label("Your listening space", Theme.SMALL, Theme.TEXT_MUTED);
    private final DefaultListModel<SessionPlanner.Choice> queueModel = new DefaultListModel<>();
    private final JList<SessionPlanner.Choice> queue = new JList<>(queueModel);
    private final JSlider seek = new JSlider(0, 1000, 0);
    private final JLabel elapsed = UiFactory.label("0:00", Theme.SMALL, Theme.TEXT_MUTED);
    private final JLabel remaining = UiFactory.label("0:00", Theme.SMALL, Theme.TEXT_MUTED);
    private final JButton playPause = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PLAY, 24, Theme.BLACK), "Play");
    private boolean updatingSeek;

    public ListeningWorldPanel(Actions actions) {
        super(new BorderLayout(0, 16));
        this.actions = actions;
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(22, 32, 24, 32));
        add(header(), BorderLayout.NORTH);
        add(mainScene(), BorderLayout.CENTER);
        add(transport(), BorderLayout.SOUTH);
        getAccessibleContext().setAccessibleDescription(
            "Immersive Now Playing view. The scene uses your selected room and saved audio traits.");
    }

    private JComponent header() {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JButton back = UiFactory.button("Back to your space", AppIcons.of(AppIcons.Kind.COLLAPSE, 15, Theme.BLUE), false);
        back.addActionListener(event -> actions.back());
        header.add(back, BorderLayout.WEST);
        header.add(UiFactory.label("NOW PLAYING", Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.EAST);
        return header;
    }

    private JComponent mainScene() {
        JPanel scene = new JPanel(new GridBagLayout());
        scene.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0; c.weighty = 1; c.fill = GridBagConstraints.BOTH;
        c.gridx = 0; c.weightx = 1.35; c.insets = new Insets(0, 0, 0, 24);
        scene.add(stage, c);

        JPanel details = new JPanel(new BorderLayout(0, 18));
        details.setOpaque(false);
        details.setPreferredSize(new Dimension(360, 0));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        roomLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        artist.setAlignmentX(Component.LEFT_ALIGNMENT);
        traits.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.add(roomLabel);
        copy.add(Box.createVerticalStrut(12));
        copy.add(title);
        copy.add(Box.createVerticalStrut(6));
        copy.add(artist);
        copy.add(Box.createVerticalStrut(20));
        copy.add(traits);
        reduceMotion.setOpaque(false);
        reduceMotion.setForeground(Theme.TEXT_MUTED);
        reduceMotion.setFont(Theme.SMALL);
        reduceMotion.setAlignmentX(Component.LEFT_ALIGNMENT);
        reduceMotion.addActionListener(event -> stage.setReducedMotion(reduceMotion.isSelected()));
        copy.add(Box.createVerticalStrut(14));
        copy.add(reduceMotion);
        details.add(copy, BorderLayout.NORTH);

        JPanel queuePanel = new JPanel(new BorderLayout(0, 9));
        queuePanel.setOpaque(false);
        queuePanel.add(trackCount, BorderLayout.NORTH);
        queue.setOpaque(false);
        queue.setFixedCellHeight(52);
        queue.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queue.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && queue.getSelectedIndex() >= 0)
                    actions.playAt(queue.getSelectedIndex());
            }
        });
        queue.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "playQueueChoice");
        queue.getActionMap().put("playQueueChoice", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (queue.getSelectedIndex() >= 0) actions.playAt(queue.getSelectedIndex());
            }
        });
        queue.setCellRenderer((list, value, index, selected, focus) -> {
            JLabel row = UiFactory.label("<html><b>" + escape(value.track().title()) + "</b><br><small>"
                + escape(value.reason()) + "</small></html>", Theme.SMALL,
                selected ? Theme.TEXT : Theme.TEXT_MUTED);
            row.setOpaque(true);
            row.setBackground(selected ? Theme.SURFACE_HOVER : Theme.BLACK);
            row.setBorder(Theme.padding(6, 12, 6, 12));
            return row;
        });
        JScrollPane scroll = new JScrollPane(queue);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));
        scroll.getViewport().setOpaque(false);
        scroll.setOpaque(false);
        queuePanel.add(scroll, BorderLayout.CENTER);
        details.add(queuePanel, BorderLayout.CENTER);

        c.gridx = 1; c.weightx = 1; c.insets = new Insets(0, 0, 0, 0);
        scene.add(details, c);
        return scene;
    }

    private JComponent transport() {
        JPanel transport = new JPanel(new BorderLayout(16, 8));
        transport.setOpaque(false);
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 0));
        actionsPanel.setOpaque(false);
        JButton previous = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.PREVIOUS, 18, Theme.TEXT), "Previous track");
        JButton next = UiFactory.iconButton(AppIcons.of(AppIcons.Kind.NEXT, 18, Theme.TEXT), "Next track");
        playPause.setPreferredSize(new Dimension(54, 54));
        playPause.setBackground(Theme.BLUE);
        previous.addActionListener(event -> actions.previous());
        next.addActionListener(event -> actions.next());
        playPause.addActionListener(event -> actions.playPause());
        actionsPanel.add(previous);
        actionsPanel.add(playPause);
        actionsPanel.add(next);
        transport.add(actionsPanel, BorderLayout.NORTH);

        JPanel timeline = new JPanel(new BorderLayout(12, 0));
        timeline.setOpaque(false);
        seek.setOpaque(false);
        seek.setEnabled(false);
        seek.setToolTipText("Track position");
        seek.addChangeListener(event -> {
            if (!updatingSeek && seek.isEnabled() && seek.getValueIsAdjusting())
                actions.seek(seek.getValue() / 1000.0);
        });
        seek.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                if (!seek.isEnabled() || !SwingUtilities.isLeftMouseButton(event)) return;
                int value = seek.getUI() instanceof BasicSliderUI ui ? ui.valueForXPosition(event.getX())
                    : (int) Math.round(event.getX() * 1000.0 / Math.max(1, seek.getWidth()));
                value = Math.max(0, Math.min(1000, value));
                updatingSeek = true; seek.setValue(value); updatingSeek = false;
                actions.seek(value / 1000.0);
            }
        });
        timeline.add(elapsed, BorderLayout.WEST);
        timeline.add(seek, BorderLayout.CENTER);
        timeline.add(remaining, BorderLayout.EAST);
        transport.add(timeline, BorderLayout.SOUTH);
        return transport;
    }

    public void setSession(List<SessionPlanner.Choice> choices, int selectedIndex, ListeningSpace space) {
        queueModel.clear();
        choices.forEach(queueModel::addElement);
        if (!choices.isEmpty()) queue.setSelectedIndex(Math.max(0, Math.min(selectedIndex, choices.size() - 1)));
        trackCount.setText(choices.isEmpty() ? "A song can fill this space" : choices.size() + " tracks in this journey");
        setSpace(space);
    }

    public void setSpace(ListeningSpace space) {
        ListeningSpace safe = space == null ? ListeningSpace.QUIET_ORBIT : space;
        roomLabel.setText(safe.displayName());
        roomLabel.setForeground(safe.accent());
        stage.setSpace(safe);
    }

    public void setPlayback(PlaybackState state, TrackTags tags) {
        if (state == null || state.track() == null) {
            title.setText("Choose a song to begin");
            artist.setText("Your library is ready when you are");
            traits.setText("A listening space shaped by your choices");
            seek.setEnabled(false);
            elapsed.setText("0:00"); remaining.setText("0:00");
            stage.setPlaying(false);
            stage.loadArtwork(null);
            return;
        }
        title.setText(state.track().title());
        artist.setText(state.track().artist() + "  /  " + state.track().album());
        TrackTags profile = tags == null ? TrackTags.pending() : tags;
        traits.setText(profile.analyzed()
            ? "Estimated energy " + profile.energy() + "/100  ·  Estimated movement " + profile.activity() + "/100"
            : "Your chosen space guides this scene");
        seek.setEnabled(state.durationSeconds() > 0 && state.status() != PlaybackState.Status.LOADING);
        updatingSeek = true;
        seek.setValue(state.durationSeconds() <= 0 ? 0
            : Math.max(0, Math.min(1000, (int) Math.round(state.positionSeconds() / state.durationSeconds() * 1000))));
        updatingSeek = false;
        elapsed.setText(clock(state.positionSeconds()));
        remaining.setText("−" + clock(Math.max(0, state.durationSeconds() - state.positionSeconds())));
        boolean playing = state.status() == PlaybackState.Status.PLAYING;
        playPause.setIcon(AppIcons.of(playing ? AppIcons.Kind.PAUSE : AppIcons.Kind.PLAY, 24, Theme.BLACK));
        playPause.setToolTipText(playing ? "Pause" : "Play");
        stage.setTraits(profile.energy(), profile.activity());
        stage.loadArtwork(state.track().path());
        stage.setPlaying(playing);
    }

    private String clock(double seconds) {
        long whole = Math.max(0, Math.round(seconds));
        return String.format("%d:%02d", whole / 60, whole % 60);
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class WorldStage extends JPanel {
        private static final long serialVersionUID = 1L;
        private ListeningSpace space = ListeningSpace.QUIET_ORBIT;
        private int energy = 50;
        private int movement = 50;
        private double phase;
        private boolean reducedMotion;
        private Path artworkPath;
        private BufferedImage artwork;
        private final Timer motion = new Timer(42, event -> { phase += .012; repaint(); });

        WorldStage() {
            setOpaque(false);
            setPreferredSize(new Dimension(500, 430));
            getAccessibleContext().setAccessibleName("Ambient listening scene");
        }

        void setSpace(ListeningSpace value) { space = value; repaint(); }
        void setTraits(int energy, int movement) { this.energy = energy; this.movement = movement; repaint(); }
        void setPlaying(boolean playing) {
            if (playing && !reducedMotion && !motion.isRunning()) motion.start();
            else if ((!playing || reducedMotion) && motion.isRunning()) motion.stop();
        }
        void loadArtwork(Path path) {
            if (java.util.Objects.equals(path, artworkPath)) return;
            artworkPath = path;
            artwork = null;
            repaint();
            if (path == null) return;
            new SwingWorker<BufferedImage, Void>() {
                @Override protected BufferedImage doInBackground() throws Exception {
                    if (!java.nio.file.Files.isRegularFile(path)) return null;
                    var tag = AudioFileIO.read(path.toFile()).getTag();
                    if (tag == null || tag.getFirstArtwork() == null) return null;
                    byte[] bytes = tag.getFirstArtwork().getBinaryData();
                    if (bytes == null || bytes.length == 0) return null;
                    BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (source == null) return null;
                    int size = Math.max(source.getWidth(), source.getHeight());
                    if (size <= 500) return source;
                    double scale = 500.0 / size;
                    BufferedImage scaled = new BufferedImage(Math.max(1, (int) (source.getWidth() * scale)),
                        Math.max(1, (int) (source.getHeight() * scale)), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D draw = scaled.createGraphics();
                    draw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    draw.drawImage(source, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
                    draw.dispose();
                    return scaled;
                }
                @Override protected void done() {
                    if (!java.util.Objects.equals(path, artworkPath)) return;
                    try { artwork = get(); }
                    catch (Exception ignored) { artwork = null; }
                    repaint();
                }
            }.execute();
        }
        void setReducedMotion(boolean value) {
            reducedMotion = value;
            if (value && motion.isRunning()) motion.stop();
            repaint();
        }

        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) { g.dispose(); return; }
            g.setPaint(new GradientPaint(0, 0, new Color(9, 15, 30), 0, h, space.horizon()));
            g.fillRoundRect(0, 0, w, h, 30, 30);

            double orbSize = Math.min(w, h) * (.25 + energy / 520.0);
            double orbX = w * (.63 + Math.sin(phase) * .018 * movement / 50.0) - orbSize / 2;
            double orbY = h * .36 + Math.cos(phase * .72) * 7 - orbSize / 2;
            g.setColor(new Color(space.accent().getRed(), space.accent().getGreen(), space.accent().getBlue(), 24));
            g.fill(new Ellipse2D.Double(orbX - 32, orbY - 32, orbSize + 64, orbSize + 64));
            g.setColor(new Color(space.accent().getRed(), space.accent().getGreen(), space.accent().getBlue(), 170));
            g.fill(new Ellipse2D.Double(orbX, orbY, orbSize, orbSize));

            g.setStroke(new BasicStroke(1.2f));
            g.setColor(new Color(Theme.TEXT.getRed(), Theme.TEXT.getGreen(), Theme.TEXT.getBlue(), 80));
            g.draw(new Ellipse2D.Double(orbX - orbSize * .35, orbY + orbSize * .1,
                orbSize * 1.7, orbSize * .82));
            double orbit = phase * (movement < 34 ? .35 : movement > 66 ? 1.1 : .65);
            double dotX = w * .63 + Math.cos(orbit) * w * .17;
            double dotY = h * .36 + Math.sin(orbit) * h * .09;
            g.setColor(Theme.TEXT);
            g.fill(new Ellipse2D.Double(dotX - 4, dotY - 4, 8, 8));

            Path2D horizon = new Path2D.Double();
            horizon.moveTo(0, h * .70);
            int steps = Math.max(12, w / 18);
            for (int i = 0; i <= steps; i++) {
                double x = i * w / (double) steps;
                double wave = Math.sin(i * .42 + phase * .18) * h * (.012 + movement / 5000.0);
                horizon.lineTo(x, h * .70 + wave);
            }
            horizon.lineTo(w, h); horizon.lineTo(0, h); horizon.closePath();
            g.setColor(new Color(4, 8, 17, 180));
            g.fill(horizon);
            if (artwork != null) {
                int artSize = (int) Math.min(w * .31, h * .52);
                int artX = (int) (w * .08);
                int artY = (int) (h * .27);
                Shape oldClip = g.getClip();
                g.setClip(new RoundRectangle2D.Double(artX, artY, artSize, artSize, 20, 20));
                g.drawImage(artwork, artX, artY, artSize, artSize, null);
                g.setClip(oldClip);
                g.setColor(new Color(Theme.TEXT.getRed(), Theme.TEXT.getGreen(), Theme.TEXT.getBlue(), 105));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new RoundRectangle2D.Double(artX, artY, artSize, artSize, 20, 20));
            }
            g.setColor(new Color(Theme.TEXT.getRed(), Theme.TEXT.getGreen(), Theme.TEXT.getBlue(), 100));
            g.setFont(Theme.font(Font.PLAIN, 12));
            g.drawString("Scene shaped by your room and saved track traits", 24, h - 18);
            g.dispose();
        }
    }
}
