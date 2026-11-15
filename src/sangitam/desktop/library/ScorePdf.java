package sangitam.desktop.library;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.multipdf.PDFMergerUtility;

import java.awt.image.BufferedImage;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import java.util.List;

/** Reads and renders user-selected score PDFs without a browser or network access. */
public final class ScorePdf {
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static final int MAX_SIDE = 4800;

    public record Import(byte[] pdf, int pages) { }
    public record DisplayPage(BufferedImage image, int width, int height) { }

    private ScorePdf() { }

    public static List<Dimension> pageSizes(Path file, byte[] legacy) throws IOException {
        try (var document = file == null ? open(legacy) : Loader.loadPDF(file.toFile())) {
            var sizes = new java.util.ArrayList<Dimension>();
            for (int number = 1; number <= document.getNumberOfPages(); number++) {
                var box = pageBox(document, number);
                boolean rotated = document.getPage(number - 1).getRotation() % 180 != 0;
                sizes.add(new Dimension(Math.max(1, Math.round(rotated ? box.getHeight() : box.getWidth())),
                    Math.max(1, Math.round(rotated ? box.getWidth() : box.getHeight()))));
            }
            return List.copyOf(sizes);
        }
    }

    public static BufferedImage viewportPage(Path file, byte[] legacy, int page) throws IOException {
        try (var document = file == null ? open(legacy) : Loader.loadPDF(file.toFile())) {
            var box = pageBox(document, page);
            float scale = Math.min(3.25f, 2400f / Math.max(box.getWidth(), box.getHeight()));
            return new PDFRenderer(document).renderImage(page - 1, scale);
        }
    }

    public static Import read(Path path) throws IOException {
        return validate(bytes(path));
    }

    public static byte[] bytes(Path path) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Choose a PDF under 64 MB.");
        return bytes;
    }

    public static BufferedImage render(byte[] pdf, int page, float zoom) throws IOException {
        try (var document = open(pdf)) {
            return render(document, page, zoom);
        }
    }

    public static DisplayPage display(byte[] pdf, int page, float zoom, float density) throws IOException {
        try (var document = open(pdf)) {
            var box = pageBox(document, page);
            var image = render(document, page, zoom * density);
            boolean rotated = document.getPage(page - 1).getRotation() % 180 != 0;
            return new DisplayPage(image, Math.round(zoom * (rotated ? box.getHeight() : box.getWidth())),
                Math.round(zoom * (rotated ? box.getWidth() : box.getHeight())));
        }
    }

    public static Import validate(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Choose a PDF under 64 MB.");
        try (var document = open(bytes)) {
            if (document.getNumberOfPages() < 1) throw new IOException("This PDF has no pages.");
            render(document, 1, .65f);
            return new Import(bytes, document.getNumberOfPages());
        }
    }

    public static byte[] combine(List<byte[]> scores) throws IOException {
        if (scores.size() == 1) return scores.getFirst();
        if (scores.stream().mapToLong(part -> part.length).sum() > MAX_BYTES)
            throw new IOException("The combined score exceeds 64 MB.");
        var merger = new PDFMergerUtility();
        try (var complete = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            for (var part : scores) {
                try (var source = open(part)) { merger.appendDocument(complete, source); }
            }
            complete.save(bytes);
            return bytes.toByteArray();
        }
    }

    private static PDDocument open(byte[] pdf) throws IOException {
        try {
            return Loader.loadPDF(pdf);
        } catch (InvalidPasswordException locked) {
            throw new IOException("This PDF requires a password. Choose an unlocked copy.", locked);
        } catch (IOException invalid) {
            throw new IOException("This file could not be opened as a PDF.", invalid);
        }
    }

    private static PDRectangle pageBox(PDDocument document, int page) throws IOException {
        if (page < 1 || page > document.getNumberOfPages()) throw new IOException("That page is unavailable.");
        var box = document.getPage(page - 1).getCropBox();
        if (!Float.isFinite(box.getWidth()) || !Float.isFinite(box.getHeight()) || box.getWidth() <= 0 || box.getHeight() <= 0)
            throw new IOException("The page has invalid dimensions.");
        return box;
    }

    private static BufferedImage render(PDDocument document, int page, float zoom) throws IOException {
        var box = pageBox(document, page);
        var renderer = new PDFRenderer(document);
        return renderer.renderImage(page - 1, Math.min(zoom, MAX_SIDE / Math.max(box.getWidth(), box.getHeight())));
    }
}
