package sangitam.desktop.ui;

import sangitam.desktop.model.SmartPlaylistRule;
import sangitam.desktop.model.SmartPlaylistRule.Range;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata;
import sangitam.desktop.storage.SmartPlaylistStore.Preview;

import javax.swing.*;
import java.awt.*;
import java.text.ParseException;

/** Rule editor and asynchronous preview. It builds values; CollectionStore owns saving them. */
final class SmartPlaylistPanel extends JPanel implements AutoCloseable {
    private static final long serialVersionUID = 1L;
    @FunctionalInterface interface Query { Preview run(SmartPlaylistRule rule) throws Exception; }
    private final Query query;
    private final JTextField name = text("Playlist name");
    private final JTextField description = text("Playlist description");
    private final JTextField contains = text("Text contains");
    private final JTextField genre = text("Exact genre");
    private final JTextField tags = text("Required tags");
    private final JCheckBox energy = new JCheckBox("Filter energy");
    private final JCheckBox activity = new JCheckBox("Filter movement");
    private final JSpinner energyLow = spinner("Minimum energy", 0, 0, 100);
    private final JSpinner energyHigh = spinner("Maximum energy", 100, 0, 100);
    private final JSpinner activityLow = spinner("Minimum movement", 0, 0, 100);
    private final JSpinner activityHigh = spinner("Maximum movement", 100, 0, 100);
    private final JSpinner plays = spinner("Minimum plays", 0, 0, Integer.MAX_VALUE);
    private final JSpinner limit = spinner("Track limit", 100, 1, 5000);
    private final JCheckBox available = new JCheckBox("Available tracks only", true);
    private final JComboBox<SmartPlaylistRule.Order> order = new JComboBox<>(SmartPlaylistRule.Order.values());
    private final JButton preview = UiFactory.button("Preview matches", null, false);
    private final JLabel status = UiFactory.label("Preview to see current matches. All enabled conditions must match.", Theme.SMALL, Theme.TEXT_MUTED);
    private final DefaultListModel<Track> examples = new DefaultListModel<>();
    private SwingWorker<Preview, Void> worker;
    private long generation;
    private boolean closed;

    SmartPlaylistPanel(String title, String note, SmartPlaylistRule initial, Query query) {
        super(new BorderLayout(0, 12));
        this.query = query;
        name.setText(title); description.setText(note);
        contains.setText(initial.text()); genre.setText(initial.genre()); tags.setText(String.join(", ", initial.tags()));
        energy.setSelected(initial.energy() != null); activity.setSelected(initial.activity() != null);
        if (initial.energy() != null) { energyLow.setValue(initial.energy().minimum()); energyHigh.setValue(initial.energy().maximum()); }
        if (initial.activity() != null) { activityLow.setValue(initial.activity().minimum()); activityHigh.setValue(initial.activity().maximum()); }
        plays.setValue(initial.minimumPlays()); limit.setValue(initial.limit()); available.setSelected(initial.availableOnly()); order.setSelectedItem(initial.order());
        order.getAccessibleContext().setAccessibleName("Smart playlist order");
        JPanel form = new JPanel(new GridBagLayout()); form.setOpaque(false);
        addRow(form, 0, "Name", name); addRow(form, 1, "Description", description);
        addRow(form, 2, "Text contains", contains); contains.setToolTipText("Search song labels, classical details and custom tags");
        addRow(form, 3, "Exact genre (optional)", genre);
        addRow(form, 4, "Required tags (all)", tags); tags.setToolTipText("Comma-separated exact tags; every tag must be present");
        addRow(form, 5, "Energy", range(energy, energyLow, energyHigh));
        addRow(form, 6, "Movement", range(activity, activityLow, activityHigh));
        addRow(form, 7, "Minimum local plays", plays);
        addRow(form, 8, "Order", order); addRow(form, 9, "Include at most", limit);
        addRow(form, 10, "Availability", available);
        JPanel heading = new JPanel(new BorderLayout(0, 8)); heading.setOpaque(false);
        heading.add(UiFactory.label("Save rules that update as your library changes.", Theme.BODY_BOLD, Theme.TEXT), BorderLayout.NORTH);
        heading.add(form, BorderLayout.CENTER);
        heading.add(UiFactory.label("Rating filters exclude unmeasured tracks. Playback queues keep their order once started.", Theme.SMALL, Theme.TEXT_MUTED), BorderLayout.SOUTH);
        add(heading, BorderLayout.NORTH);
        JList<Track> list = new JList<>(examples); list.setFixedCellHeight(30);
        list.getAccessibleContext().setAccessibleName("Smart playlist preview");
        DefaultListCellRenderer renderer = new DefaultListCellRenderer(); renderer.putClientProperty("html.disable", true); list.setCellRenderer(renderer);
        JScrollPane scroll = new JScrollPane(list); scroll.setPreferredSize(new Dimension(600, 120)); add(scroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(new BorderLayout(10, 0)); bottom.setOpaque(false);
        status.getAccessibleContext().setAccessibleName("Smart playlist preview status");
        bottom.add(status, BorderLayout.CENTER); bottom.add(preview, BorderLayout.EAST); add(bottom, BorderLayout.SOUTH);
        for (JTextField field : new JTextField[] { contains, genre, tags }) LibraryPanel.listen(field, this::invalidatePreview);
        for (JSpinner field : new JSpinner[] { energyLow, energyHigh, activityLow, activityHigh, plays, limit }) field.addChangeListener(event -> invalidatePreview());
        energy.addActionListener(event -> { updateRanges(); invalidatePreview(); });
        activity.addActionListener(event -> { updateRanges(); invalidatePreview(); });
        available.addActionListener(event -> invalidatePreview()); order.addActionListener(event -> invalidatePreview());
        preview.addActionListener(event -> preview()); updateRanges();
    }
    String playlistName() {
        String value = name.getText().strip();
        if (value.isBlank()) throw new IllegalArgumentException("Give the playlist a name.");
        return value;
    }
    String description() { return description.getText().strip(); }
    SmartPlaylistRule rule() {
        try {
            plays.commitEdit(); limit.commitEdit();
            if (energy.isSelected()) { energyLow.commitEdit(); energyHigh.commitEdit(); }
            if (activity.isSelected()) { activityLow.commitEdit(); activityHigh.commitEdit(); }
        }
        catch (ParseException error) { throw new IllegalArgumentException("Enter whole numbers in the rating, play count and limit fields."); }
        return new SmartPlaylistRule(contains.getText(), genre.getText(), TrackMetadata.parseTags(tags.getText()),
            energy.isSelected() ? new Range(number(energyLow), number(energyHigh)) : null,
            activity.isSelected() ? new Range(number(activityLow), number(activityHigh)) : null,
            number(plays), available.isSelected(), (SmartPlaylistRule.Order) order.getSelectedItem(), number(limit));
    }
    private void updateRanges() {
        energyLow.setEnabled(energy.isSelected()); energyHigh.setEnabled(energy.isSelected());
        activityLow.setEnabled(activity.isSelected()); activityHigh.setEnabled(activity.isSelected());
    }
    private void invalidatePreview() {
        generation++;
        if (worker != null) worker.cancel(true);
        examples.clear(); preview.setEnabled(!closed);
        status.setText("Rules changed. Preview to refresh matches.");
    }
    private void preview() {
        if (closed) return;
        final SmartPlaylistRule rule;
        try { rule = rule(); }
        catch (IllegalArgumentException error) { status.setText(error.getMessage()); return; }
        if (worker != null) worker.cancel(true);
        long request = ++generation; examples.clear(); preview.setEnabled(false); status.setText("Finding matches…");
        worker = new SwingWorker<>() {
            @Override protected Preview doInBackground() throws Exception { return query.run(rule); }
            @Override protected void done() {
                if (closed || isCancelled() || request != generation) return;
                preview.setEnabled(true);
                try {
                    Preview result = get(); examples.addAll(result.examples());
                    status.setText(result.matching() + " matching · " + result.included() + " included · Preview shows " + result.examples().size());
                } catch (Exception error) { status.setText("Could not preview matches. Try again after the library finishes loading."); }
            }
        }; worker.execute();
    }
    @Override public void close() { closed = true; generation++; if (worker != null) worker.cancel(true); }
    private static JTextField text(String label) { JTextField field = new JTextField(30); field.getAccessibleContext().setAccessibleName(label); return field; }
    private static JSpinner spinner(String label, int value, int low, int high) { JSpinner field = new JSpinner(new SpinnerNumberModel(value, low, high, 1)); field.getAccessibleContext().setAccessibleName(label); return field; }
    private static int number(JSpinner field) { return ((Number) field.getValue()).intValue(); }
    private static JPanel range(JCheckBox enabled, JSpinner low, JSpinner high) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); row.setOpaque(false);
        row.add(enabled); row.add(low); row.add(new JLabel("to")); row.add(high); return row;
    }
    private static void addRow(JPanel panel, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints(); c.gridy = row; c.gridx = 0;
        c.insets = new Insets(3, 0, 3, 12); c.anchor = GridBagConstraints.WEST;
        JLabel name = UiFactory.label(label, Theme.SMALL, Theme.TEXT); name.setLabelFor(field); panel.add(name, c);
        c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL; panel.add(field, c);
    }
}
