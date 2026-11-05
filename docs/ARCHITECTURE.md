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
       └─ PlayerService → VlcjPlayerService → libVLC (JavaFX Media fallback)
```

`MainFrame` owns one `LibraryService` and one `PlayerService`. It sends the same track list to the dashboard, search panel and library panel. These panels do not contain their own sample collections or SQL. A double-click or Enter action sends a `Track` back to `MainFrame`, which asks the player to open it. The player sends an immutable `PlaybackState` back to the Swing player bar.

`Track` is a small record containing a normalized file path, title, artist, album, duration, format and optional genre. The path is the database key, so importing the same file again updates one row rather than creating a duplicate. The scanner recognizes MP3, WAV, FLAC, OGG/OGA, AAC, M4A, AIFF, and WMA by extension, reads embedded metadata with Jaudiotagger, and falls back to the filename when tags are empty. It does not follow directory symlinks while walking a folder. The file browser enumerates drive roots, reads the remembered location, and builds directory listings on Swing workers. The Swing renderer uses the file types found by the listing worker.

Imports remember the selected canonical paths and recursion settings in `library_sources`. A rescan walks those sources and checks previously indexed paths outside the current source results. The scanner reuses metadata when file size and modification time match the saved stamp. Analysis uses its own freshness stamp. Import and rescan workers serialize with each other and release the store monitor during filesystem traversal and audio analysis. Each scan commits metadata, source settings, availability, and new analysis results together, then publishes an immutable catalog snapshot to Swing.

Availability is stored on the track row and reflects the most recent check. An unavailable row retains its path, tags, playlists, and listening history. Search and the library render that state from the catalog snapshot without checking the filesystem. A successful scan of the original path clears it. Older databases start with unknown metadata stamps and an empty source list. Rescan checks their indexed files, and explicit folder imports register new discovery scopes.

The scanner can bypass the metadata stamp for a stored placeholder title or a missing artist with a composer-folder fallback. Startup schedules that narrow refresh using the same scan worker and transactional upsert as rescan. It leaves the source list and file tags alone. The folder tree is built from indexed paths on a Swing worker and attached on the event thread. Expansion and selection are restored by path when metadata changes.

General library rows combine catalog snapshots with the virtual organization snapshot. `CollectionStore` stores virtual folder parents, direct song membership, scoped playlist names, and pins in the existing SQLite database. The playlist migration preserves IDs and item positions. Collection mutations are serialized with the store, and folder moves reject descendants as destinations. Track identity remains the indexed path. Album rows group by album name and credited artist. Queue actions share MainFrame’s existing ordered playback session. Cover reads run through a bounded background cache and use embedded local artwork.

Catalog detail edits preserve separate overrides for title, artist, album, genre, and analysis ratings. Rescan upserts honor those overrides. Library removal records a path exclusion and removes catalog rows in one transaction. Playlist and listen references keep their paths so an explicit reimport can restore the catalog entry.

`AudioAnalyzer` decodes a short PCM sample through Java Sound (with MP3SPI for MP3), calculates loudness and changes in loudness, then saves energy and activity scores. `TrackTags` records whether measurement succeeded and the file size/time used for cache validity. It never guesses a mood, voice presence or genre from audio. The database migration adds these columns to existing libraries without removing tracks. Play counts are saved after confirmed playback.

`SessionPlanner` scores candidate tracks against the requested energy, intent, genre tags and local play counts. It adds artist variety, avoids duplicate file paths, and tries to fit the requested duration. Every selected track includes a human-readable reason. The queue UI can reorder, remove or add tracks; `MainFrame` routes play, next, previous and end-of-track events through that queue.

`LibraryStore` is the only class that knows SQLite. `AppPaths` locates the database in the user's application-data area. `TrackSearch` is a pure function over the imported library, so its filtering rules can be tested separately from Swing.

`DiscoverPanel` is a separate search path for online catalogs. Each `MusicProvider` adapter reads only catalog metadata and constructs a link on the provider's official domain. MusicBrainz works without a credential. The other adapters use the provider credential service, which supports Windows Credential Manager and environment variables for development. A `CatalogTrack` is intentionally distinct from the local `Track`, so online results cannot accidentally enter local playback or the tag-based session queue.

## Threading

Swing components are created and updated on the Event Dispatch Thread. `LibraryService` uses `SwingWorker` for initial database loading, folder scanning, audio analysis and database writes; it publishes progress and final catalog snapshots back on the Event Dispatch Thread. Old libraries are analyzed after their track list is already visible. Session building and file-existence checks run in another worker. Search filtering runs in a debounced `SwingWorker`. Online HTTP searches and browser launches also run in `SwingWorker`s with timeouts. Library and soundscape animations use `javax.swing.Timer`.

`PlayerService` remains the transport boundary. `VlcjPlayerService` uses one command executor to serialize libVLC calls and sample the active player's status, position, and seekability. It reuses the libVLC factory and creates a new media player for each open. End and error callbacks return to the command executor and are scoped to that media player. It prepares libVLC after the first UI paint and falls back to `JavaFxPlayerService` when native initialization fails. JavaFX commands and callbacks run on the JavaFX application thread. Both adapters discard obsolete playback snapshots when a newer track is requested and deliver current snapshots to Swing. The position bar previews during a drag and sends one seek on release. The decoder supplies the duration and reconciles the result. VLC is an external dependency. The Windows media-key listener uses JNativeHook and filters events to play, stop, previous, next, and volume controls.

Provider API secrets are read from Windows Credential Manager on a background worker and cached in memory for the session. The Settings dialog writes credentials through a small Windows Credential Manager bridge. Environment variables remain a development fallback. The custom Swing file browser stores only its last directory in Java Preferences.

## Presenting the code

1. Start at `SangitamApp`: install the theme and create `MainFrame` on the Event Dispatch Thread.
2. Show `MainFrame`: one place wires screens, the library and the player together.
3. Import a test folder: `ImportMediaDialog` passes paths to `LibraryService`; `LibraryScanner` reads metadata; `AudioAnalyzer` measures sound; `LibraryStore` saves both.
4. Search: `SearchPanel` asks `TrackSearch` to filter the shared track list.
5. Play a track: a panel sends `Track` to `MainFrame`, then `PlayerService` reports a `PlaybackState` to `PlayerBar`.
6. Build a session: `SessionPlanner` chooses tracks with reasons; edit the Swing queue, start playback, then show how next and previous follow its order.

## Next boundary

Named, saved sessions are a later boundary. They can serialize `SessionPlanner` choices without changing the audio decoder or the Swing queue controls.
