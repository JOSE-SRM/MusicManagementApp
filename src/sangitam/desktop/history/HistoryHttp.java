package sangitam.desktop.history;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Bounded provider requests; failures never expose credentials or full request URLs. */
final class HistoryHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).build();

    private HistoryHttp() { }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static JsonObject get(String url, String authorization) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15)).header("Accept", "application/json")
            .header("User-Agent", "Sangitam/0.3 (https://github.com/JOSE-SRM/MusicManagementApp)")
            .GET();
        if (authorization != null) request.header("Authorization", authorization);
        HttpResponse<String> response;
        try { response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException failure) { throw new IOException("Could not reach the listening service."); }
        if (response.statusCode() == 401 || response.statusCode() == 403)
            throw new IOException("Access denied. Check the account, token, and permissions.");
        if (response.statusCode() == 429) throw new IOException("Rate limit reached. Try again later.");
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Service returned HTTP " + response.statusCode() + ".");
        try {
            JsonElement body = JsonParser.parseString(response.body());
            if (!body.isJsonObject()) throw new IllegalStateException();
            return body.getAsJsonObject();
        } catch (RuntimeException error) { throw new IOException("Service returned unreadable data."); }
    }

    static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static String string(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        try { return element == null || element.isJsonNull() ? "" : element.getAsString(); }
        catch (RuntimeException invalid) { return ""; }
    }

    static long number(JsonObject parent, String key) {
        try { return parent.get(key).getAsLong(); }
        catch (RuntimeException invalid) { return 0; }
    }

    static java.net.URI safeLink(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) ? uri : null;
        } catch (RuntimeException invalid) { return null; }
    }
}
