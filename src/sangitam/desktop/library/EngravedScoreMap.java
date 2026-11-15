package sangitam.desktop.library;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDSimpleFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Locates onsets in supported two-staff LilyPond PDFs using engraved noteheads and clefs. */
public final class EngravedScoreMap {
    public record Position(int page, double x, double top, double bottom) { }
    private record Glyph(String name, float x, float y, float scale) { }
    private record Column(Position position, long pitches) { }
    private record Anchor(double time, Position position) { }
    private record Onset(double time, long pitches) { }
    private record Match(int onset, int column) { }
    private static final int[] DIATONIC = {0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6};
    private final List<Anchor> anchors;
    private final double maxGap;

    private EngravedScoreMap(List<Anchor> anchors) { this(anchors, 1.35); }

    private EngravedScoreMap(List<Anchor> anchors, double maxGap) {
        this.anchors = List.copyOf(anchors);
        this.maxGap = maxGap;
    }

    public static EngravedScoreMap recognized(List<OpticalScoreXml.Mark> marks) {
        return new EngravedScoreMap(marks.stream().filter(mark -> mark.position().x() >= 0 && mark.position().x() <= 1)
            .map(mark -> new Anchor(mark.time(), mark.position())).toList(), 5.0);
    }

    public int anchors() { return anchors.size(); }

    public double referenceAt(int page, double x, double y, double near) {
        double best = Double.POSITIVE_INFINITY, result = Double.NaN;
        for (var anchor : anchors) {
            var position = anchor.position();
            double distance = Math.abs(position.x() - x);
            if (page != position.page() || y < position.top() || y > position.bottom() || distance > .05) continue;
            double cost = distance + .00004 * Math.abs(anchor.time() - near);
            if (cost < best) { best = cost; result = anchor.time(); }
        }
        return result;
    }

    public Position positionAt(double time) {
        int low = 0, high = anchors.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (anchors.get(middle).time() <= time) low = middle + 1;
            else high = middle;
        }
        if (low == 0 || time - anchors.get(low - 1).time() > maxGap) return null;
        Anchor last = anchors.get(low - 1);
        if (low < anchors.size()) {
            Anchor next = anchors.get(low);
            var a = last.position();
            var b = next.position();
            if (a.page() == b.page() && a.top() == b.top() && b.x() > a.x() && next.time() - last.time() <= maxGap) {
                double fraction = (time - last.time()) / (next.time() - last.time());
                return new Position(a.page(), a.x() + fraction * (b.x() - a.x()), a.top(), a.bottom());
            }
        }
        return last.position();
    }

    public static EngravedScoreMap read(Path path, int first, int last, ScoreSync.Reference reference) throws IOException {
        var columns = new ArrayList<Column>();
        try (var pdf = Loader.loadPDF(path.toFile())) {
            var parser = new GlyphReader();
            for (int number = first; number <= last; number++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                var page = pdf.getPage(number - 1);
                if (page.getRotation() != 0) return new EngravedScoreMap(List.of());
                parser.setStartPage(number);
                parser.setEndPage(number);
                parser.glyphs.clear();
                parser.getText(pdf);
                var box = page.getCropBox();
                readPage(columns, parser.glyphs, number, box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
            }
        }
        if (columns.size() < 20 || columns.size() > 6000) return new EngravedScoreMap(List.of());
        var onsets = onsets(reference.notes());
        if (onsets.size() > 8000) return new EngravedScoreMap(List.of());
        return match(columns, onsets);
    }

    private static void readPage(List<Column> columns, List<Glyph> glyphs, int page, float left, float bottom, float width, float height) {
        var clefs = glyphs.stream().filter(glyph -> glyph.x() < left + width * .20)
            .filter(glyph -> glyph.name().equals("clefs.G") || glyph.name().equals("clefs.F"))
            .sorted(Comparator.comparingDouble(Glyph::y).reversed()).toList();
        if (clefs.isEmpty() || clefs.size() % 2 != 0) return;
        for (int i = 0; i < clefs.size(); i += 2) {
            Glyph high = clefs.get(i), low = clefs.get(i + 1);
            float upperEdge = i == 0 ? bottom + height : (clefs.get(i - 1).y() + high.y()) / 2;
            float lowerEdge = i + 2 == clefs.size() ? bottom : (clefs.get(i + 2).y() + low.y()) / 2;
            float staffStep = high.scale() / 8;
            if (staffStep <= 0 || high.y() - low.y() < staffStep * 8 || high.y() - low.y() > staffStep * 32) continue;
            var notes = glyphs.stream().filter(glyph -> glyph.name().startsWith("noteheads.s")
                    && glyph.scale() >= high.scale() * .85 && glyph.y() >= lowerEdge && glyph.y() < upperEdge
                    && glyph.x() > high.x() && glyph.x() > low.x())
                .sorted(Comparator.comparingDouble(Glyph::x)).toList();
            float x = 0;
            long pitches = 0;
            double top = (bottom + height - high.y() - staffStep * 9) / height;
            double end = (bottom + height - low.y() + staffStep * 9) / height;
            var changes = glyphs.stream().filter(glyph -> glyph.name().endsWith("_change") && glyph.name().startsWith("clefs.")
                && glyph.y() >= lowerEdge && glyph.y() < upperEdge).toList();
            for (var note : notes) {
                if (pitches != 0 && note.x() - x > staffStep * .45) {
                    columns.add(new Column(new Position(page, (x - left) / width, top, end), pitches));
                    pitches = 0;
                }
                if (pitches == 0) x = note.x();
                Glyph staff = Math.abs(note.y() - center(high, staffStep)) <= Math.abs(note.y() - center(low, staffStep)) ? high : low;
                for (var change : changes) {
                    if (change.x() > staff.x() && change.x() < note.x() && Math.abs(center(change, staffStep) - center(staff, staffStep)) < staffStep * 5)
                        staff = change;
                }
                int pitch = (staff.name().startsWith("clefs.G") ? 32 : 24) + Math.round((note.y() - staff.y()) / staffStep);
                if (pitch >= 0 && pitch < 63) pitches |= 1L << pitch;
            }
            if (pitches != 0) columns.add(new Column(new Position(page, (x - left) / width, top, end), pitches));
        }
    }

    private static float center(Glyph clef, float step) { return clef.y() + (clef.name().startsWith("clefs.G") ? 2 : -2) * step; }

    private static List<Onset> onsets(List<ScoreSync.Note> notes) {
        var result = new ArrayList<Onset>();
        double time = -1;
        long pitches = 0;
        for (var note : notes) {
            if (note.start() - time > .014 && pitches != 0) {
                result.add(new Onset(time, pitches));
                pitches = 0;
            }
            if (pitches == 0) time = note.start();
            int key = note.pitch(), pitch = (key / 12 - 1) * 7 + DIATONIC[key % 12];
            if (pitch >= 0 && pitch < 62) pitches |= 1L << pitch;
        }
        if (pitches != 0) result.add(new Onset(time, pitches));
        return result;
    }

    private static EngravedScoreMap match(List<Column> columns, List<Onset> onsets) throws IOException {
        var seeds = new ArrayList<Match>();
        for (int i = 2; i + 2 < onsets.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            double best = 0, alternate = 0;
            int column = -1;
            for (int j = 2; j + 2 < columns.size(); j++) {
                double exact = similarity(columns.get(j).pitches(), onsets.get(i).pitches());
                if (exact < .58) continue;
                double context = exact * .36;
                for (int step = 1; step <= 2; step++) {
                    double weight = step == 1 ? .20 : .12;
                    context += weight * (similarity(columns.get(j - step).pitches(), onsets.get(i - step).pitches())
                        + similarity(columns.get(j + step).pitches(), onsets.get(i + step).pitches()));
                }
                if (context > best) { alternate = best; best = context; column = j; }
                else if (context > alternate) alternate = context;
            }
            if (best >= .84 && best - alternate >= .075) seeds.add(new Match(i, column));
        }
        if (seeds.size() < Math.max(6, onsets.size() / 18)) return new EngravedScoreMap(List.of());
        var anchors = new ArrayList<Anchor>();
        for (int i = 0; i < seeds.size(); i++) {
            var seed = seeds.get(i);
            boolean left = i > 0 && neighboring(seeds.get(i - 1), seed, onsets);
            boolean right = i + 1 < seeds.size() && neighboring(seed, seeds.get(i + 1), onsets);
            if (!left && !right) continue;
            anchors.add(anchor(seed, columns, onsets));
            if (right) fill(anchors, seed, seeds.get(i + 1), columns, onsets);
        }
        return new EngravedScoreMap(anchors);
    }

    private static Anchor anchor(Match match, List<Column> columns, List<Onset> onsets) {
        return new Anchor(onsets.get(match.onset()).time(), columns.get(match.column()).position());
    }

    private static boolean neighboring(Match before, Match after, List<Onset> onsets) {
        int written = after.column() - before.column(), played = after.onset() - before.onset();
        return written > 0 && written <= played * 3 + 3 && played <= written * 3 + 3 && played < 80
            && onsets.get(after.onset()).time() - onsets.get(before.onset()).time() < 16;
    }

    /** Interpolate only between independent matches by aligning intervening note groups. */
    private static void fill(List<Anchor> anchors, Match before, Match after, List<Column> columns, List<Onset> onsets) {
        int played = after.onset() - before.onset() - 1, written = after.column() - before.column() - 1;
        if (played == 0 || written == 0) return;
        var cost = new double[played + 1][written + 1];
        var direction = new byte[played + 1][written + 1];
        double gap = .42;
        for (int i = 1; i <= played; i++) cost[i][0] = i * gap;
        for (int j = 1; j <= written; j++) cost[0][j] = j * gap;
        for (int i = 1; i <= played; i++) {
            for (int j = 1; j <= written; j++) {
                double agreement = similarity(columns.get(before.column() + j).pitches(), onsets.get(before.onset() + i).pitches());
                double diagonal = cost[i - 1][j - 1] + 1 - agreement;
                double up = cost[i - 1][j] + gap, left = cost[i][j - 1] + gap;
                byte step = diagonal <= up && diagonal <= left ? 0 : up <= left ? (byte) 1 : 2;
                direction[i][j] = step;
                cost[i][j] = step == 0 ? diagonal : step == 1 ? up : left;
            }
        }
        var reversed = new ArrayList<Anchor>();
        for (int i = played, j = written; i > 0 && j > 0;) {
            byte step = direction[i][j];
            if (step == 0 && similarity(columns.get(before.column() + j).pitches(), onsets.get(before.onset() + i).pitches()) >= .65)
                reversed.add(anchor(new Match(before.onset() + i, before.column() + j), columns, onsets));
            if (step != 2) i--;
            if (step != 1) j--;
        }
        anchors.addAll(reversed.reversed());
    }

    private static double similarity(long printed, long performed) {
        return 2.0 * Long.bitCount(printed & performed) / (Long.bitCount(printed) + Long.bitCount(performed));
    }

    private static final class GlyphReader extends PDFTextStripper {
        private final List<Glyph> glyphs = new ArrayList<>();
        GlyphReader() throws IOException { }

        @Override protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) {
            if (!(font instanceof PDSimpleFont simple) || !font.getName().matches("(?i).*(emmentaler|feta).*") || simple.getEncoding() == null) return;
            String name = simple.getEncoding().getName(code);
            if (name.startsWith("noteheads.s") || name.startsWith("clefs."))
                glyphs.add(new Glyph(name, matrix.getTranslateX(), matrix.getTranslateY(), matrix.getScalingFactorY()));
        }
    }
}
