package sangitam.desktop.history;

import java.util.List;

/** One optional account history source. Catalog search alone never counts as a play. */
public interface HistorySource {
    String name();
    boolean available();
    String setupHint();
    List<ListenEvent> recent() throws Exception;
}
