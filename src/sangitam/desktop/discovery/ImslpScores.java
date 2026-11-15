package sangitam.desktop.discovery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import sangitam.desktop.library.ScoreWork;
import sangitam.desktop.library.WorkDetails;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Resolves score editions through IMSLP's MediaWiki API and uses the provider's public download flow. */
public final class ImslpScores {
    private static final URI SITE = URI.create("https://imslp.org/");
    private static final Pattern PAGES = Pattern.compile("\\b(\\d+)\\s*pp\\.", Pattern.CASE_INSENSITIVE);
    private static final Pattern YEAR = Pattern.compile("\\b(18\\d{2}|19\\d{2}|20[0-2]\\d)\\b");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public record Edition(String title, String details, String file, URI link, int pages, int quality) {
        @Override public String toString() { return title + (details.isBlank() ? "" : " · " + details); }
    }

    public record Download(Edition edition, byte[] pdf) { }

    public List<Edition> search(ScoreWork work) throws Exception {
        if (work.key().isBlank()) return List.of();
        String surname = WorkDetails.fold(work.composer()).replaceFirst("^.* ", "");
        String title = WorkDetails.fold(work.title());
        String catalogue = WorkDetails.fold(work.catalogue());
        String terms = catalogue.isBlank() ? title : title.replaceFirst("\\s+(?:no |number )?\\d.*$", "")
            .replaceFirst("\\s+(?:in )?[a-g](?: flat| sharp)? (?:major|minor).*$", "")
            .replaceFirst("\\s+(?:op|bwv|k|rv|d|woo)\\s*\\d.*$", "");
        String indexNumber = work.catalogue().replaceAll("\\.\\s+", ".")
            .replaceFirst("(?i)^op(?:us)?\\s+(?=\\d)", "Op.").replaceFirst("(?i)^k\\s+(?=\\d)", "K.");
        var matches = api("action=query&list=search&srnamespace=0&srlimit=15&srsearch=" + encode(surname + " " + indexNumber + " " + terms));
        var query = matches.getAsJsonObject("query");
        if (query == null || !query.has("search")) return List.of();
        for (var item : query.getAsJsonArray("search")) {
            String candidate = item.getAsJsonObject().get("title").getAsString();
            if (!matchesWork(candidate, work, surname)) continue;
            var page = api("action=parse&redirects=1&prop=text&page=" + encode(candidate));
            var parse = page.getAsJsonObject("parse");
            if (parse == null || !parse.has("text")) continue;
            var editions = editions(parse.getAsJsonObject("text").get("*").getAsString());
            if (!editions.isEmpty()) return editions;
        }
        return List.of();
    }

    private static boolean matchesWork(String candidate, ScoreWork work, String surname) {
        int credit = candidate.lastIndexOf('(');
        if (credit < 0 || !WorkDetails.fold(candidate.substring(credit)).startsWith(surname + " ")) return false;
        String name = WorkDetails.fold(candidate.substring(0, credit));
        String wanted = WorkDetails.fold(work.title());
        if (!work.catalogue().isBlank()) {
            var namespaces = Pattern.compile("\\b(?:op(?:us)?|woo|bwv|kv?|rv|d|hwv|hob|s)\\s*\\d+[a-z]?(?:\\s+no\\s*\\d+)?\\b");
            var actual = namespaces.matcher(name);
            String key = ScoreWork.catalogueKey(work.catalogue());
            while (actual.find()) if (ScoreWork.catalogueKey(actual.group()).equals(key)) return true;
            return false;
        }
        return name.equals(wanted) || name.length() >= 12 && (wanted.startsWith(name + " ") || name.startsWith(wanted + " "));
    }

    static List<Edition> editions(String html) {
        var doc = Jsoup.parse(html, SITE.toString());
        var results = new ArrayList<Edition>();
        for (var card : doc.select("div.we")) {
            String metadata = card.text();
            String normalized = WorkDetails.fold(metadata);
            if (normalized.matches(".*\\b(?:arranger|transcription|non pd|not public domain|copyright restricted)\\b.*")) continue;
            if (!normalized.contains("copyright public domain") && !normalized.contains("copyright creative commons attribution")
                && !normalized.contains("copyright creative commons zero")) continue;
            for (var block : card.select("div[id^=IMSLP]")) {
                var link = block.selectFirst(".we_file_download a[href*=ImagefromIndex]");
                var file = block.selectFirst(".we_file_info2 a[href^=/wiki/File:]");
                if (link == null || file == null || !file.attr("title").toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) continue;
                String title = link.text();
                String folded = WorkDetails.fold(title);
                if (!folded.matches("(?:complete|full) score(?: (?:a4|letter|color|mono|preview))*")) continue;
                if (!link.absUrl("href").matches("https://imslp\\.org/wiki/Special:ImagefromIndex/\\d+")) continue;
                var count = PAGES.matcher(block.text());
                int pages = count.find() ? Integer.parseInt(count.group(1)) : 0;
                if (pages == 0 || pages > 360) continue;
                String detail = description(metadata);
                int rank = 30 + (normalized.contains("pdf typeset") ? 100 : 0) + (normalized.contains("urtext") ? 12 : 0)
                    + (folded.contains("mono") ? 5 : 0) - (normalized.contains("first edition") ? 20 : 0);
                var date = YEAR.matcher(detail);
                if (date.find()) rank += Math.clamp((Integer.parseInt(date.group(1)) - 1800) / 6, 0, 35);
                results.add(new Edition(title, detail, file.attr("title"), URI.create(link.absUrl("href")), pages, rank));
            }
        }
        return results.stream().distinct().sorted(Comparator.comparingInt(Edition::quality).reversed()).limit(12).toList();
    }

    private static String description(String text) {
        String publisher = text.replaceFirst("(?is)^.*?pub\\s*lisher\\s*\\.?\\s*info\\.\\s*", "");
        if (publisher.equals(text)) return "";
        String value = publisher.replaceFirst("(?is)\\s+Copyright.*$", "").strip();
        return value.length() <= 140 ? value : value.substring(0, 137) + "…";
    }

    public Download download(Edition edition) throws Exception {
        byte[] file = tryPdf(edition.link());
        if (file != null) return new Download(edition, file);
        JsonObject metadata;
        try { metadata = api("action=query&prop=imageinfo&iiprop=url%7Cmime%7Csize&titles=" + encode(edition.file())); }
        catch (IOException unavailable) { return null; }
        var query = metadata.getAsJsonObject("query");
        if (query == null || !query.has("pages")) return null;
        for (var value : query.getAsJsonObject("pages").asMap().values()) {
            var item = value.getAsJsonObject();
            if (!item.has("imageinfo")) continue;
            var info = item.getAsJsonArray("imageinfo").get(0).getAsJsonObject();
            if (!info.has("mime") || !info.get("mime").getAsString().equals("application/pdf") || info.get("size").getAsLong() > 64L * 1024 * 1024) continue;
            file = tryPdf(SITE.resolve(info.get("url").getAsString()));
            if (file != null) return new Download(edition, file);
        }
        return null;
    }

    private byte[] tryPdf(URI uri) throws Exception {
        try { return pdf(uri); } catch (IOException unavailable) { return null; }
    }

    private JsonObject api(String params) throws Exception {
        URI url = SITE.resolve("api.php?format=json&" + params);
        var response = request(url);
        try (var body = response.body()) {
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type").orElse("").contains("json"))
                throw new IOException("IMSLP’s catalogue is unavailable right now.");
            byte[] bytes = body.readNBytes(5_000_001);
            if (bytes.length > 5_000_000) throw new IOException("IMSLP’s edition list was too large to read.");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private byte[] pdf(URI url) throws Exception {
        for (int redirect = 0; redirect < 5 && trusted(url); redirect++) {
            var response = request(url);
            try (var body = response.body()) {
                if (response.statusCode() >= 300 && response.statusCode() < 400 && response.headers().firstValue("Location").isPresent()) {
                    url = url.resolve(response.headers().firstValue("Location").orElseThrow());
                    continue;
                }
                if (response.statusCode() != 200) return null;
                byte[] prefix = body.readNBytes(5);
                if (!new String(prefix, StandardCharsets.US_ASCII).equals("%PDF-")) return null;
                byte[] remainder = body.readNBytes(64 * 1024 * 1024 - 4);
                if (remainder.length + 5 > 64 * 1024 * 1024) return null;
                byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + remainder.length);
                System.arraycopy(remainder, 0, bytes, prefix.length, remainder.length);
                return bytes;
            }
        }
        return null;
    }

    private HttpResponse<java.io.InputStream> request(URI uri) throws Exception {
        return http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(16)).header("User-Agent", "Sangitam/0.2 (sheet music)")
            .GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static boolean trusted(URI uri) {
        String host = uri.getHost();
        return "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1 && host != null
            && (host.equals("imslp.org") || host.endsWith(".imslp.org"));
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
