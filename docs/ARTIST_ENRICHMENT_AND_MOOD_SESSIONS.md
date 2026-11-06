# Artist enrichment and mood sessions

Try **Library → General → Artists** or click a name in the playbar. On **Listen Now**, use **Deep analyse library**, then **Build my journey**. The two features share the catalog but have different data flows. Artist enrichment uses public artist names over HTTPS. Music analysis and listening context stay on the device.

## From a credit to an artist profile

1. [ArtistPanel](../src/sangitam/desktop/ui/ArtistPanel.java) starts a Swing worker when you open an artist. It reads the locally saved preference and checks for a cached profile. Opening another artist cancels the previous load, and the UI ignores results from obsolete workers.
2. [ArtistEnrichmentClient](../src/sangitam/desktop/discovery/ArtistEnrichmentClient.java) asks English Wikipedia for the artist title, follows encyclopedia redirects, and checks the lead for music roles. A search fallback requires a single name-compatible music result among the returned candidates. An ambiguous name can be retried using **Refresh / match…** with a fuller name. The original library credit remains the alias being matched.
3. The client formats brief excerpts as **About**, **Music and recordings**, and **Life** when the source has suitable sections. The biography starts with a few lines and **Read more** reveals the rest. The source link provides the full article and its attribution. For the portrait the client checks the Commons image licence and downloads an eligible thumbnail, then resizes it to a 320-pixel PNG. Both public requests have response-size limits. [MediaWiki TextExtracts](https://www.mediawiki.org/wiki/Extension:TextExtracts), [PageImages](https://www.mediawiki.org/wiki/Extension:PageImages), [Imageinfo](https://www.mediawiki.org/wiki/API:Imageinfo).
4. [ArtistEnrichmentStore](../src/sangitam/desktop/storage/ArtistEnrichmentStore.java) commits the portrait, biography, attribution, and alias. A refresh failure keeps previous data. **Online profile** controls automatic lookups and persists in SQLite. **Refresh / match…** can request a one-off lookup. **Edit artist profile…** takes precedence over cached details. A cached profile is eligible for refresh after 30 days. A failed lookup is throttled for an hour while the app stays open.

Artist matching uses names and role evidence, so two musicians with the same name can still need a human choice. The app avoids automatically selecting from ambiguous search results. Profiles are fetched when opened, which keeps a library import from issuing many online requests. Only the public name and role are used for lookup. The app reads and stores local audio information separately. [Wikimedia API access policy](https://www.mediawiki.org/wiki/Wikimedia_APIs/Access_policy).

### Why a merge changes catalog rows

The name printed on the artist page is only one use of artist identity. Library grouping, album pins, indexed search and listening totals also use names. For a resolved alias, the store updates affected track credits, including composer and conductor fields, rebuilds search text, and transfers pins and listening credit keys in one transaction. Existing manual profiles are retained. A canonical name that already has manual profile data takes precedence when two profiles are merged.

Track paths remain the keys for playlists, playback memory and song pins. That lets **Beethoven → Ludwig van Beethoven** change the catalog labels without moving media or changing playlist membership. A scan, manual detail edit, or file detail restoration applies known aliases again. Opening the canonical page offline uses the same cached entry.

## From samples to a listening journey

[AudioAnalyzer](../src/sangitam/desktop/library/AudioAnalyzer.java) uses Java Sound with MP3SPI to read PCM. It reads up to 90 seconds for short tracks and up to 180 seconds for longer ones. Playback and analysis have separate decoders. A file can play through VLC and still have pending analysis if Java Sound cannot decode it.

| Signal | How Sangitam estimates it |
|---|---|
| RMS, peak, dynamics | Measure amplitude and compare the 10th and 90th percentiles of the short-window amplitude envelope. The level units are dBFS. |
| Tempo | Look for repeating rises in the amplitude envelope. Weak or short observations leave BPM uncertain. |
| Brightness and key | Use a Hann-windowed FFT to estimate spectral centroid and distribute local peaks into 12 pitch classes. Compare the resulting chroma with major and minor templates. |
| Mood palette | Combine key mode and tempo evidence with energy and activity to derive arousal, valence, and a rough palette. The planner also reads instrumentation from tags and editable metadata. |

[SpectralFeatures](../src/sangitam/desktop/library/SpectralFeatures.java) holds the signal calculations. Listen Now reports analysis coverage and **Edit details → Analysis** shows the first selected recording’s estimates. User corrections to energy and movement stay separate from the stored measurements. [AudioFeatureStore](../src/sangitam/desktop/storage/AudioFeatureStore.java) versions the feature payload and checks size and modification time before writing, which protects a replacement file from a late worker result. [LibraryService](../src/sangitam/desktop/library/LibraryService.java) publishes coverage from the background job.

A key or tempo estimate can miss an expressive performance, changing tempo, a modulation, or the contrast between movements in one file. A dBFS estimate describes the sampled amplitude. The app uses metadata for instrumentation and does not yet classify instruments or vocals from audio. It leaves unsupported recordings eligible for sessions with less evidence.

### Listening context and taste

[ListeningClock](../src/sangitam/desktop/history/ListeningClock.java) measures elapsed playback while the player advances. The playback-memory worker records those intervals in `listening_windows` alongside artist totals. Skipping the playhead and pausing do not add the skipped or paused time. [TasteStore](../src/sangitam/desktop/storage/TasteStore.java) retains up to 180 days of context and groups older windows by track and hour when loading the context.

[SessionContext](../src/sangitam/desktop/session/SessionContext.java) first checks recent windows from the last 90 minutes and stops at gaps greater than 30 minutes. A recording from yesterday can contribute to an older pattern, and never counts as fresh playback. If there is no usable fresh evidence, the app weights the device’s own listening at similar hours, weekdays, days of the month, and months. Auto can choose a suggested starting energy and valence. The user still chooses the destination energy and can turn off context or select a mood directly.

Taste ratings use five levels from **Avoid** through **Love**. Open **Taste profile** on a song, album or artist, or rate a recording from the journey. Ratings are absolute preferences and can be reset to neutral. They are separate from song likes and pins. **Avoid** excludes a track from automatic selection. Other ratings influence selection for tracks and, more lightly, for related credits and genres. Manual playback and playlist additions are available independently of these preferences.

### Follow one Build click

```mermaid
flowchart LR
    A[Shape the session] --> B[MainFrame captures request]
    B --> C[Worker loads listening context]
    C --> D[SessionPlanner scores local recordings]
    D --> E[Editable journey draft]
    E --> F[Play journey]
    E --> G[Save regular playlist]
```

[SessionPlanner](../src/sangitam/desktop/session/SessionPlanner.java) advances along the requested energy arc using accumulated track duration. It scores candidates using energy, mood, intent, genre, instrumentation tags, play count, and taste, then tries to fill the requested time with variety. Each choice includes a reason. [SessionQueuePanel](../src/sangitam/desktop/ui/SessionQueuePanel.java) shows the resulting contour and offers a similar-energy replacement, manual edits, playback and saving. [MainFrame](../src/sangitam/desktop/ui/MainFrame.java) checks file availability and rejects obsolete build results. Building a draft while a queue is playing lets the user edit it independently and decide when to start it.

## Read the same measurements in Rediscover

[ListeningInsightsStore](../src/sangitam/desktop/storage/ListeningInsightsStore.java) reads the durable per-path playback totals in `listening_seconds`. The same read transaction joins the current genre, period, and mood estimate and groups `listens` by source. Changes to tags or a fresh analysis can therefore change how previously measured time is grouped. Removed recordings keep a separate bucket, and old feature versions or file stamps put recordings in the pending bucket. Recorded plays have a separate unit because a provider event alone does not report elapsed playback in Sangitam.

[ListeningInsightsPanel](../src/sangitam/desktop/ui/ListeningInsightsPanel.java) loads this report on demand. The time bars share the same local total and fold small categories into Other. The tab uses saved data and makes no provider request. Optional history sync remains on Rediscover's existing controls.

## Verification and remaining work

The headless suite covers audio fixtures, stale feature writes, alias propagation and profile caching, timestamps and taste persistence, and Swing callback routing. The live public Beethoven lookup verified the canonical name, music and life sections, and a cached-size PNG from Commons. The offscreen preview exercised the updated session layout. Interactive library animation, actual music playback, Windows media integration, and mood quality on a representative music library still need manual checks.

Possible next stages are calibrated audio classification across more formats, time range filters for analytics, source-aware discovery and playlists, and Windows packaging. The handoff tracks what is ready for each stage. [Project handoff](PROJECT_HANDOFF.md).
