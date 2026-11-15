package sangitam.desktop.library;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/** Reads note timing and page coordinates from Audiveris MusicXML output. */
public final class OpticalScoreXml {
    private static final double BEAT = .5;
    public record Mark(double time, EngravedScoreMap.Position position) { }
    private record Measure(double duration, List<ScoreSync.Note> notes, List<Mark> marks, boolean repeatStart, boolean repeatEnd) { }

    private OpticalScoreXml() { }

    static OpticalScoreRecognition.Result read(List<Path> pages, String revision) throws Exception {
        var measures = new ArrayList<Measure>();
        for (Path file : pages) {
            if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("A recognized score page is too large to read.");
            String name = file.getFileName().toString();
            measures.addAll(page(file, Integer.parseInt(name.substring(6, 10))));
        }
        var notes = new ArrayList<ScoreSync.Note>();
        var marks = new ArrayList<Mark>();
        double position = 0;
        for (var measure : measures) {
            append(notes, marks, measure, position);
            position += measure.duration();
        }
        if (notes.size() < 16 || position < 4) throw new IOException("Too few notes were recognized to align this score.");
        var repeatedNotes = new ArrayList<ScoreSync.Note>();
        var repeatedMarks = new ArrayList<Mark>();
        double repeatedTime = 0;
        int start = 0;
        boolean repeats = false;
        for (int i = 0; i < measures.size(); i++) {
            var measure = measures.get(i);
            if (measure.repeatStart()) start = i;
            append(repeatedNotes, repeatedMarks, measure, repeatedTime);
            repeatedTime += measure.duration();
            if (measure.repeatEnd() && start <= i) {
                repeats = true;
                for (int repeat = start; repeat <= i; repeat++) {
                    var previous = measures.get(repeat);
                    append(repeatedNotes, repeatedMarks, previous, repeatedTime);
                    repeatedTime += previous.duration();
                }
                start = i + 1;
            }
        }
        var written = new OpticalScoreRecognition.Variant(new ScoreSync.Reference(position, sorted(notes)), List.copyOf(marks), "written");
        var performed = repeats ? new OpticalScoreRecognition.Variant(new ScoreSync.Reference(repeatedTime, sorted(repeatedNotes)),
            List.copyOf(repeatedMarks), "repeats") : null;
        return new OpticalScoreRecognition.Result(written, performed, revision);
    }

    private static List<ScoreSync.Note> sorted(List<ScoreSync.Note> notes) {
        return notes.stream().sorted(Comparator.comparingDouble(ScoreSync.Note::start)).toList();
    }

    private static void append(List<ScoreSync.Note> notes, List<Mark> marks, Measure measure, double offset) {
        for (var note : measure.notes()) notes.add(new ScoreSync.Note(note.pitch(), note.start() + offset, note.end() + offset));
        for (var mark : measure.marks()) marks.add(new Mark(mark.time() + offset, mark.position()));
    }

    private static List<Measure> page(Path file, int page) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        var root = builder.parse(file.toFile()).getDocumentElement();
        if (!root.getTagName().equals("score-partwise")) return List.of();
        var defaults = child(child(root, "defaults"), "page-layout");
        var margins = child(defaults, "page-margins");
        double width = value(defaults, "page-width", 0) - value(margins, "left-margin", 0) - value(margins, "right-margin", 0);
        double height = value(defaults, "page-height", 0) - value(margins, "top-margin", 0) - value(margins, "bottom-margin", 0);
        var result = new ArrayList<Measure>();
        int part = 0;
        for (var scorePart : children(root, "part")) {
            double divisions = 1, transpose = 0, x = 0, y = 0, systemHeight = 40;
            int index = 0, staves = 1;
            for (var measure : children(scorePart, "measure")) {
                var attributes = child(measure, "attributes");
                divisions = Math.max(1, value(attributes, "divisions", divisions));
                transpose = value(child(attributes, "transpose"), "chromatic", transpose);
                staves = (int) Math.clamp(value(attributes, "staves", staves), 1, 32);
                var print = child(measure, "print");
                var system = child(print, "system-layout");
                if (system != null) {
                    x = value(child(system, "system-margins"), "left-margin", 0);
                    y = value(system, "top-system-distance", y + systemHeight + value(system, "system-distance", 0));
                    systemHeight = 40.0 * staves;
                    for (var staff : children(print, "staff-layout")) systemHeight += value(staff, "staff-distance", 0);
                }
                double cursor = 0, onset = 0, length = 0;
                var notes = new ArrayList<ScoreSync.Note>();
                var marks = new TreeMap<Double, EngravedScoreMap.Position>();
                boolean repeatStart = false, repeatEnd = false;
                for (Node node = measure.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (!(node instanceof Element event)) continue;
                    double duration = value(event, "duration", 0) * BEAT / divisions;
                    switch (event.getTagName()) {
                        case "backup" -> cursor = Math.max(0, cursor - duration);
                        case "forward" -> cursor += duration;
                        case "note" -> {
                            if (child(event, "grace") != null) continue;
                            if (child(event, "chord") == null) { onset = cursor; cursor += duration; }
                            var pitch = child(event, "pitch");
                            if (pitch != null && duration > 0) {
                                int key = pitch(pitch, transpose);
                                if (key >= 0 && key <= 127) notes.add(new ScoreSync.Note(key, onset, onset + duration));
                                double noteX = number(event.getAttribute("default-x"), Double.NaN);
                                if (part == 0 && width > 0 && height > 0 && Double.isFinite(noteX))
                                    marks.putIfAbsent(onset, new EngravedScoreMap.Position(page, (x + noteX) / width,
                                        Math.clamp((y - 12) / height, 0, 1), Math.clamp((y + systemHeight + 12) / height, 0, 1)));
                            }
                        }
                        case "barline" -> {
                            var repeat = child(event, "repeat");
                            if (repeat != null) {
                                repeatStart |= repeat.getAttribute("direction").equals("forward");
                                repeatEnd |= repeat.getAttribute("direction").equals("backward");
                            }
                        }
                        default -> { }
                    }
                    length = Math.max(length, cursor);
                }
                x += number(measure.getAttribute("width"), 0);
                if (index >= result.size()) {
                    var positions = marks.entrySet().stream().map(entry -> new Mark(entry.getKey(), entry.getValue())).toList();
                    result.add(new Measure(length, notes, positions, repeatStart, repeatEnd));
                } else {
                    var original = result.get(index);
                    original.notes().addAll(notes);
                    result.set(index, new Measure(Math.max(original.duration(), length), original.notes(), original.marks(),
                        original.repeatStart() || repeatStart, original.repeatEnd() || repeatEnd));
                }
                index++;
            }
            part++;
        }
        return result;
    }

    private static int pitch(Element pitch, double transpose) {
        String step = Optional.ofNullable(child(pitch, "step")).map(Element::getTextContent).orElse("").strip();
        int semitone = switch (step) {
            case "C" -> 0; case "D" -> 2; case "E" -> 4; case "F" -> 5; case "G" -> 7; case "A" -> 9; case "B" -> 11;
            default -> -100;
        };
        return (int) Math.round(12 * (value(pitch, "octave", 0) + 1) + semitone + value(pitch, "alter", 0) + transpose);
    }

    private static Element child(Element parent, String name) {
        if (parent == null) return null;
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && element.getTagName().equals(name)) return element;
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        var result = new ArrayList<Element>();
        if (parent != null) for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && element.getTagName().equals(name)) result.add(element);
        return result;
    }

    private static double value(Element parent, String tag, double fallback) {
        var element = child(parent, tag);
        return element == null ? fallback : number(element.getTextContent(), fallback);
    }

    private static double number(String text, double fallback) {
        try { return Double.parseDouble(text); } catch (NumberFormatException invalid) { return fallback; }
    }
}
