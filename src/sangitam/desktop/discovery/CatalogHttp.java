package sangitam.desktop.discovery;

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

/** Shared HTTP plumbing: bounded requests, encoded queries, and safe user-facing failures. */
final class CatalogHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();

    private CatalogHttp() { }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static JsonObject get(String url, String header, String value) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12)).header("Accept", "application/json")
            .header("User-Agent", "Sangitam/0.3.0 (https://github.com/JOSE-SRM/MusicManagementApp)")
            .GET();
        if (header != null) request.header(header, value);
        HttpResponse<String> response;
        try { response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
        catch (IOException failure) { throw new IOException("Could not reach the provider. Check your connection."); }
        if (response.statusCode() == 401 || response.statusCode() == 403)
            throw new IOException("Access denied. Check this provider's credentials or permissions.");
        if (response.statusCode() == 429)
            throw new IOException("Provider rate limit reached. Try again later.");
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("Provider returned HTTP " + response.statusCode() + ".");
        try {
            JsonElement body = JsonParser.parseString(response.body());
            if (!body.isJsonObject()) throw new IllegalStateException("Expected an object");
            return body.getAsJsonObject();
        } catch (RuntimeException invalid) {
            throw new IOException("Provider returned an unreadable response.", invalid);
        }
    }

    static JsonObject object(JsonObject parent, String key) {
        JsonElement element = parent == null ? null : parent.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    static String string(JsonObject object, String key) {
        JsonElement element = object == null ? null : object.get(key);
        try { return element != null && !element.isJsonNull() ? element.getAsString() : ""; }
        catch (RuntimeException invalid) { return ""; }
    }
}
