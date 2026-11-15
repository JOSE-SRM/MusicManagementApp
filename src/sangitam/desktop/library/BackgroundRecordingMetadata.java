package sangitam.desktop.library;

import sangitam.desktop.discovery.AcoustIdLookup;
import sangitam.desktop.discovery.MusicBrainzMetadata;
import sangitam.desktop.discovery.MusicBrainzProvider;
import sangitam.desktop.discovery.RecordingLookup;
import sangitam.desktop.model.RecordingCandidate;
import sangitam.desktop.model.Track;
import sangitam.desktop.model.TrackMetadata.Field;
import sangitam.desktop.model.TrackMetadata.Origin;
import sangitam.desktop.model.TrackMetadata.Value;
import sangitam.desktop.storage.BackgroundMetadataStore;
import sangitam.desktop.storage.LibraryStore;
import sangitam.desktop.storage.RecordingMatchStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Resolves work metadata on the catalog worker. Recording fields require independent performance evidence. */
final class BackgroundRecordingMetadata {
    record Update(boolean changed, String warning, boolean musicBrainzFailed, boolean acoustIdFailed) {
        Update(boolean changed, String warning) { this(changed, warning, false, false); }
    }
    private final LibraryStore store;
    private final RecordingMatchStore matches;
    private final BackgroundMetadataStore metadata;
    private final MusicBrainzProvider provider = new MusicBrainzProvider();
    private final MusicBrainzMetadata musicBrainz = new MusicBrainzMetadata(provider);
    private final AcoustIdLookup acoustId = new AcoustIdLookup();

    BackgroundRecordingMetadata(LibraryStore store) {
        this.store = store;
        matches = new RecordingMatchStore(store);
        metadata = new BackgroundMetadataStore(store);
    }

    static boolean eligible(Track track, boolean online) {
        if (online) return RecordingLookup.missingTitle(track.title()) || RecordingLookup.missingLabel(track.artist())
            || track.metadata().text(Field.RECORDING_DATE).isBlank()
            || (track.genre().toLowerCase(Locale.ROOT).contains("classical") && track.metadata().text(Field.WORK).isBlank());
        return !track.metadata().text(Field.COMPOSER).isBlank()
            || track.path().toString().toLowerCase(Locale.ROOT).contains("beethoven");
    }

    Update update(Path path, boolean online, boolean refresh, String acoustIdKey) throws Exception {
        return update(path, online, online, refresh, acoustIdKey);
    }

    synchronized Update update(Path path, boolean online, boolean fingerprints, boolean refresh, String acoustIdKey)
        throws Exception {
        var context = matches.load(path);
        if (context == null || context.stamp().size() < 0 || !Files.isRegularFile(path)) return new Update(false, "");
        Track track = context.track();
        String input = input(context, online, acoustIdKey != null);
        if (!refresh && metadata.checked(path, context.stamp(), input) && (online || !fingerprints || acoustIdKey == null))
            return new Update(false, "");

        var fields = new EnumMap<Field, Value>(Field.class);
        StringBuilder sources = new StringBuilder();
        var references = WorkDetails.reference(track);
        boolean score = references.containsKey(Field.WORK);
        references.forEach((field, text) -> fields.put(field, new Value(text,
            score ? Origin.SCORE_REFERENCE : Origin.TITLE_HINT)));
        if (score) {
            sources.append(WorkDetails.referenceSource(references));
            ClassicalHints.enrich(path, track.title(), "Classical", track.metadata().fields()).forEach((field, value) -> {
                if (value.origin() == Origin.SCORE_REFERENCE) fields.put(field, value);
            });
        }
        if (track.metadata().text(Field.RECORDING_DATE).isBlank() && (online || !fields.isEmpty())
            && context.stamp().size() > 1024) {
            var dates = LibraryScanner.readDates(path);
            fields.putAll(dates);
            if (!dates.isEmpty()) sources.append(" · Embedded audio tags");
        }

        String warning = "";
        RecordingCandidate automatic = null;
        boolean fingerprint = false;
        String query = "";
        boolean musicBrainzFailed = false;
        boolean acoustIdFailed = false;
        if (online) {
            try {
                UUID recording = null;
                boolean exact = false;
                if (context.saved() != null && context.saved().stamp().equals(context.stamp())) {
                    recording = context.saved().id();
                    var candidate = musicBrainz.recording(recording);
                    var evidence = candidate == null ? null : RecordingLookup.compare(track, candidate);
                    exact = "AcoustID fingerprint".equals(context.saved().query())
                        || evidence != null && evidence.corroborated() && evidence.album();
                } else {
                    String title = RecordingLookup.initialTitle(track);
                    if (!title.isBlank()) {
                        query = RecordingLookup.query(title, track, true);
                        var corroborated = provider.lookupRecordings(query, refresh).stream()
                            .filter(candidate -> RecordingLookup.compare(track, candidate).corroborated()).toList();
                        if (corroborated.size() == 1) {
                            var best = corroborated.getFirst();
                            recording = best.id();
                            exact = RecordingLookup.compare(track, best).album();
                            if (exact) automatic = best;
                        }
                    }
                }
                var details = musicBrainz.resolve(track, recording, exact);
                add(fields, sources, details);
            } catch (InterruptedException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                musicBrainzFailed = true;
                warning = "MusicBrainz lookup paused. " + unavailable.getMessage();
            }
        }
        if (fingerprints && automatic == null && context.saved() == null) {
            boolean needsPerformance = track.metadata().text(Field.RECORDING_DATE).isBlank()
                && !fields.containsKey(Field.RECORDING_DATE);
            if (needsPerformance || !fields.containsKey(Field.WORK)) {
                if (acoustIdKey == null) {
                    if (warning.isBlank() && !fields.containsKey(Field.WORK))
                        warning = "Fingerprint fallback needs an AcoustID application key and Chromaprint fpcalc.";
                } else try {
                    var identified = acoustId.identify(path, acoustIdKey);
                    if (identified != null) {
                        var recognized = identified.recording();
                        if (recognized == null && online && !musicBrainzFailed)
                            recognized = musicBrainz.recording(identified.id());
                        if (recognized != null && (track.durationSeconds() == 0 || recognized.durationMillis() == 0
                            || Math.abs(track.durationSeconds() - recognized.durationMillis() / 1000.0) <= 12)) {
                            automatic = recognized;
                            fingerprint = true;
                            var recognizedTrack = new Track(path, recognized.title(), recognized.credit(), track.album(),
                                track.durationSeconds(), track.format(), track.genre(), track.metadata());
                            var recognizedWork = WorkDetails.reference(recognizedTrack);
                            var origin = recognizedWork.containsKey(Field.WORK) ? Origin.SCORE_REFERENCE : Origin.TITLE_HINT;
                            recognizedWork.forEach((field, text) -> fields.putIfAbsent(field, new Value(text, origin)));
                            if (online && !musicBrainzFailed)
                                add(fields, sources, musicBrainz.resolve(recognizedTrack, identified.id(), true));
                        }
                    }
                } catch (InterruptedException cancelled) {
                    throw cancelled;
                } catch (Exception unavailable) {
                    acoustIdFailed = true;
                    if (warning.isBlank()) warning = "AcoustID lookup paused. " + unavailable.getMessage();
                }
            }
        }
        String recordingDate = track.metadata().text(Field.RECORDING_DATE);
        if (recordingDate.isBlank() && fields.containsKey(Field.RECORDING_DATE))
            recordingDate = fields.get(Field.RECORDING_DATE).text();
        if (recordingDate.matches("^(?:18|19|20)\\d{2}.*")) {
            int year = Integer.parseInt(recordingDate.substring(0, 4));
            fields.put(Field.RECORDING_PERIOD, new Value((year / 10 * 10) + "s", Origin.TITLE_HINT));
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        var attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (attributes.size() != context.stamp().size() || attributes.lastModifiedTime().toMillis() != context.stamp().modifiedMillis())
            return new Update(false, "");
        synchronized (store) {
            if (!Objects.equals(context, matches.load(path))) return new Update(false, "");
            if (automatic != null && context.saved() == null) {
                if (fingerprint) matches.saveFingerprintMatch(context, automatic);
                else matches.save(context, automatic, null, true, query);
                context = matches.load(path);
            }
            String checked = input(context, online && !musicBrainzFailed && !acoustIdFailed, acoustIdKey != null && fingerprints);
            metadata.save(path, context.stamp(), checked, fields, sources.toString());
        }
        return new Update(true, warning, musicBrainzFailed, acoustIdFailed);
    }

    private static String input(RecordingMatchStore.Context context, boolean online, boolean fingerprint) {
        var track = context.track();
        String tags = track.metadata().fields().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
            .map(entry -> entry.getKey().name() + "=" + entry.getValue().text()).collect(java.util.stream.Collectors.joining("\n"));
        String identity = java.util.UUID.nameUUIDFromBytes((track.title() + "\n" + track.artist() + "\n"
            + track.album() + "\n" + tags + "\n" + track.durationSeconds()
            + "\n" + (context.saved() == null ? "" : context.saved().id())).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return "1:" + identity + "|" + online + ":" + fingerprint + ":2";
    }

    private static void add(EnumMap<Field, Value> fields, StringBuilder sources, MusicBrainzMetadata.Details details) {
        details.fields().forEach((field, text) -> fields.putIfAbsent(field, new Value(text, Origin.ONLINE)));
        if (!details.source().isBlank()) {
            if (!sources.isEmpty()) sources.append(" · ");
            sources.append(details.source());
        }
    }
}
