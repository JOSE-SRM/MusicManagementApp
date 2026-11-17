package sangitam.desktop.ui;

import sangitam.desktop.model.ListeningMoment;

import javax.swing.*;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

/** A compact shelf of locally saved listening moments, replayable after restart. */
public final class ListeningMomentsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM")
        .withZone(ZoneId.systemDefault());
    private final JPanel rows = new JPanel();
    private final Consumer<ListeningMoment> open;

    public ListeningMomentsPanel(Consumer<ListeningMoment> open) {
        super(new BorderLayout(0, 12));
        this.open = open;
        setOpaque(false);
        add(UiFactory.label("Moments you kept", Theme.HEADING, Theme.TEXT), BorderLayout.NORTH);
        rows.setOpaque(false);
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        add(rows, BorderLayout.CENTER);
        setMoments(List.of());
    }

    public void setMoments(List<ListeningMoment> moments) {
        rows.removeAll();
        if (moments.isEmpty()) {
            JLabel empty = UiFactory.label("Finish a journey and keep a note to find that feeling again.",
                Theme.SMALL, Theme.TEXT_MUTED);
            empty.setBorder(Theme.padding(5, 0, 5, 0));
            rows.add(empty);
        } else {
            for (ListeningMoment moment : moments) {
                rows.add(momentRow(moment));
                rows.add(Box.createVerticalStrut(7));
            }
        }
        revalidate();
        repaint();
    }

    private JComponent momentRow(ListeningMoment moment) {
        JPanel row = new JPanel(new BorderLayout(14, 0));
        row.setBackground(Theme.SURFACE);
        row.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 13), Theme.padding(10, 14, 10, 14)));
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel title = UiFactory.label(moment.title(), Theme.BODY_BOLD, Theme.TEXT);
        JLabel detail = UiFactory.label(moment.room() + "  ·  " + moment.tracks().size() + " tracks  ·  "
            + DATE.format(Instant.ofEpochMilli(moment.createdAtMillis())), Theme.SMALL, Theme.TEXT_MUTED);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.add(title);
        copy.add(Box.createVerticalStrut(3));
        copy.add(detail);
        if (!moment.note().isBlank()) {
            JLabel note = UiFactory.label(moment.note(), Theme.SMALL, Theme.TEXT_MUTED);
            note.setAlignmentX(Component.LEFT_ALIGNMENT);
            copy.add(Box.createVerticalStrut(3));
            copy.add(note);
        }
        JButton replay = UiFactory.button("Return", null, false);
        replay.addActionListener(event -> open.accept(moment));
        row.add(copy, BorderLayout.CENTER);
        row.add(replay, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        return row;
    }
}
