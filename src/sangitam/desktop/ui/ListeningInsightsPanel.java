package sangitam.desktop.ui;

import sangitam.desktop.storage.ListeningInsightsStore;
import sangitam.desktop.storage.ListeningInsightsStore.Bucket;
import sangitam.desktop.storage.ListeningInsightsStore.Day;
import sangitam.desktop.storage.ListeningInsightsStore.Range;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;

/** A lazy Rediscover tab. Time bars use observed playback, while source mix counts play events. */
final class ListeningInsightsPanel extends JPanel {
    private final ListeningInsightsStore store;
    private final JPanel content = new JPanel();
    private final JLabel status = UiFactory.label("Measured local listening on this device", Theme.SMALL, Theme.TEXT_MUTED);
    private SwingWorker<ListeningInsightsStore.Snapshot, Void> worker;
    private final JButton refresh = UiFactory.button("Refresh insights", null, false);
    private final JComboBox<Range> range = new JComboBox<>(Range.values());

    ListeningInsightsPanel(ListeningInsightsStore store) {
        super(new BorderLayout(0, 12));
        this.store = store;
        setBackground(Theme.SURFACE);
        setBorder(Theme.padding(12, 12, 12, 12));
        var header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(status, BorderLayout.CENTER);
        var controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controls.setOpaque(false);
        JLabel rangeLabel = UiFactory.label("Date range", Theme.SMALL, Theme.TEXT);
        rangeLabel.setLabelFor(range);
        controls.add(rangeLabel);
        controls.add(range);
        controls.add(refresh);
        header.add(controls, BorderLayout.EAST);
        range.addActionListener(event -> reload());
        refresh.addActionListener(event -> reload());
        add(header, BorderLayout.NORTH);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.SURFACE);
        var scroll = new PixelScrollPane(content);
        scroll.setBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        UiFactory.styleScroll(scroll);
        add(scroll);
    }

    void reload() {
        if (worker != null) worker.cancel(true);
        status.setText("Loading listening insights…");
        refresh.setEnabled(false);
        Range selected = (Range) range.getSelectedItem();
        worker = new SwingWorker<>() {
            @Override protected ListeningInsightsStore.Snapshot doInBackground() throws Exception {
                return store.load(selected, ZonedDateTime.now());
            }
            @Override protected void done() {
                if (isCancelled() || worker != this) return;
                refresh.setEnabled(true);
                try { render(get(), selected); }
                catch (Exception error) { status.setText("Could not load listening insights. Try Refresh insights."); }
            }
        };
        worker.execute();
    }

    private void render(ListeningInsightsStore.Snapshot data, Range range) {
        content.removeAll();
        status.setText(minutes(data.seconds()) + " measured locally · " + data.recordings() + " recordings");
        if (data.seconds() == 0) text("No measured listening time in this range.", Theme.BODY);
        else {
            chart("Time by genre", data.genres(), data.seconds());
            chart("Time by classical period", data.periods(), data.seconds());
            chart("Time by estimated mood", data.moods(), data.seconds());
        }
        content.add(Box.createVerticalStrut(12));
        text("Recorded plays by source", Theme.HEADING);
        text("Local and synced play events. Time is measured only during Sangitam playback.", Theme.SMALL);
        if (data.sourcePlays().isEmpty()) text("No recorded plays yet.", Theme.SMALL);
        data.sourcePlays().forEach((source, plays) -> text(source + " · " + plays + " plays", Theme.BODY));
        content.add(Box.createVerticalStrut(12));
        dailyTrend(data.trend(), range);
        content.revalidate();
        content.repaint();
    }

    private void dailyTrend(List<Day> trend, Range range) {
        text(range == Range.ALL ? "Daily trend · last 30 days" : "Daily trend", Theme.HEADING);
        var days = trend.reversed();
        var dateFormat = DateTimeFormatter.ofPattern("d MMM uuuu");
        var table = new JTable(new AbstractTableModel() {
            @Override public int getRowCount() { return days.size(); }
            @Override public int getColumnCount() { return 3; }
            @Override public String getColumnName(int column) {
                return switch (column) {
                    case 0 -> "Date";
                    case 1 -> "Local listening time";
                    default -> "Recorded plays";
                };
            }
            @Override public Object getValueAt(int row, int column) {
                Day day = days.get(row);
                return switch (column) {
                    case 0 -> day.date().format(dateFormat);
                    case 1 -> day.seconds();
                    default -> day.plays();
                };
            }
        });
        table.getAccessibleContext().setAccessibleName("Daily listening trend");
        table.getTableHeader().setReorderingAllowed(false);
        table.setRowHeight(25);
        table.getColumnModel().getColumn(1).setCellRenderer(new TrendBar(
            days.stream().mapToDouble(Day::seconds).max().orElse(0), ListeningInsightsPanel::minutes));
        table.getColumnModel().getColumn(2).setCellRenderer(new TrendBar(
            days.stream().mapToLong(Day::plays).max().orElse(0), value -> Long.toString((long) value)));
        JScrollPane scroll = new PixelScrollPane(table);
        UiFactory.styleScroll(scroll);
        scroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(480, 210));
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));
        content.add(scroll);
    }

    private static final class TrendBar extends JProgressBar implements TableCellRenderer {
        private final double maximum;
        private final DoubleFunction<String> format;

        TrendBar(double maximum, DoubleFunction<String> format) {
            super(0, 1000);
            this.maximum = maximum;
            this.format = format;
            setStringPainted(true);
            setForeground(Theme.BLUE);
        }

        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                  boolean focused, int row, int column) {
            double amount = ((Number) value).doubleValue();
            setValue(maximum > 0 ? (int) Math.round(1000 * amount / maximum) : 0);
            setString(format.apply(amount));
            setBackground(selected ? table.getSelectionBackground() : table.getBackground());
            return this;
        }
    }

    private void chart(String name, List<Bucket> buckets, double total) {
        text(name, Theme.HEADING);
        buckets.stream().limit(6).forEach(bucket -> bar(bucket.label(), bucket.seconds(), total));
        if (buckets.size() > 6) bar("Other categories", buckets.stream().skip(6).mapToDouble(Bucket::seconds).sum(), total);
        content.add(Box.createVerticalStrut(12));
    }

    private void bar(String name, double seconds, double total) {
        String label = name + " · " + minutes(seconds);
        text(label, Theme.SMALL);
        var bar = new JProgressBar(0, 1000);
        bar.setValue((int) Math.round(1000 * seconds / total));
        bar.setForeground(Theme.BLUE);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.getAccessibleContext().setAccessibleName(label);
        content.add(bar);
        content.add(Box.createVerticalStrut(6));
    }

    private void text(String value, Font font) {
        var label = UiFactory.label(value, font, Theme.TEXT);
        label.putClientProperty("html.disable", true);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(label);
        content.add(Box.createVerticalStrut(4));
    }

    private static String minutes(double seconds) { return String.format(Locale.ROOT, "%.1f min", seconds / 60); }

    void close() { if (worker != null) worker.cancel(true); }
}
