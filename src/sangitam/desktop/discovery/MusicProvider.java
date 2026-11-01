package sangitam.desktop.discovery;

import java.util.List;

/** A searchable catalog, deliberately separate from the local audio player. */
public interface MusicProvider {
    String name();
    String setupHint();
    boolean available();
    List<CatalogTrack> search(String query) throws Exception;
}
