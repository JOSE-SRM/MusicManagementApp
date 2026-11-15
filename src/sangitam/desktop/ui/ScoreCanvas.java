package sangitam.desktop.ui;

import sangitam.desktop.library.EngravedScoreMap;
import sangitam.desktop.library.ScorePdf;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** A continuous PDF surface. Page rasters are independent of zoom and limited to the visible neighborhood. */
final class ScoreCanvas extends JComponent implements Scrollable {
    private static final int GAP = 12;
    private static final int CACHED_PAGES = 4;
    private static final java.util.concurrent.Executor RENDERER = java.util.concurrent.Executors.newSingleThreadExecutor(
        Thread.ofPlatform().daemon().name("Score renderer").factory());
    private record Document(Path file, byte[] legacy, List<Dimension> sizes) { }

    private final LinkedHashMap<Integer, BufferedImage> images = new LinkedHashMap<>(8, .75f, true);
    private final java.util.Set<Integer> failed = new java.util.HashSet<>();
    private final IntConsumer visiblePage;
    private final Consumer<String> error;
    private Document document;
    private SwingWorker<?, ?> reader;
    private int[] tops = {GAP};
    private int maxWidth;
    private int landingPage = 1;
    private double zoom = 1;
    private EngravedScoreMap.Position cursor;
    private boolean repositioning;

    ScoreCanvas(IntConsumer visiblePage, Consumer<String> error) {
        this.visiblePage = visiblePage;
        this.error = error;
        setOpaque(true);
        setBackground(Theme.BLACK);
    }

    void open(Path file, Callable<byte[]> legacy, int start) {
        clear();
        landingPage = start;
        reader = new SwingWorker<Document, Void>() {
            @Override protected Document doInBackground() throws Exception {
                var bytes = file == null ? legacy.call() : null;
                return new Document(file, bytes, ScorePdf.pageSizes(file, bytes));
            }
            @Override protected void done() {
                if (reader != this) return;
                reader = null;
                try {
                    document = get();
                    recalculate();
                    go(landingPage);
                } catch (Exception problem) {
                    error.accept("The score could not be opened.");
                }
            }
        };
        RENDERER.execute(reader);
    }

    void clear() {
        if (reader != null) reader.cancel(true);
        reader = null;
        document = null;
        cursor = null;
        images.clear();
        failed.clear();
        recalculate();
    }

    private JViewport viewport() { return getParent() instanceof JViewport view ? view : null; }

    double zoom() { return zoom; }

    void zoom(double value, Point anchor) {
        double requested = Math.clamp(value, .5, 4);
        if (requested == zoom) return;
        var view = viewport();
        var old = view.getViewPosition();
        int page = pageAt(old.y + anchor.y);
        var bounds = pageBounds(page);
        double x = bounds.width == 0 ? .5 : (old.x + anchor.x - bounds.x) / (double) bounds.width;
        double y = bounds.height == 0 ? 0 : (old.y + anchor.y - bounds.y) / (double) bounds.height;
        repositioning = true;
        zoom = requested;
        recalculate();
        resizeViewport(view);
        bounds = pageBounds(page);
        move(view, bounds.x + (int) Math.round(x * bounds.width) - anchor.x,
            bounds.y + (int) Math.round(y * bounds.height) - anchor.y);
        repositioning = false;
        viewportChanged();
    }

    void go(int number) {
        landingPage = number;
        var view = viewport();
        if (document == null || view == null) return;
        repositioning = true;
        resizeViewport(view);
        move(view, view.getViewPosition().x, pageBounds(number).y - GAP);
        repositioning = false;
        visiblePage.accept(number);
        requestPage();
    }

    private void resizeViewport(JViewport view) {
        var size = getPreferredSize();
        size.width = Math.max(size.width, view.getWidth());
        size.height = Math.max(size.height, view.getHeight());
        view.setViewSize(size);
    }

    private static void move(JViewport view, int x, int y) {
        var size = view.getViewSize();
        view.setViewPosition(new Point(Math.clamp(x, 0, Math.max(0, size.width - view.getWidth())),
            Math.clamp(y, 0, Math.max(0, size.height - view.getHeight()))));
    }

    void cursor(EngravedScoreMap.Position next, boolean follow) {
        var before = cursor;
        cursor = next;
        if (before != null) repaint(cursorBounds(before));
        if (next == null) return;
        var bounds = pageBounds(next.page());
        repaint(cursorBounds(next));
        if (!follow || bounds.isEmpty()) return;
        int top = bounds.y + (int) Math.round(next.top() * bounds.height);
        int bottom = bounds.y + (int) Math.round(next.bottom() * bounds.height);
        var view = getVisibleRect();
        if (top < view.y + 12 || bottom > view.y + view.height - 12) {
            int target = Math.max(bounds.y - GAP, top - 32);
            var port = viewport();
            repositioning = true;
            move(port, port.getViewPosition().x, target);
            repositioning = false;
            requestPage();
        }
    }

    private Rectangle cursorBounds(EngravedScoreMap.Position marker) {
        var page = pageBounds(marker.page());
        int x = page.x + (int) Math.round(marker.x() * page.width);
        int top = page.y + (int) Math.round(marker.top() * page.height);
        int height = (int) Math.round((marker.bottom() - marker.top()) * page.height);
        return new Rectangle(x - 2, top, 6, Math.max(8, height));
    }

    void viewportChanged() {
        if (document == null || repositioning) return;
        var view = getVisibleRect();
        visiblePage.accept(pageAt(view.y + Math.min(40, view.height / 4)));
        requestPage();
    }

    Rectangle pageBounds(int page) {
        if (document == null || page < 1 || page > document.sizes().size()) return new Rectangle();
        Dimension size = document.sizes().get(page - 1);
        int width = (int) Math.round(size.width * zoom);
        int height = tops[page] - tops[page - 1] - GAP;
        return new Rectangle(Math.max(GAP, (getWidth() - width) / 2), tops[page - 1], width, height);
    }

    int pageAt(int y) {
        int index = java.util.Arrays.binarySearch(tops, y);
        return Math.clamp(index < 0 ? -index - 1 : index + 1, 1, Math.max(1, tops.length - 1));
    }

    private void recalculate() {
        var sizes = document == null ? List.<Dimension>of() : document.sizes();
        tops = new int[sizes.size() + 1];
        tops[0] = GAP;
        maxWidth = 0;
        for (int i = 0; i < sizes.size(); i++) {
            var size = sizes.get(i);
            tops[i + 1] = tops[i] + (int) Math.round(size.height * zoom) + GAP;
            maxWidth = Math.max(maxWidth, (int) Math.round(size.width * zoom));
        }
        revalidate();
        repaint();
    }

    private void requestPage() {
        if (document == null || reader != null || document.sizes().isEmpty()) return;
        var view = getVisibleRect();
        if (view.height <= 0) return;
        int first = pageAt(view.y);
        int last = pageAt(view.y + view.height - 1);
        int through = Math.min(document.sizes().size(), Math.min(first + CACHED_PAGES - 1, last + 1));
        int before = Math.max(1, Math.max(first - 1, through - CACHED_PAGES + 1));
        int target = 0;
        // Keep the viewport ahead of the scroll position warm while leaving room for the previous page.
        for (int i = first; i <= through; i++)
            if (!images.containsKey(i) && !failed.contains(i)) { target = i; break; }
        if (target == 0 && before < first && !images.containsKey(before) && !failed.contains(before)) target = before;
        if (target == 0) return;
        int page = target;
        var source = document;
        reader = new SwingWorker<BufferedImage, Void>() {
            @Override protected BufferedImage doInBackground() throws Exception {
                return ScorePdf.viewportPage(source.file(), source.legacy(), page);
            }
            @Override protected void done() {
                if (reader != this) return;
                reader = null;
                try {
                    images.put(page, get());
                    while (images.size() > CACHED_PAGES) {
                        var expired = images.keySet().stream().filter(key -> key < before || key > through).findFirst();
                        images.remove(expired.orElseGet(() -> images.keySet().iterator().next()));
                    }
                } catch (Exception problem) {
                    failed.add(page);
                }
                repaint(pageBounds(page));
                requestPage();
            }
        };
        RENDERER.execute(reader);
    }

    @Override protected void paintComponent(Graphics graphics) {
        var g = (Graphics2D) graphics.create();
        try {
            var clip = g.getClipBounds();
            g.setColor(getBackground());
            g.fill(clip);
            if (document == null) return;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int number = pageAt(clip.y); number <= pageAt(clip.y + clip.height); number++) {
                var bounds = pageBounds(number);
                var image = images.get(number);
                g.setColor(Color.WHITE);
                g.fill(bounds);
                if (image != null) g.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null);
                else {
                    g.setColor(Color.DARK_GRAY);
                    g.drawString(failed.contains(number) ? "Page could not be displayed" : "Loading page " + number,
                        bounds.x + 15, bounds.y + 25);
                }
                if (cursor != null && cursor.page() == number) {
                    int x = bounds.x + (int) Math.round(cursor.x() * bounds.width);
                    int top = bounds.y + (int) Math.round(cursor.top() * bounds.height);
                    int height = (int) Math.round((cursor.bottom() - cursor.top()) * bounds.height);
                    g.setColor(new Color(18, 110, 187, 180));
                    g.fillRect(x - 1, top, 3, Math.max(8, height));
                }
            }
        } finally { g.dispose(); }
    }

    @Override public Dimension getPreferredSize() { return new Dimension(maxWidth + GAP * 2, tops[tops.length - 1]); }
    @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int axis, int direction) { return 18; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int axis, int direction) {
        return axis == SwingConstants.HORIZONTAL ? visible.width : visible.height;
    }
    @Override public boolean getScrollableTracksViewportWidth() { return viewport() != null && getPreferredSize().width <= viewport().getWidth(); }
    @Override public boolean getScrollableTracksViewportHeight() { return viewport() != null && getPreferredSize().height <= viewport().getHeight(); }
}
