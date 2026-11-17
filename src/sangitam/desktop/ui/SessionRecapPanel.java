package sangitam.desktop.ui;

import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** A quiet finish to a session, with an optional note saved locally as a listening moment. */
public final class SessionRecapPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    public interface Actions {
        void save(String title, String note);
        void shapeAnother();
        void rediscover();
    }

    private final JLabel title = UiFactory.label("A little more room in your day.", Theme.font(Font.BOLD, 30), Theme.TEXT);
    private final JLabel summary = UiFactory.label("Your listening moment is ready to keep.", Theme.BODY, Theme.TEXT_MUTED);
    private final JLabel roomLabel = UiFactory.label("SAVED IN QUIET ORBIT", Theme.SMALL, Theme.VIOLET);
    private final JTextField momentName = new JTextField("An hour with my music", 26);
    private final JTextArea note = new JTextArea(3, 32);
    private final DefaultListModel<String> tracks = new DefaultListModel<>();
    private final Actions actions;
    private String room = "Quiet Orbit";

    public SessionRecapPanel(Actions actions) {
        super(new BorderLayout(0, 26));
        this.actions = actions;
        setBackground(Theme.BLACK);
        setBorder(Theme.padding(34, 48, 34, 48));
        add(header(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);
    }

    private JComponent header() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        JLabel overline = UiFactory.label("A MOMENT, KEPT", Theme.SMALL, Theme.BLUE);
        overline.setAlignmentX(Component.LEFT_ALIGNMENT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(overline);
        panel.add(Box.createVerticalStrut(12));
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));
        panel.add(summary);
        return panel;
    }

    private JComponent body() {
        JPanel body = new JPanel(new GridLayout(1, 2, 32, 0));
        body.setOpaque(false);

        JPanel memory = new JPanel();
        memory.setOpaque(false);
        memory.setLayout(new BoxLayout(memory, BoxLayout.Y_AXIS));
        roomLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel nameLabel = UiFactory.label("Give this moment a name", Theme.BODY_BOLD, Theme.TEXT);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        momentName.setFont(Theme.BODY);
        momentName.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 10), Theme.padding(10, 12, 10, 12)));
        JLabel noteLabel = UiFactory.label("A note for the next time you find it", Theme.BODY_BOLD, Theme.TEXT);
        noteLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setFont(Theme.BODY);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setForeground(Theme.TEXT);
        note.setBackground(Theme.SURFACE_RAISED);
        note.setBorder(Theme.padding(10, 12, 10, 12));
        JScrollPane noteScroll = new JScrollPane(note);
        noteScroll.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 10));
        noteScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        memory.add(roomLabel);
        memory.add(Box.createVerticalStrut(24));
        memory.add(nameLabel);
        memory.add(Box.createVerticalStrut(8));
        memory.add(momentName);
        memory.add(Box.createVerticalStrut(18));
        memory.add(noteLabel);
        memory.add(Box.createVerticalStrut(8));
        memory.add(noteScroll);
        memory.add(Box.createVerticalGlue());

        JPanel trackPanel = new JPanel(new BorderLayout(0, 10));
        trackPanel.setOpaque(false);
        trackPanel.add(UiFactory.label("The music that made it", Theme.BODY_BOLD, Theme.TEXT), BorderLayout.NORTH);
        JList<String> list = new JList<>(tracks);
        list.setFont(Theme.BODY);
        list.setFixedCellHeight(42);
        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setBorder(UiFactory.roundedBorder(Theme.DIVIDER, 12));
        trackPanel.add(listScroll, BorderLayout.CENTER);
        body.add(memory);
        body.add(trackPanel);
        return body;
    }

    private JComponent footer() {
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        JPanel other = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        other.setOpaque(false);
        JButton again = UiFactory.button("Shape another journey", null, false);
        again.addActionListener(event -> actions.shapeAnother());
        JButton forgotten = UiFactory.button("Find a forgotten favorite", null, false);
        forgotten.addActionListener(event -> actions.rediscover());
        other.add(again);
        other.add(forgotten);
        footer.add(other, BorderLayout.WEST);
        JButton save = UiFactory.button("Keep this moment", AppIcons.of(AppIcons.Kind.PLUS, 15, Theme.BLACK), true);
        save.addActionListener(event -> {
            String name = momentName.getText().strip();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Give this moment a name first.", "Save listening moment",
                    JOptionPane.INFORMATION_MESSAGE);
                momentName.requestFocusInWindow();
                return;
            }
            actions.save(name, note.getText().strip());
        });
        footer.add(save, BorderLayout.EAST);
        return footer;
    }

    public void setSession(List<SessionPlanner.Choice> choices, ListeningSpace space) {
        room = (space == null ? ListeningSpace.QUIET_ORBIT : space).displayName();
        roomLabel.setText("SAVED IN " + room.toUpperCase());
        title.setText("A little more room in your day.");
        int seconds = choices.stream().mapToInt(choice -> choice.track().durationSeconds() > 0
            ? (int) Math.round(choice.track().durationSeconds()) : 180).sum();
        summary.setText(choices.size() + (choices.size() == 1 ? " song" : " songs") + " · about "
            + Math.max(1, Math.round(seconds / 60.0)) + " minutes · " + room);
        tracks.clear();
        choices.forEach(choice -> tracks.addElement(choice.track().title() + " — " + choice.track().artist()));
        momentName.setText(room + " · " + java.time.LocalDate.now());
        revalidate();
        repaint();
    }
}
