package sangitam.desktop.ui;

import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Sangitam's signature interaction: request a queue by intent, energy and available time.
 * IMPORTANT: The implementation is intentionally ordinary Swing—models, listeners and a custom-painted panel.
 */
public final class SessionComposerPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final transient Map<String, JToggleButton> intents = new LinkedHashMap<>();
    private final JSlider energy = new JSlider(0, 100, 45);
    private final JLabel energyValue = UiFactory.label("45 · balanced", Theme.SMALL, Theme.BLUE);
    private final JComboBox<String> duration = new JComboBox<>(new String[] {"25 minutes", "45 minutes", "60 minutes", "90 minutes"});
    private final JCheckBox familiarOnly = new JCheckBox("Prioritise familiar tracks");
    private final JLabel recipeTitle = UiFactory.label("Balanced focus", Theme.HEADING, Theme.TEXT);
    private final JLabel recipeDetail = UiFactory.label(
        "<html>Choose an intent and build from your library.<br>You can edit the queue below.</html>",
        Theme.BODY, Theme.TEXT_MUTED);
    private final SoundscapeCanvas canvas = new SoundscapeCanvas();
    private final transient Consumer<String> notify;
    private final transient Consumer<SessionPlanner.Request> onBuild;

    public SessionComposerPanel(Consumer<String> notify, Consumer<SessionPlanner.Request> onBuild) {
        super(new BorderLayout(24, 0));
        this.notify = notify;
        this.onBuild = onBuild;
        setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 20), Theme.padding(24, 26, 24, 26)));
        add(controls(), BorderLayout.WEST);
        add(preview(), BorderLayout.CENTER);
    }

    private JComponent controls() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(350, 0));

        panel.add(UiFactory.label("Shape this session", Theme.HEADING, Theme.TEXT));
        panel.add(Box.createVerticalStrut(5));
        panel.add(UiFactory.label("Start with how you want the next hour to feel.", Theme.BODY, Theme.TEXT_MUTED));
        panel.add(Box.createVerticalStrut(20));
        panel.add(fieldLabel("Intent"));
        panel.add(Box.createVerticalStrut(8));

        JPanel intentRow = new JPanel(new GridLayout(1, 3, 8, 0));
        intentRow.setOpaque(false);
        ButtonGroup intentGroup = new ButtonGroup();
        addIntent(intentRow, intentGroup, "Focus", true);
        addIntent(intentRow, intentGroup, "Explore", false);
        addIntent(intentRow, intentGroup, "Unwind", false);
        intentRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        intentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(intentRow);
        panel.add(Box.createVerticalStrut(17));

        JPanel energyHeading = new JPanel(new BorderLayout());
        energyHeading.setOpaque(false);
        energyHeading.setAlignmentX(Component.LEFT_ALIGNMENT);
        energyHeading.add(fieldLabel("Energy"), BorderLayout.WEST);
        energyHeading.add(energyValue, BorderLayout.EAST);
        panel.add(energyHeading);
        energy.setOpaque(false);
        energy.setMajorTickSpacing(25);
        energy.setPaintTicks(true);
        energy.setAlignmentX(Component.LEFT_ALIGNMENT);
        energy.addChangeListener(event -> energyValue.setText(energy.getValue() + " · " + energyWord()));
        panel.add(energy);
        panel.add(Box.createVerticalStrut(12));

        JPanel durationRow = new JPanel(new BorderLayout(12, 0));
        durationRow.setOpaque(false);
        durationRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        durationRow.add(fieldLabel("Time available"), BorderLayout.WEST);
        duration.setFont(Theme.BODY);
        duration.setForeground(Theme.TEXT);
        duration.setBackground(Theme.SURFACE_RAISED);
        durationRow.add(duration, BorderLayout.CENTER);
        durationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
        panel.add(durationRow);
        panel.add(Box.createVerticalStrut(12));
        familiarOnly.setOpaque(false);
        familiarOnly.setForeground(Theme.TEXT_MUTED);
        familiarOnly.setFont(Theme.SMALL);
        familiarOnly.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(familiarOnly);
        panel.add(Box.createVerticalStrut(17));

        JButton build = UiFactory.button("Build my session", AppIcons.of(AppIcons.Kind.PLAY, 15, Theme.BLACK), true);
        build.setAlignmentX(Component.LEFT_ALIGNMENT);
        build.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        build.addActionListener(event -> buildSession());
        panel.add(build);
        return panel;
    }

    private JComponent preview() {
        JPanel preview = new JPanel(new BorderLayout(0, 12));
        preview.setBackground(Theme.BLACK);
        preview.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(Theme.DIVIDER, 18), Theme.padding(18, 20, 18, 20)));
        preview.add(canvas, BorderLayout.CENTER);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiFactory.label("Session preview", Theme.SMALL, Theme.BLUE));
        copy.add(Box.createVerticalStrut(5));
        copy.add(recipeTitle);
        copy.add(Box.createVerticalStrut(4));
        copy.add(recipeDetail);
        preview.add(copy, BorderLayout.SOUTH);
        return preview;
    }

    private void addIntent(JPanel row, ButtonGroup group, String name, boolean selected) {
        JToggleButton button = new JToggleButton(name, selected);
        button.setFont(Theme.BODY_BOLD);
        button.setForeground(Theme.TEXT);
        button.setBackground(selected ? Theme.BLUE_STRONG : Theme.SURFACE_RAISED);
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(selected ? Theme.BLUE : Theme.DIVIDER, 10), Theme.padding(7, 9, 7, 9)));
        button.addActionListener(event -> {
            intents.values().forEach(this::styleIntentButton);
            notify.accept(name + " intent selected");
        });
        intents.put(name, button);
        group.add(button);
        row.add(button);
    }

    private void styleIntentButton(JToggleButton button) {
        button.setBackground(button.isSelected() ? Theme.BLUE_STRONG : Theme.SURFACE_RAISED);
        button.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(button.isSelected() ? Theme.BLUE : Theme.DIVIDER, 10),
            Theme.padding(7, 9, 7, 9)));
    }

    private JLabel fieldLabel(String text) {
        return UiFactory.label(text, Theme.BODY_BOLD, Theme.TEXT);
    }

    private void buildSession() {
        String intent = intents.entrySet().stream()
            .filter(entry -> entry.getValue().isSelected())
            .map(Map.Entry::getKey).findFirst().orElse("Focus");
        int minutes = Integer.parseInt(((String) duration.getSelectedItem()).split(" ")[0]);
        recipeTitle.setText(intent + " · " + energyWord());
        recipeDetail.setText("Choosing tracks from your library…");
        canvas.reveal(energy.getValue());
        onBuild.accept(new SessionPlanner.Request(SessionPlanner.Intent.valueOf(intent.toUpperCase(java.util.Locale.ROOT)),
            energy.getValue(), minutes, familiarOnly.isSelected()));
    }

    public void showPlan(SessionPlanner.Plan plan) {
        recipeDetail.setText(plan.choices().isEmpty() ? "No playable tracks found. Add music first."
            : "<html>" + plan.choices().size() + " tracks · about "
                + Math.round(plan.estimatedSeconds() / 60.0) + " minutes<br>See the editable queue below</html>");
    }

    private String energyWord() {
        if (energy.getValue() < 34) return "calm";
        if (energy.getValue() < 67) return "balanced";
        return "charged";
    }
}
