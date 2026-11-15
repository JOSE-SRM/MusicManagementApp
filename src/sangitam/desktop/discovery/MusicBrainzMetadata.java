package sangitam.desktop.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import sangitam.desktop.library.WorkDetails;
import sangitam.desktop.model.RecordingCandidate;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** MusicBrainz work and recording relations with separate composition and performance evidence. */
public final class MusicBrainzMetadata {
    public record Details(Map<Field,String> fields, String source) { }
    private final MusicBrainzProvider provider;
    private final LinkedHashMap<UUID, JsonObject> recordings = new LinkedHashMap<>();
    private final LinkedHashMap<String, JsonObject> workSearch = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, JsonObject> works = new LinkedHashMap<>();

    public MusicBrainzMetadata(MusicBrainzProvider provider) { this.provider = provider; }

    public RecordingCandidate recording(UUID id) throws Exception {
        var container = new JsonObject();
        var array = new JsonArray();
        array.add(readRecording(id));
        container.add("recordings", array);
        return MusicBrainzProvider.parseRecordings(container).stream().findFirst().orElse(null);
    }

    public Details resolve(Track local, UUID recording, boolean samePerformance) throws Exception {
        var fields = new EnumMap<Field,String>(Field.class);
        String source = "";
        if (recording != null) {
            JsonObject details = readRecording(recording);
            if (samePerformance) fields.putAll(performance(details));
            var linkedWorks = objects(details, "relations").stream()
                .filter(relation -> "performance".equals(CatalogHttp.string(relation, "type")))
                .map(relation -> CatalogHttp.object(relation, "work"))
                .filter(work -> id(work) != null).toList();
            var plausible = samePerformance && linkedWorks.size() == 1 ? linkedWorks
                : linkedWorks.stream().filter(work -> workTitleAgrees(local, work)).toList();
            if (plausible.size() == 1) {
                UUID workId = id(plausible.getFirst());
                JsonObject work = readWork(workId);
                if (samePerformance || workAgrees(local, work)) {
                    fields.putAll(compositionWithParent(work));
                    source = "https://musicbrainz.org/work/" + workId;
                }
            }
            if (source.isEmpty() && !fields.isEmpty()) source = "https://musicbrainz.org/recording/" + recording;
        }
        if (!fields.containsKey(Field.WORK)) {
            String title = local.metadata().text(Field.WORK);
            if (title.isBlank()) title = RecordingLookup.initialTitle(local);
            String composer = local.metadata().text(Field.COMPOSER);
            if (!title.isBlank() && !composer.isBlank()) {
                String query = "work:\"" + quote(title) + "\" AND artist:\"" + quote(composer) + "\"";
                JsonObject response = workSearch.get(query);
                if (response == null) {
                    response = provider.workSearch(query);
                    cache(workSearch, query, response);
                }
                var candidates = objects(response, "works").stream()
                    .filter(work -> id(work) != null && workTitleAgrees(local, work)).toList();
                if (candidates.size() == 1) {
                    UUID workId = id(candidates.getFirst());
                    JsonObject detail = readWork(workId);
                    if (workAgrees(local, detail)) {
                        fields.putAll(compositionWithParent(detail));
                        source = "https://musicbrainz.org/work/" + workId;
                    }
                }
            }
        }
        return new Details(Map.copyOf(fields), source);
    }

    private JsonObject readRecording(UUID id) throws Exception {
        JsonObject value = recordings.get(id);
        if (value == null) {
            value = provider.recordingDetails(id);
            cache(recordings, id, value);
        }
        return value;
    }

    private JsonObject readWork(UUID id) throws Exception {
        JsonObject value = works.get(id);
        if (value == null) {
            value = provider.workDetails(id);
            cache(works, id, value);
        }
        return value;
    }

    private static <K> void cache(LinkedHashMap<K,JsonObject> cache, K key, JsonObject result) {
        cache.put(key, result);
        if (cache.size() > 64) cache.pollFirstEntry();
    }

    private static String quote(String value) {
        return value.substring(0, Math.min(150, value.length())).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static boolean workAgrees(Track local, JsonObject candidate) {
        if (!workTitleAgrees(local, candidate)) return false;
        String localComposer = WorkDetails.fold(local.metadata().text(Field.COMPOSER));
        if (localComposer.isBlank()) return false;
        var names = composers(candidate);
        if (WorkDetails.fold(String.join("; ", names)).equals(localComposer)) return true;
        return names.stream().map(WorkDetails::fold).anyMatch(composer -> composer.equals(localComposer)
            || java.util.Arrays.stream(composer.split(" ")).sorted().toList()
                .equals(java.util.Arrays.stream(localComposer.split(" ")).sorted().toList())
            || (" " + composer + " ").contains(" " + localComposer + " ")
            || (" " + localComposer + " ").contains(" " + composer + " "));
    }

    private static boolean workTitleAgrees(Track local, JsonObject candidate) {
        String candidateTitle = WorkDetails.fold(CatalogHttp.string(candidate, "title"));
        String knownWork = WorkDetails.fold(local.metadata().text(Field.WORK));
        String rawTitle = WorkDetails.fold(local.title());
        String opus = WorkDetails.fold(local.metadata().text(Field.OPUS));
        boolean exact = candidateTitle.length() >= 5 && (candidateTitle.equals(knownWork) || candidateTitle.equals(rawTitle));
        boolean explicitWorkPhrase = candidateTitle.split(" ").length >= 4
            && (" " + rawTitle + " ").contains(" " + candidateTitle + " ");
        boolean catalogue = !opus.isBlank() && (" " + candidateTitle + " ").contains(" " + opus + " ");
        if (catalogue && !exact) {
            // A shared opus can contain multiple works. Require the work title or its distinctive catalogue wording.
            if (!knownWork.isEmpty()) catalogue = candidateTitle.startsWith(knownWork + " ") || knownWork.startsWith(candidateTitle + " ");
            else catalogue = rawTitle.startsWith(candidateTitle + " ");
        }
        return exact || explicitWorkPhrase || catalogue;
    }

    private Map<Field,String> compositionWithParent(JsonObject work) throws Exception {
        var movement = composition(work);
        var parents = objects(work, "relations").stream()
            .filter(relation -> "parts".equals(CatalogHttp.string(relation, "type"))
                && "backward".equals(CatalogHttp.string(relation, "direction")))
            .map(relation -> id(CatalogHttp.object(relation, "work"))).filter(java.util.Objects::nonNull).distinct().toList();
        if (parents.size() != 1) return movement;
        var parent = composition(readWork(parents.getFirst()));
        String movementTitle = movement.remove(Field.WORK);
        String movementKey = movement.remove(Field.WORK_KEY);
        movement.remove(Field.COMPOSITION_DATE);
        if (movementTitle != null) parent.put(Field.MOVEMENT, movementTitle);
        if (movementKey != null) parent.put(Field.KEY, movementKey);
        movement.forEach(parent::putIfAbsent);
        return parent;
    }

    static Map<Field,String> composition(JsonObject work) {
        String title = CatalogHttp.string(work, "title");
        // Use the listed work key. A work-level key is never promoted to a movement key here.
        var fields = WorkDetails.fromTitle(title, String.join("; ", composers(work)), false);
        for (var attribute : objects(work, "attributes")) {
            String type = CatalogHttp.string(attribute, "type");
            String value = CatalogHttp.string(attribute, "value");
            if (value.isBlank()) continue;
            if (type.equalsIgnoreCase("Key")) fields.put(Field.WORK_KEY, value);
            if (type.equalsIgnoreCase("Opus number")) fields.put(Field.OPUS, value);
        }
        var dates = new TreeSet<String>();
        for (var relation : objects(work, "relations")) {
            if (!"composer".equals(CatalogHttp.string(relation, "type")) || hasAttribute(relation, "additional"))
                continue;
            String start = CatalogHttp.string(relation, "begin");
            String end = CatalogHttp.string(relation, "end");
            boolean hasStart = start.matches("\\d{4}(?:-\\d{2}){0,2}");
            boolean hasEnd = end.matches("\\d{4}(?:-\\d{2}){0,2}");
            if (hasStart) dates.add(hasEnd && !end.equals(start) ? start + "–" + end : start);
            else if (hasEnd) dates.add(end);
        }
        if (dates.size() == 1) fields.put(Field.COMPOSITION_DATE, dates.getFirst());
        return fields;
    }

    static Map<Field,String> performance(JsonObject recording) {
        var fields = new EnumMap<Field,String>(Field.class);
        var credits = new EnumMap<Field,TreeSet<String>>(Field.class);
        var dates = new TreeSet<String>();
        for (var relation : objects(recording, "relations")) {
            String type = CatalogHttp.string(relation, "type");
            String artist = CatalogHttp.string(CatalogHttp.object(relation, "artist"), "name");
            if (!artist.isBlank()) {
                Field field = switch (type) {
                    case "conductor" -> Field.CONDUCTOR;
                    case "orchestra" -> Field.ORCHESTRA;
                    case "performer", "instrument", "vocal" -> hasAttribute(relation, "solo") ? Field.SOLOIST : Field.PERFORMERS;
                    default -> null;
                };
                if (field != null) credits.computeIfAbsent(field, ignored -> new TreeSet<>()).add(artist);
            }
            if (Set.of("recorded at", "recorded in").contains(type)) {
                String begin = CatalogHttp.string(relation, "begin");
                String end = CatalogHttp.string(relation, "end");
                if (begin.matches("\\d{4}(?:-\\d{2}){0,2}"))
                    dates.add(end.isBlank() || end.equals(begin) ? begin : begin + "–" + end);
            }
        }
        credits.forEach((field, names) -> fields.put(field, String.join("; ", names)));
        if (dates.size() == 1) fields.put(Field.RECORDING_DATE, dates.getFirst());
        String firstRelease = CatalogHttp.string(recording, "first-release-date");
        if (firstRelease.matches("\\d{4}(?:-\\d{2}){0,2}")) fields.put(Field.RELEASE_DATE, firstRelease);
        return fields;
    }

    private static boolean hasAttribute(JsonObject relation, String name) {
        var attributes = relation.get("attributes");
        if (attributes == null || !attributes.isJsonArray()) return false;
        for (var value : attributes.getAsJsonArray())
            if (value.isJsonPrimitive() && name.equalsIgnoreCase(value.getAsString())) return true;
        return false;
    }

    private static Set<String> composers(JsonObject work) {
        var names = new TreeSet<String>();
        for (var relation : objects(work, "relations")) {
            if ("composer".equals(CatalogHttp.string(relation, "type"))) {
                String name = CatalogHttp.string(CatalogHttp.object(relation, "artist"), "name");
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }

    private static UUID id(JsonObject object) {
        try { return UUID.fromString(CatalogHttp.string(object, "id")); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    static java.util.List<JsonObject> objects(JsonObject object, String field) {
        var items = new ArrayList<JsonObject>();
        var array = object.get(field);
        if (array != null && array.isJsonArray()) {
            for (var item : array.getAsJsonArray()) if (item.isJsonObject()) items.add(item.getAsJsonObject());
        }
        return items;
    }
}
