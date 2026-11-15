package sangitam.desktop.discovery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import sangitam.desktop.library.ScoreWork;
import sangitam.desktop.library.WorkDetails;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Uses Mutopia's public catalogue and the licensed files linked by its edition pages. */
public final class MutopiaScores {
    private static final URI SITE = URI.create("https://www.mutopiaproject.org/");
    private static final URI MIRROR = URI.create("https://www.ibiblio.org/mutopia/");
    private static final Pattern MOVEMENT = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th) movement\\b");
    private static final Pattern PARTIAL = Pattern.compile("\\b(movement|excerpt|arranged|arrangement|transcription|selection|part)\\b");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build();
    private volatile Catalogue catalogue;

    private record Catalogue(URI base, String html) { }

    public record Item(String title, URI source, int movement, int rank) { }
    public record Download(int movement, URI source, byte[] pdf, byte[] midi) { }

    public List<Item> search(ScoreWork work) throws Exception {
        if (work.key().isBlank()) throw new IOException("Add the composer and work in Edit details before looking for a score.");
        Catalogue current = catalogue;
        if (current == null) {
            try { current = catalogue(SITE); }
            catch (IOException unavailable) { current = catalogue(MIRROR); }
            catalogue = current;
        }
        return match(current.html(), work, current.base());
    }

    private Catalogue catalogue(URI base) throws Exception {
        return new Catalogue(base, new String(read(base.resolve("piece-list.html"), 2_000_000), StandardCharsets.UTF_8));
    }

    static List<Item> match(String html, ScoreWork work) {
        return match(html, work, SITE);
    }

    private static List<Item> match(String html, ScoreWork work, URI base) {
        var candidates = new ArrayList<Item>();
        String surname = WorkDetails.fold(work.composer()).replaceFirst("^.* ", "");
        if (surname.isBlank()) return List.of();
        String catalogue = ScoreWork.catalogueKey(work.catalogue());
        String target = WorkDetails.fold(work.title());
        String expected = WorkDetails.fold(work.instrument()).split(" ")[0];
        if (expected.isBlank() && target.startsWith("piano ")) expected = "piano";
        for (Element row : Jsoup.parse(html, base.toString()).select("tr")) {
            var cells = row.select("td");
            if (cells.size() != 4) continue;
            var link = cells.get(1).selectFirst("a[href]");
            if (link == null || !link.attr("href").matches("cgibin/piece-info\\.cgi\\?id=\\d+")) continue;
            String credit = WorkDetails.fold(cells.getFirst().text());
            if (!credit.endsWith(" " + surname) && !credit.equals(surname)) continue;
            String name = WorkDetails.fold(link.text());
            String opus = ScoreWork.catalogueKey(cells.get(2).text());
            String instruments = WorkDetails.fold(cells.get(3).text());
            boolean exactTitle = target.equals(name) || name.length() >= 10 && target.contains(name) || target.length() >= 10 && name.contains(target);
            if (!catalogue.isBlank() ? !catalogue.equals(opus) : !exactTitle) continue;
            if (!expected.isBlank() && !(" " + instruments + " ").contains(" " + expected + " ")) continue;
            if (expected.equals("piano") && !instruments.equals("piano") && target.contains("piano sonata")) continue;
            if (name.matches(".*\\b(?:arranged|arrangement|transcription|excerpt)\\b.*")) continue;
            var numbered = MOVEMENT.matcher(name);
            int movement = numbered.find() ? Integer.parseInt(numbered.group(1)) : 0;
            if (movement == 0 && PARTIAL.matcher(name).find()) continue;
            int rank = (exactTitle ? 4 : 0) + (expected.equals(instruments) ? 2 : 0);
            candidates.add(new Item(link.text(), base.resolve(link.attr("href")), movement, rank));
        }
        var ranked = candidates.stream().sorted(Comparator.comparingInt(Item::rank).reversed()).toList();
        var complete = ranked.stream().filter(item -> item.movement() == 0).toList();
        if (!complete.isEmpty()) return List.of(complete.getFirst());
        var movements = new TreeMap<Integer, Item>();
        ranked.forEach(item -> movements.putIfAbsent(item.movement(), item));
        if (movements.isEmpty() || movements.lastKey() != movements.size()
            || work.movement() > movements.lastKey()) return List.of();
        // A movement by itself does not establish the rest of a multi-movement score.
        if (movements.size() == 1 || movements.size() > 12 || target.contains("pathetique") && movements.size() != 3) return List.of();
        return List.copyOf(movements.values());
    }

    public Download download(Item item) throws Exception {
        try { return downloadEdition(item); }
        catch (IOException unavailable) {
            if (!item.source().getHost().equals(SITE.getHost())) throw unavailable;
            URI mirror = MIRROR.resolve(item.source().getRawPath().substring(1) + "?" + item.source().getRawQuery());
            return downloadEdition(new Item(item.title(), mirror, item.movement(), item.rank()));
        }
    }

    private Download downloadEdition(Item item) throws Exception {
        var page = Jsoup.parse(new String(read(item.source(), 1_000_000), StandardCharsets.UTF_8), item.source().toString());
        URI pdf = linkedFile(page, "-a4.pdf");
        if (pdf == null) throw new IOException("Mutopia does not list a PDF for this edition.");
        URI midi = linkedFile(page, ".mid");
        return new Download(item.movement(), item.source(), read(pdf, 32 * 1024 * 1024),
            midi == null ? null : read(midi, 2 * 1024 * 1024));
    }

    private static URI linkedFile(Element page, String suffix) {
        for (var link : page.select("a[href]")) {
            String value = link.absUrl("href").replaceFirst("^http://(www\\.)?mutopiaproject.org/", SITE.toString())
                .replaceFirst("^http://www\\.ibiblio\\.org/mutopia/", MIRROR.toString());
            if (value.isEmpty()) continue;
            URI uri;
            try { uri = URI.create(value); } catch (IllegalArgumentException invalid) { continue; }
            if (official(uri) && (uri.getPath().startsWith("/ftp/") || uri.getPath().startsWith("/mutopia/ftp/"))
                && uri.getPath().endsWith(suffix)) return uri;
        }
        return null;
    }

    private static boolean official(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
            && ("www.mutopiaproject.org".equals(uri.getHost()) || "www.ibiblio.org".equals(uri.getHost())
            && uri.getPath().startsWith("/mutopia/"));
    }

    public static boolean isSource(String url) {
        return url.startsWith(SITE.toString()) || url.startsWith(MIRROR.toString());
    }

    private byte[] read(URI uri, int maxBytes) throws Exception {
        if (!official(uri)) throw new IOException("Unexpected sheet-music download address.");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("User-Agent", "Sangitam/0.2 (sheet music)").build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Mutopia returned HTTP " + response.statusCode() + ". Try again later.");
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("The edition exceeds the download size limit.");
            return bytes;
        }
    }
}
