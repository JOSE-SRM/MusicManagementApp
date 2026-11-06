package sangitam.desktop.ui;

import sangitam.desktop.storage.ListeningInsightsStore;
import sangitam.desktop.storage.ListeningInsightsStore.Bucket;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;

/** A lazy Rediscover tab. Time bars use observed playback, while source mix counts play events. */
final class ListeningInsightsPanel extends JPanel {
    private final ListeningInsightsStore store;
    private final JPanel content = new JPanel();
    private final JLabel status = UiFactory.label("Measured local listening on this device", Theme.SMALL, Theme.TEXT_MUTED);
    private SwingWorker<ListeningInsightsStore.Snapshot, Void> worker;
    private final JButton refresh = UiFactory.button("Refresh insights", null, false);

    ListeningInsightsPanel(ListeningInsightsStore store) {
        super(new BorderLayout(0, 12));
        this.store = store;
        setBackground(Theme.SURFACE);
        setBorder(Theme.padding(12, 12, 12, 12));
        var header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        header.add(status, BorderLayout.CENTER);
        header.add(refresh, BorderLayout.EAST);
        refresh.addActionListener(event -> reload());
        add(header, BorderLayout.NORTH);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(Theme.SURFACE);
        var scroll = new JScrollPane(content);
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
        worker = new SwingWorker<>() {
            @Override protected ListeningInsightsStore.Snapshot doInBackground() throws Exception { return store.load(); }
            @Override protected void done() {
                if (isCancelled() || worker != this) return;
                refresh.setEnabled(true);
                try { render(get()); }
                catch (Exception error) { status.setText("Could not load listening insights. Try Refresh insights."); }
            }
        };
        worker.execute();
    }

    private void render(ListeningInsightsStore.Snapshot data) {
        content.removeAll();
        status.setText(minutes(data.seconds()) + " measured locally · " + data.recordings() + " recordings");
        if (data.seconds() == 0) text("Play local music to start measuring time on this device.", Theme.BODY);
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
        content.revalidate();
        content.repaint();
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
