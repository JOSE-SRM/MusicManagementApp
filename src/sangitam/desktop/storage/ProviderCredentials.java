package sangitam.desktop.storage;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Central lookup for provider secrets; Windows Credential Manager wins over launch-time environment values. */
public final class ProviderCredentials {
    public static final String SPOTIFY_TOKEN = "spotify.access-token";
    public static final String YOUTUBE_KEY = "youtube.api-key";
    public static final String APPLE_TOKEN = "apple.developer-token";
    public static final String LASTFM_KEY = "lastfm.api-key";
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile CompletableFuture<Void> loading;

    private ProviderCredentials() { }

    public static String get(String credential, String environmentVariable) {
        String saved = CACHE.get(credential);
        if (saved != null && !saved.isBlank()) return saved;
        String environment = System.getenv(environmentVariable);
        return environment == null || environment.isBlank() ? null : environment;
    }

    /** Reads secrets off Swing's event thread so credential lookup never delays startup or navigation. */
    public static void preload(Consumer<Boolean> complete) {
        CompletableFuture<Void> current = loading;
        if (current == null) {
            synchronized (ProviderCredentials.class) {
                current = loading;
                if (current == null) {
                    loading = current = CompletableFuture.runAsync(() -> {
                        for (String key : new String[] {SPOTIFY_TOKEN, YOUTUBE_KEY, APPLE_TOKEN, LASTFM_KEY}) {
                            String secret = WindowsCredentialStore.read(key);
                            if (secret != null && !secret.isBlank()) CACHE.put(key, secret);
                        }
                    });
                }
            }
        }
        current.whenComplete((ignored, error) -> {
            if (complete != null) complete.accept(error == null);
        });
    }

    public static void remember(String credential, String secret) {
        if (secret == null || secret.isBlank()) CACHE.remove(credential);
        else CACHE.put(credential, secret);
    }
}
