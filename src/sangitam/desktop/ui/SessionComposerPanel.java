package sangitam.desktop.ui;

import sangitam.desktop.session.SessionPlanner;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Guided choice of a listening room, shaped into an editable session by SessionPlanner. */
public final class SessionComposerPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private final Map<ListeningSpace, JToggleButton> spaces = new LinkedHashMap<>();
    private final Map<Integer, JToggleButton> durations = new LinkedHashMap<>();
    private final ButtonGroup durationGroup = new ButtonGroup();
    private final JSlider energy = new JSlider(0, 100, 45);
    private final JLabel energyValue = UiFactory.label("45 · steady", Theme.SMALL, Theme.BLUE);
    private final JCheckBox familiarOnly = new JCheckBox("Let familiar tracks lead");
    private final JLabel recipeTitle = UiFactory.label("A quieter line through your library", Theme.HEADING, Theme.TEXT);
    private final JLabel recipeDetail = UiFactory.label("Choose a room and we’ll shape the route.", Theme.BODY, Theme.TEXT_MUTED);
    private final JLabel libraryStatus = UiFactory.label("Bring in your music to open this space.", Theme.SMALL, Theme.TEXT_MUTED);
    private final SoundscapeCanvas canvas = new SoundscapeCanvas();
    private final JComponent controlsPanel;
    private final JComponent previewPanel;
    private final Consumer<String> notify;
    private final Consumer<SessionPlanner.Request> onBuild;
    private int libraryCount;
    private boolean compact;

    public SessionComposerPanel(Consumer<String> notify, Consumer<SessionPlanner.Request> onBuild) {
        super(new BorderLayout(24, 0));
        this.notify = notify;
        this.onBuild = onBuild;
        setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(new Color(54, 69, 94), 24), Theme.padding(24, 26, 24, 26)));
        controlsPanel = controls();
        previewPanel = preview();
        add(controlsPanel, BorderLayout.WEST);
        add(previewPanel, BorderLayout.CENTER);
        refreshPreview();
    }

    /** Stacks the choices and the landscape preview on a narrow workspace. */
    public void setCompact(boolean value) {
        if (compact == value) return;
        compact = value;
        removeAll();
        setLayout(new BorderLayout(value ? 0 : 24, value ? 18 : 0));
        controlsPanel.setPreferredSize(value ? new Dimension(0, 460) : new Dimension(360, 0));
        add(controlsPanel, value ? BorderLayout.NORTH : BorderLayout.WEST);
        add(previewPanel, BorderLayout.CENTER);
        revalidate();
        repaint();
    }

    public void setLibraryCount(int count) {
        libraryCount = Math.max(0, count);
        libraryStatus.setText(libraryCount == 0 ? "Bring in your music to open this space."
            : libraryCount + (libraryCount == 1 ? " song ready for a journey." : " songs ready for a journey."));
        refreshPreview();
    }

    public ListeningSpace currentSpace() {
        return spaces.entrySet().stream().filter(entry -> entry.getValue().isSelected())
            .map(Map.Entry::getKey).findFirst().orElse(ListeningSpace.QUIET_ORBIT);
    }

    private JComponent controls() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setPreferredSize(new Dimension(360, 0));
        panel.add(UiFactory.label("MAKE A LISTENING SPACE", Theme.SMALL, Theme.BLUE));
        panel.add(Box.createVerticalStrut(8));
        panel.add(UiFactory.label("Where would you like to go?", Theme.font(Font.BOLD, 24), Theme.TEXT));
        panel.add(Box.createVerticalStrut(6));
        panel.add(UiFactory.label("Your collection becomes the landscape.", Theme.BODY, Theme.TEXT_MUTED));
        panel.add(Box.createVerticalStrut(20));

        panel.add(fieldLabel("Choose a room"));
        panel.add(Box.createVerticalStrut(9));
        JPanel roomRow = new JPanel(new GridLayout(1, 3, 8, 0));
        roomRow.setOpaque(false);
        ButtonGroup roomGroup = new ButtonGroup();
        for (ListeningSpace space : ListeningSpace.values()) addSpace(roomRow, roomGroup, space);
        roomRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        roomRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(roomRow);
        panel.add(Box.createVerticalStrut(18));

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
        energy.setToolTipText("Choose the pace of the session from gentle to bright");
        energy.getAccessibleContext().setAccessibleName("Session energy");
        energy.addChangeListener(event -> {
            energyValue.setText(energy.getValue() + " · " + energyWord().toLowerCase());
            refreshPreview();
        });
        panel.add(energy);
        panel.add(Box.createVerticalStrut(14));

        panel.add(fieldLabel("How long do you have?"));
        panel.add(Box.createVerticalStrut(9));
        JPanel durationRow = new JPanel(new GridLayout(1, 4, 7, 0));
        durationRow.setOpaque(false);
        addDuration(durationRow, 25, true);
        addDuration(durationRow, 45, false);
        addDuration(durationRow, 60, false);
        addDuration(durationRow, 90, false);
        durationRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        durationRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(durationRow);
        panel.add(Box.createVerticalStrut(12));

        familiarOnly.setOpaque(false);
        familiarOnly.setForeground(Theme.TEXT_MUTED);
        familiarOnly.setFont(Theme.SMALL);
        familiarOnly.setAlignmentX(Component.LEFT_ALIGNMENT);
        familiarOnly.setToolTipText("Give songs with more listening history a gentle priority");
        familiarOnly.addActionListener(event -> refreshPreview());
        panel.add(familiarOnly);
        panel.add(Box.createVerticalStrut(12));
        libraryStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(libraryStatus);
        panel.add(Box.createVerticalStrut(12));

        JButton build = UiFactory.button("Shape this journey", AppIcons.of(AppIcons.Kind.PLAY, 15, Theme.BLACK), true);
        build.setAlignmentX(Component.LEFT_ALIGNMENT);
        build.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        build.addActionListener(event -> buildSession());
        panel.add(build);
        return panel;
    }

    private JComponent preview() {
        JPanel preview = new JPanel(new BorderLayout(0, 13));
        preview.setOpaque(false);
        preview.setBorder(Theme.padding(2, 0, 0, 0));
        preview.add(canvas, BorderLayout.CENTER);
        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        copy.add(UiFactory.label("YOUR ROOM", Theme.SMALL, Theme.VIOLET));
        copy.add(Box.createVerticalStrut(5));
        copy.add(recipeTitle);
        copy.add(Box.createVerticalStrut(4));
        copy.add(recipeDetail);
        preview.add(copy, BorderLayout.SOUTH);
        return preview;
    }

    private void addSpace(JPanel row, ButtonGroup group, ListeningSpace space) {
        String markup = "<html><center><b>" + space.displayName() + "</b><br><small>" + space.intentLabel() + "</small></center></html>";
        JToggleButton button = new JToggleButton(markup, space == ListeningSpace.QUIET_ORBIT);
        button.setFont(Theme.SMALL);
        button.setForeground(space == ListeningSpace.QUIET_ORBIT ? Theme.BLACK : Theme.TEXT);
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(space.description());
        button.getAccessibleContext().setAccessibleName(space.displayName());
        button.getAccessibleContext().setAccessibleDescription(space.description());
        button.addActionListener(event -> {
            styleSpaces();
            refreshPreview();
            notify.accept(space.displayName() + " selected");
        });
        spaces.put(space, button);
        group.add(button);
        row.add(button);
        styleSpace(space, button);
    }

    private void addDuration(JPanel row, int minutes, boolean selected) {
        JToggleButton button = new JToggleButton(minutes + " min", selected);
        button.setFont(Theme.SMALL.deriveFont(Font.BOLD));
        button.setForeground(selected ? Theme.BLACK : Theme.TEXT_MUTED);
        button.setOpaque(true);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setToolTipText(minutes + " minute listening journey");
        button.getAccessibleContext().setAccessibleName(minutes + " minute session length");
        button.addActionListener(event -> {
            durations.forEach(this::styleDuration);
            refreshPreview();
        });
        durations.put(minutes, button);
        durationGroup.add(button);
        row.add(button);
        styleDuration(minutes, button);
    }

    private void styleSpaces() { spaces.forEach(this::styleSpace); }

    private void styleSpace(ListeningSpace space, JToggleButton button) {
        boolean selected = button.isSelected();
        button.setBackground(selected ? space.accent() : Theme.SURFACE_RAISED);
        button.setForeground(selected ? Theme.BLACK : Theme.TEXT);
        button.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(selected ? space.accent() : Theme.DIVIDER, 13), Theme.padding(7, 4, 7, 4)));
    }

    private void styleDuration(int minutes, JToggleButton button) {
        button.setBackground(button.isSelected() ? Theme.BLUE : Theme.SURFACE_RAISED);
        button.setForeground(button.isSelected() ? Theme.BLACK : Theme.TEXT_MUTED);
        button.setBorder(BorderFactory.createCompoundBorder(
            UiFactory.roundedBorder(button.isSelected() ? Theme.BLUE : Theme.DIVIDER, 10), Theme.padding(7, 4, 7, 4)));
    }

    private JLabel fieldLabel(String text) { return UiFactory.label(text, Theme.BODY_BOLD, Theme.TEXT); }

    private int selectedDuration() {
        return durations.entrySet().stream().filter(entry -> entry.getValue().isSelected())
            .map(Map.Entry::getKey).findFirst().orElse(25);
    }

    private void buildSession() {
        if (libraryCount == 0) {
            notify.accept("Add music to your library before shaping a journey");
            return;
        }
        ListeningSpace space = currentSpace();
        int minutes = selectedDuration();
        recipeTitle.setText(space.description());
        recipeDetail.setText("Finding a thoughtful route through your songs…");
        canvas.reveal(energy.getValue(), space, minutes);
        onBuild.accept(new SessionPlanner.Request(space.intent(), energy.getValue(), minutes,
            familiarOnly.isSelected()));
    }

    private void refreshPreview() {
        if (spaces.isEmpty()) return;
        ListeningSpace space = currentSpace();
        int minutes = selectedDuration();
        recipeTitle.setText(space.description());
        recipeDetail.setText(libraryCount == 0 ? "Bring in your music to make this place yours."
            : minutes + " minutes  ·  " + (familiarOnly.isSelected() ? "familiar songs lead" : "familiarity and discovery"));
        canvas.preview(energy.getValue(), space, minutes);
    }

    public void showPlan(SessionPlanner.Plan plan) {
        ListeningSpace space = currentSpace();
        recipeTitle.setText(plan.choices().isEmpty() ? "A little more music will open this journey."
            : "A route through " + space.displayName());
        recipeDetail.setText(plan.choices().isEmpty() ? "Add a few tracks, then shape a place to listen."
            : plan.choices().size() + " songs selected  ·  review and edit your route below");
        canvas.reveal(energy.getValue(), space, selectedDuration());
    }

    public void showPlaylist(String name, SessionPlanner.Plan plan) {
        ListeningSpace space = currentSpace();
        recipeTitle.setText("Returning to " + name);
        recipeDetail.setText(plan.choices().size() + " songs in this saved route");
        canvas.reveal(energy.getValue(), space, selectedDuration());
    }

    private String energyWord() {
        if (energy.getValue() < 34) return "Gentle";
        if (energy.getValue() < 67) return "Steady";
        return "Bright";
    }
}
