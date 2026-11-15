package sangitam.desktop.discovery;

import sangitam.desktop.library.ScoreWork;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Verified CC0 engravings from Noctave's free score catalogue. */
public final class NoctaveScores {
    private static final String HOST = "noctavepiano.com";
    private static final String APPASSIONATA = "beethoven-sonata-no-23-appassionata-op-57-complete";
    private static final int PDF_LIMIT = 32 * 1024 * 1024;

    private NoctaveScores() { }

    public record Download(byte[] pdf, URI source) { }

    public static URI source(ScoreWork work) {
        return work.key().equals("ludwig van beethoven|op57")
            ? URI.create("https://" + HOST + "/en/free-library/" + APPASSIONATA + "/") : null;
    }

    public static Download download(ScoreWork work) throws IOException, InterruptedException {
        var source = source(work);
        if (source == null) throw new IOException("No verified Noctave edition for this work.");
        var file = URI.create("https://" + HOST + "/bibliotheque-libre/" + APPASSIONATA + "/" + APPASSIONATA + ".pdf?download=1");
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        var request = HttpRequest.newBuilder(file).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Sangitam/1.0 (desktop sheet music)")
            .header("Accept", "application/pdf").GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() != 200 || !HOST.equalsIgnoreCase(response.uri().getHost()))
                throw new IOException("Noctave did not return the engraved PDF.");
            var bytes = body.readNBytes(PDF_LIMIT + 1);
            if (bytes.length < 5 || bytes.length > PDF_LIMIT
                || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw new IOException("Noctave did not return the engraved PDF.");
            return new Download(bytes, source);
        }
    }
}
