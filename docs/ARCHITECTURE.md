# Sangitam architecture

Sangitam keeps a small, explainable separation between the Swing interface and the work behind it. The working journey is **choose a file or folder → scan metadata and audio → save tags → build an editable session → play**.

## How the pieces connect

```text
SangitamApp
  └─ MainFrame (navigation and shared state wiring)
       ├─ Swing panels: Listening rooms, Now Playing, Session recap, Search, Discover, Library
       ├─ DiscoverPanel → MusicProvider adapters → official catalog APIs
       ├─ LibraryService
       │    ├─ LibraryScanner → Track
       │    ├─ AudioAnalyzer → TrackTags
       │    └─ LibraryStore → local SQLite file
       ├─ SessionPlanner → chosen tracks with reasons
       ├─ ListeningMoment → saved title, note, room, and ordered local paths
       └─ PlayerService → JavaFxPlayerService → local audio file
```

`MainFrame` owns one `LibraryService` and one `PlayerService`. It sends the same track list to the dashboard, search panel and library panel. These panels do not contain their own sample collections or SQL. A double-click or Enter action sends a `Track` back to `MainFrame`, which asks the player to open it. The player sends an immutable `PlaybackState` back to the Swing player bar.

`Track` is a small record containing a normalized file path, title, artist, album, duration, format and optional genre. The path is the database key, so importing the same file again updates one row rather than creating a duplicate. The scanner accepts MP3 and WAV files, reads embedded metadata with Jaudiotagger, and falls back to the filename when tags are empty. It does not follow directory symlinks while walking a folder.

`AudioAnalyzer` decodes selected PCM passages through Java Sound (with MP3SPI for MP3), estimates loudness, short-term event density, and envelope variation, then saves numeric energy and movement scores. These measurements are rough audio traits, not mood or genre labels. The library UI shows them as estimates; listeners can correct genre, energy, and movement, and corrected tags are kept across rescans. `TrackTags` records analysis validity and source-file size/time so changed files can be reanalyzed. Analysis version changes invalidate old automatic profiles. Play counts are saved after confirmed playback.

`SessionPlanner` scores candidate tracks against the requested energy, intent, genre tags and local play counts. It adds artist variety, avoids duplicate file paths, and tries to fit the requested duration. Every selected track includes a human-readable reason. The queue UI can reorder, remove or add tracks; `MainFrame` routes play, next, previous and end-of-track events through that queue.

`LibraryStore` is the only class that knows SQLite. `AppPaths` locates the database in the user's application-data area. Playlist and listening-moment item rows have cascading foreign keys to their owning collection; their file paths remain independent so unavailable local files can be reported without erasing a saved queue. Saved listening moments store the listener's title, optional note, chosen room, and ordered track paths; they never copy or upload audio. `TrackSearch` is a pure function over the imported library, so its filtering rules can be tested separately from Swing.

`DiscoverPanel` is a separate search path for online catalogs. Each `MusicProvider` adapter reads only catalog metadata and constructs a link on the provider's official domain. MusicBrainz works without a credential and rate-limits requests; the other adapters read environment variables at startup. A `CatalogTrack` is intentionally distinct from the local `Track`, so online results cannot accidentally enter local playback or the tag-based session queue. No provider credentials are persisted, and the UI explains that results open in their provider.

## Threading

Swing components are created and updated on the Event Dispatch Thread. `LibraryService` uses `SwingWorker` for initial database loading, folder scanning, audio analysis and database writes; it publishes progress and final catalog snapshots back on the Event Dispatch Thread. Old libraries are analyzed after their track list is already visible. Session building and file-existence checks run in another worker. Search filtering runs in a debounced `SwingWorker`. Online HTTP searches and browser launches also run in `SwingWorker`s with timeouts. Library and soundscape animations use `javax.swing.Timer`.

Audio decoding uses JavaFX Media on its own application thread. `JavaFxPlayerService` is the only class that imports JavaFX. It starts that thread in the background when the player service is created, turns commands into JavaFX work, and returns immutable state to Swing with `SwingUtilities.invokeLater`. Embedded cover art is decoded on a `SwingWorker`, not the Event Dispatch Thread. The ambient scene animates only during playback and can be paused with Reduce motion. The visible interface still uses only Swing components. The adapter can be replaced if another decoder proves better for later format support or packaging.

## Presenting the code

1. Start at `SangitamApp`: install the theme and create `MainFrame` on the Event Dispatch Thread.
2. Show `MainFrame`: one place wires screens, the library and the player together.
3. Import a test folder: `ImportMediaDialog` passes paths to `LibraryService`; `LibraryScanner` reads metadata; `AudioAnalyzer` measures sound; `LibraryStore` saves both.
4. Search: `SearchPanel` asks `TrackSearch` to filter the shared track list.
5. Play a track: a panel sends `Track` to `MainFrame`, then `PlayerService` reports a `PlaybackState` to `PlayerBar`.
6. Shape a listening room: `SessionPlanner` chooses tracks with reasons; edit the Swing route, enter Now Playing, and show how next and previous follow its order.
7. Finish a journey: the recap saves a `ListeningMoment` through `LibraryStore`; reopen it from the dashboard to replay its ordered paths.

## Current boundary

Listening rooms are a visual and session-planning choice. Their colors do not mean the app has detected a song's emotion. The ambient player uses saved energy and movement tags; it is not a live audio waveform. Cover art is optional and read locally. Saved moments keep descriptive session memory and local paths, while standard playlists remain a separate feature.
