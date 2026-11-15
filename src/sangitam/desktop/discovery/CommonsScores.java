package sangitam.desktop.discovery;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Arrays;
import java.util.Set;

/** Finds an optional archive PDF with explicit public-domain metadata on Wikimedia Commons. */
public final class CommonsScores {
    private static final String API = "https://commons.wikimedia.org/w/api.php?format=json&formatversion=2&action=query"
        + "&generator=search&gsrnamespace=6&gsrlimit=16&prop=imageinfo&iiprop=url%7Cmime%7Cextmetadata"
        + "&iiextmetadatafilter=LicenseShortName%7CLicense%7CCopyrighted&gsrsearch=";
    private static final Set<String> DOMAIN = Set.of("pd", "pdm", "public domain", "cc0", "cc0 1 0", "public domain mark 1 0");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Download(String title, URI source, byte[] pdf) { }

    public Download download(ScoreWork work) throws Exception {
        if (work.key().isBlank()) throw new IOException("Add the composer and work in Edit details before looking for a scan.");
        String composer = WorkDetails.fold(work.composer()).replaceFirst("^.* ", "");
        String title = WorkDetails.fold(work.title());
        String catalogue = ScoreWork.catalogueKey(work.catalogue());
        String keyword = Arrays.stream(title.split(" ")).filter(word -> word.length() >= 5)
            .filter(word -> !Set.of("piano", "sonata", "concerto", "major", "minor", "string", "symphony").contains(word))
            .max(java.util.Comparator.comparingInt(String::length)).orElse(WorkDetails.fold(work.catalogue()));
        if (keyword.isBlank()) throw new IOException("A distinctive work name or catalogue number is required to find an archive scan.");
        var url = URI.create(API + URLEncoder.encode(composer + " " + keyword + " filetype:pdf", StandardCharsets.UTF_8));
        var body = JsonParser.parseString(new String(read(url, 1_000_000), StandardCharsets.UTF_8)).getAsJsonObject();
        var query = body.getAsJsonObject("query");
        if (query == null || !query.has("pages")) throw noScan();
        for (var item : query.getAsJsonArray("pages")) {
            var page = item.getAsJsonObject();
            String label = text(page, "title").replaceFirst("^File:", "");
            String folded = WorkDetails.fold(label);
            if (!folded.contains(composer) || !folded.matches(".*\\b(?:score|sonata|concerto|symphony|music|opus|op|bwv)\\b.*")
                || folded.matches(".*\\b(?:arrangement|arranged|excerpt|transcription|movement|part)\\b.*")) continue;
            if (!catalogue.isBlank()) {
                String compact = ScoreWork.catalogueKey(folded.replaceFirst("^.*?(?=\\b(?:op(?:us)?|woo|bwv|kv?|rv|d|hwv) ?\\d)", ""));
                if (!compact.matches(java.util.regex.Pattern.quote(catalogue) + "(?:[^0-9].*)?")) continue;
            } else if (!folded.contains(title)) continue;
            if (!page.has("imageinfo")) continue;
            var images = page.getAsJsonArray("imageinfo");
            if (images.isEmpty()) continue;
            var info = images.get(0).getAsJsonObject();
            if (!"application/pdf".equals(text(info, "mime"))) continue;
            var metadata = info.getAsJsonObject("extmetadata");
            if (metadata == null || !(DOMAIN.contains(WorkDetails.fold(metadata(metadata, "License")))
                || DOMAIN.contains(WorkDetails.fold(metadata(metadata, "LicenseShortName"))))) continue;
            if (metadata(metadata, "Copyrighted").equalsIgnoreCase("true")) continue;
            URI file = URI.create(text(info, "url"));
            URI source = URI.create(text(info, "descriptionurl"));
            if (!trusted(file, "upload.wikimedia.org") || !file.getPath().startsWith("/wikipedia/commons/")
                || !trusted(source, "commons.wikimedia.org")) continue;
            return new Download(label, source, read(file, 32 * 1024 * 1024));
        }
        throw noScan();
    }

    private static IOException noScan() { return new IOException("No matching public-domain scan found on Wikimedia Commons."); }

    private static String metadata(JsonObject object, String key) {
        return object.has(key) ? text(object.getAsJsonObject(key), "value") : "";
    }

    private static String text(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsString() : ""; }

    private static boolean trusted(URI url, String host) {
        return "https".equals(url.getScheme()) && host.equals(url.getHost()) && url.getUserInfo() == null && url.getPort() == -1;
    }

    private byte[] read(URI url, int maxBytes) throws Exception {
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(25)).header("User-Agent", "Sangitam/0.2 (desktop sheet-music viewer)").build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var stream = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Wikimedia Commons returned HTTP " + response.statusCode() + ".");
            byte[] bytes = stream.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("The archive scan exceeds the download size limit.");
            return bytes;
        }
    }
}
