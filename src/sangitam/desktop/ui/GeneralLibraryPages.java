package sangitam.desktop.ui;

import sangitam.desktop.model.LibraryCollections;
import sangitam.desktop.model.LibraryCollections.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Snapshot-backed presentation pages. Filtering/sorting belongs on a worker, not the EDT. */
final class GeneralLibraryPages {
    static final int PAGE_SIZE = 100;
    enum Order {
        RECENT("Recent"), ALPHABETICAL("Alphabetical"), PLAYED("Most played");
        private final String label;
        Order(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }
    record Request(String text, long folder, Order order, int offset, Kind kind) {
        Request(String text,long folder,Order order,int offset){this(text,folder,order,offset,null);}
        Request { text = LibraryEntry.lower(text.strip()); Objects.requireNonNull(order); if (offset < 0 || folder < 0) throw new IllegalArgumentException("Invalid library page"); }
    }
    record Page(List<LibraryEntry> entries, int total, int offset) {
        Page { entries = List.copyOf(entries); }
        boolean hasNext() { return offset + entries.size() < total; }
    }
    private GeneralLibraryPages() { }
    static Page query(List<LibraryEntry> entries, LibraryCollections collections, Request request) {
        return new Index(entries, collections).query(request);
    }

    /** One ordered snapshot per sort. Search and page changes reuse it until the data changes. */
    static final class Index {
        private final List<LibraryEntry> entries;
        private final LibraryCollections collections;
        private final Map<Key, LibraryEntry> byKey;
        private final Map<Order, List<LibraryEntry>> sorted = new java.util.concurrent.ConcurrentHashMap<>();

        Index(List<LibraryEntry> entries, LibraryCollections collections) {
            this.entries = entries;
            this.collections = collections;
            var keys = new HashMap<Key, LibraryEntry>();
            for (LibraryEntry entry : entries) keys.put(entry.key(), entry);
            byKey = Map.copyOf(keys);
        }

        LibraryEntry entry(Key key) { return byKey.get(key); }

        private List<LibraryEntry> ordered(Order sort) {
            return sorted.computeIfAbsent(sort, key -> {
                Comparator<LibraryEntry> alpha = Comparator.comparing(LibraryEntry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.kind().name()).thenComparing(entry -> entry.key().id());
                Comparator<LibraryEntry> order = switch (key) {
                    case ALPHABETICAL -> alpha;
                    case RECENT -> Comparator.comparingLong(LibraryEntry::recent).reversed().thenComparing(alpha);
                    case PLAYED -> Comparator.comparingLong(LibraryEntry::plays).reversed().thenComparing(alpha);
                };
                var ordered = new ArrayList<>(entries);
                ordered.sort(Comparator.<LibraryEntry, Boolean>comparing(entry -> !collections.pins().contains(entry.key())).thenComparing(order));
                return ordered;
            });
        }

        Page query(Request request) {
            Set<Key> pins = collections.pins();
            Set<Path> members = collections.folders().stream().filter(folder -> folder.id() == request.folder()).findFirst()
                .map(folder -> Set.copyOf(folder.tracks())).orElse(Set.of());
            var page = new ArrayList<LibraryEntry>(PAGE_SIZE);
            var tail = new ArrayList<LibraryEntry>(PAGE_SIZE);
            int total = 0;
            for (LibraryEntry entry : ordered(request.order())) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                if (request.kind() != null && entry.kind() != request.kind()) continue;
                boolean scope = request.folder() == 0 ? !request.text().isEmpty() || entry.parent() == 0 || pins.contains(entry.key())
                    : entry.parent() == request.folder() || entry.kind() == Kind.SONG && members.contains(entry.tracks().getFirst().path());
                if (!scope || !matches(entry, request.text())) continue;
                if (total % PAGE_SIZE == 0) tail.clear();
                tail.add(entry);
                if (total >= request.offset() && page.size() < PAGE_SIZE) page.add(entry);
                total++;
            }
            int offset = total == 0 ? 0 : Math.min(request.offset(), (total - 1) / PAGE_SIZE * PAGE_SIZE);
            return new Page(offset == request.offset() ? page : tail, total, offset);
        }

        private boolean matches(LibraryEntry entry, String text) {
            return text.isEmpty() || LibraryEntry.lower(entry.name() + " " + entry.detail() + " " + entry.kind()).contains(text)
                || entry.kind() == Kind.SONG && entry.tracks().getFirst().metadata().searchableText().contains(text);
        }
    }
}
