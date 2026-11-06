package sangitam.desktop.ui;

import sangitam.desktop.library.LibraryService;
import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import java.awt.*;
import java.text.ParseException;
import java.util.function.Consumer;

/** Collects the listening request and draws its intended energy trajectory. */
public final class SessionComposerPanel extends JPanel {
    private final JComboBox<SessionPlanner.Intent> intent = new JComboBox<>(SessionPlanner.Intent.values());
    private final JComboBox<SessionPlanner.Mood> mood = new JComboBox<>(SessionPlanner.Mood.values());
    private final JSlider start = new JSlider(0, 100, 45);
    private final JSlider end = new JSlider(0, 100, 30);
    private final JSpinner minutes = new JSpinner(new SpinnerNumberModel(45, 1, 240, 5));
    private final JCheckBox context = new JCheckBox("Use recent listening and calendar patterns in Auto", true);
    private final JCheckBox familiar = new JCheckBox("Favour familiar recordings");
    private final JLabel analysis = UiFactory.label("Import music to begin", Theme.SMALL, Theme.TEXT_MUTED);
    private final JTextArea description = new JTextArea("Choose a mood and energy arc. Build a temporary journey and save it if you like it.");
    private final Arc arc = new Arc();
    private Runnable analyze = () -> { };

    public SessionComposerPanel(Consumer<String> notify, Consumer<SessionPlanner.Request> build) {
        super(new BorderLayout(16, 12));
        setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(UiFactory.roundedBorder(Theme.DIVIDER, 22), Theme.padding(18, 20, 18, 20)));

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        header.add(UiFactory.label("Shape the session", Theme.HEADING, Theme.TEXT), BorderLayout.WEST);
        header.add(analysis, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridLayout(0, 2, 14, 8));
        fields.setOpaque(false);
        field(fields, "Intent", intent);
        field(fields, "Mood palette", mood);
        field(fields, "Start energy", start);
        field(fields, "Arrive at energy", end);
        field(fields, "Minutes", minutes);
        minutes.setEditor(new JSpinner.NumberEditor(minutes, "0"));
        var number = ((JSpinner.NumberEditor) minutes.getEditor()).getTextField();
        number.setBackground(Theme.SURFACE_RAISED);
        number.setForeground(Theme.TEXT);
        number.setCaretColor(Theme.TEXT);
        start.setUI(new RoundSliderUI(start));
        end.setUI(new RoundSliderUI(end));
        start.setForeground(Theme.BLUE);
        end.setForeground(Theme.SUCCESS);
        mood.addActionListener(event -> {
            switch ((SessionPlanner.Mood) mood.getSelectedItem()) {
                case CALM -> start.setValue(25);
                case SAD -> start.setValue(30);
                case HAPPY -> start.setValue(55);
                case ENERGETIC -> start.setValue(80);
                case TENSE -> start.setValue(70);
                default -> { }
            }
            updateStartControl();
        });
        context.addActionListener(event -> updateStartControl());
        start.addChangeListener(event -> arc.repaint());
        end.addChangeListener(event -> arc.repaint());
        updateStartControl();

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        center.add(fields, BorderLayout.NORTH);
        center.add(arc, BorderLayout.CENTER);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setEditable(false);
        description.setOpaque(false);
        description.setForeground(Theme.TEXT_MUTED);
        description.setFont(Theme.SMALL);
        JScrollPane notes = new JScrollPane(description);
        notes.setPreferredSize(new Dimension(0, 65));
        notes.setBorder(null);
        notes.setOpaque(false);
        notes.getViewport().setOpaque(false);
        notes.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        UiFactory.styleScroll(notes);
        center.add(notes, BorderLayout.SOUTH);
        add(center);

        JPanel footer = new JPanel(new GridLayout(0, 1, 0, 5));
        footer.setOpaque(false);
        context.setOpaque(false);
        familiar.setOpaque(false);
        footer.add(context);
        footer.add(familiar);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.setOpaque(false);
        JButton refresh = UiFactory.button("Deep analyse library", null, false);
        refresh.addActionListener(event -> analyze.run());
        JButton create = UiFactory.button("Build my journey", AppIcons.of(AppIcons.Kind.PLAY, 20, Theme.BLACK), true);
        create.addActionListener(event -> {
            try {
                minutes.commitEdit();
                build.accept(new SessionPlanner.Request((SessionPlanner.Intent) intent.getSelectedItem(),
                    start.getValue(), (Integer) minutes.getValue(), familiar.isSelected(),
                    (SessionPlanner.Mood) mood.getSelectedItem(), end.getValue(), automaticStart()));
                describe("Finding recordings for this arc…");
            } catch (ParseException invalid) {
                notify.accept("Choose a valid duration");
            }
        });
        buttons.add(refresh);
        buttons.add(create);
        footer.add(buttons);
        add(footer, BorderLayout.SOUTH);
    }

    private static void field(JPanel fields, String name, JComponent input) {
        JLabel label = UiFactory.label(name, Theme.SMALL, Theme.TEXT_MUTED);
        label.setLabelFor(input);
        input.getAccessibleContext().setAccessibleName(name);
        fields.add(label);
        fields.add(input);
    }

    private boolean automaticStart() {
        return mood.getSelectedItem() == SessionPlanner.Mood.AUTO && context.isSelected();
    }

    private void updateStartControl() {
        start.setEnabled(!automaticStart());
        start.setToolTipText(automaticStart() ? "Auto chooses the starting energy from listening context" : "Set the starting energy");
        arc.repaint();
    }

    private void describe(String message) {
        description.setText(message);
        description.setCaretPosition(0);
    }

    public void setAnalyzeAction(Runnable action) { analyze = action; }

    public void setCatalog(LibraryService.Catalog catalog) {
        analysis.setText(catalog.measuredCount() + " / " + catalog.tracks().size() + " deeply measured");
    }

    public void showPlan(SessionPlanner.Plan plan) {
        describe(plan.choices().isEmpty() ? "No eligible recordings. Check availability and Avoid ratings."
            : plan.choices().size() + " recordings · " + Math.round(plan.estimatedSeconds() / 60d) + " minutes. Temporary until saved.");
    }

    public void showContext(String text) {
        if (!text.isBlank()) describe(description.getText() + "\n" + text);
    }

    public void showPlaylist(String name, SessionPlanner.Plan plan) {
        describe("Saved journey: " + name + " · " + plan.choices().size() + " recordings");
    }

    private final class Arc extends JComponent {
        Arc() {
            setPreferredSize(new Dimension(0, 90));
            setToolTipText("Requested energy arc. Recorded energy will vary between tracks.");
        }

        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int width = getWidth(), height = getHeight();
            if (width < 70 || height < 44) { g.dispose(); return; }
            int starting = automaticStart() ? 50 : start.getValue();
            int from = height - 16 - (height - 40) * starting / 100;
            int to = height - 16 - (height - 40) * end.getValue() / 100;
            g.setColor(Theme.DIVIDER);
            g.drawLine(16, height - 12, width - 16, height - 12);
            g.setColor(Theme.BLUE);
            g.setStroke(automaticStart() ? new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1, new float[]{6, 6}, 0) : new BasicStroke(3));
            g.drawLine(20, from, width - 20, to);
            g.fillOval(15, from - 5, 10, 10);
            g.setFont(Theme.SMALL);
            g.drawString(automaticStart() ? "Auto" : Integer.toString(starting), 20, 15);
            g.setColor(Theme.SUCCESS);
            g.fillOval(width - 25, to - 5, 10, 10);
            g.drawString(Integer.toString(end.getValue()), width - 40, 15);
            g.dispose();
        }
    }
}
