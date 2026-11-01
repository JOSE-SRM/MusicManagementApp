# Sangitam architecture

Sangitam keeps a small, explainable separation between the Swing interface and the work behind it. The working journey is **choose a file or folder → scan metadata and audio → save tags → build an editable session → play**.

## How the pieces connect

```text
SangitamApp
  └─ MainFrame (navigation and shared state wiring)
       ├─ Swing panels: Dashboard, Search, Discover, Library, Import dialog, Player bar
       ├─ DiscoverPanel → MusicProvider adapters → official catalog APIs
       ├─ LibraryService
       │    ├─ LibraryScanner → Track
       │    ├─ AudioAnalyzer → TrackTags
       │    └─ LibraryStore → local SQLite file
       ├─ SessionPlanner → chosen tracks with reasons
       └─ PlayerService → JavaFxPlayerService → local audio file
```

`MainFrame` owns one `LibraryService` and one `PlayerService`. It sends the same track list to the dashboard, search panel and library panel. These panels do not contain their own sample collections or SQL. A double-click or Enter action sends a `Track` back to `MainFrame`, which asks the player to open it. The player sends an immutable `PlaybackState` back to the Swing player bar.

`Track` is a small record containing a normalized file path, title, artist, album, duration, format and optional genre. The path is the database key, so importing the same file again updates one row rather than creating a duplicate. The scanner accepts MP3 and WAV files, reads embedded metadata with Jaudiotagger, and falls back to the filename when tags are empty. It does not follow directory symlinks while walking a folder.

`AudioAnalyzer` decodes a short PCM sample through Java Sound (with MP3SPI for MP3), calculates loudness and changes in loudness, then saves energy and activity scores. `TrackTags` records whether measurement succeeded and the file size/time used for cache validity. It never guesses a mood, voice presence or genre from audio. The database migration adds these columns to existing libraries without removing tracks. Play counts are saved after confirmed playback.

`SessionPlanner` scores candidate tracks against the requested energy, intent, genre tags and local play counts. It adds artist variety, avoids duplicate file paths, and tries to fit the requested duration. Every selected track includes a human-readable reason. The queue UI can reorder, remove or add tracks; `MainFrame` routes play, next, previous and end-of-track events through that queue.

`LibraryStore` is the only class that knows SQLite. `AppPaths` locates the database in the user's application-data area. `TrackSearch` is a pure function over the imported library, so its filtering rules can be tested separately from Swing.

`DiscoverPanel` is a separate search path for online catalogs. Each `MusicProvider` adapter reads only catalog metadata and constructs a link on the provider's official domain. MusicBrainz works without a credential; the other adapters read environment variables at startup. A `CatalogTrack` is intentionally distinct from the local `Track`, so online results cannot accidentally enter local playback or the tag-based session queue. No provider credentials are persisted.

## Threading

Swing components are created and updated on the Event Dispatch Thread. `LibraryService` uses `SwingWorker` for initial database loading, folder scanning, audio analysis and database writes; it publishes progress and final catalog snapshots back on the Event Dispatch Thread. Old libraries are analyzed after their track list is already visible. Session building and file-existence checks run in another worker. Search filtering runs in a debounced `SwingWorker`. Online HTTP searches and browser launches also run in `SwingWorker`s with timeouts. Library and soundscape animations use `javax.swing.Timer`.

Audio decoding uses JavaFX Media on its own application thread. `JavaFxPlayerService` is the only class that imports JavaFX. It starts that thread in the background when the player service is created, turns commands into JavaFX work, and returns immutable state to Swing with `SwingUtilities.invokeLater`. The visible interface still uses only Swing components. The adapter can be replaced if another decoder proves better for later format support or packaging.

## Presenting the code

1. Start at `SangitamApp`: install the theme and create `MainFrame` on the Event Dispatch Thread.
2. Show `MainFrame`: one place wires screens, the library and the player together.
3. Import a test folder: `ImportMediaDialog` passes paths to `LibraryService`; `LibraryScanner` reads metadata; `AudioAnalyzer` measures sound; `LibraryStore` saves both.
4. Search: `SearchPanel` asks `TrackSearch` to filter the shared track list.
5. Play a track: a panel sends `Track` to `MainFrame`, then `PlayerService` reports a `PlaybackState` to `PlayerBar`.
6. Build a session: `SessionPlanner` chooses tracks with reasons; edit the Swing queue, start playback, then show how next and previous follow its order.

## Next boundary

Named, saved sessions are a later boundary. They can serialize `SessionPlanner` choices without changing the audio decoder or the Swing queue controls.
