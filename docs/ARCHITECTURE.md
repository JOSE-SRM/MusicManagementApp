# Sangitam architecture

Sangitam keeps a small, explainable separation between the Swing interface and the work behind it. The first working journey is **choose a file or folder → scan metadata → save a track → search or browse → play**.

## How the pieces connect

```text
SangitamApp
  └─ MainFrame (navigation and shared state wiring)
       ├─ Swing panels: Dashboard, Search, Library, Import dialog, Player bar
       ├─ LibraryService
       │    ├─ LibraryScanner → Track
       │    └─ LibraryStore → local SQLite file
       └─ PlayerService → JavaFxPlayerService → local audio file
```

`MainFrame` owns one `LibraryService` and one `PlayerService`. It sends the same track list to the dashboard, search panel and library panel. These panels do not contain their own sample collections or SQL. A double-click or Enter action sends a `Track` back to `MainFrame`, which asks the player to open it. The player sends an immutable `PlaybackState` back to the Swing player bar.

`Track` is a small record containing a normalized file path, title, artist, album, duration and format. The path is the database key, so importing the same file again updates one row rather than creating a duplicate. The scanner accepts MP3 and WAV files, reads embedded metadata with Jaudiotagger, and falls back to the filename when tags are empty. It does not follow directory symlinks while walking a folder.

`LibraryStore` is the only class that knows SQLite. `AppPaths` locates the database in the user's application-data area. `TrackSearch` is a pure function over the imported library, so its filtering rules can be tested separately from Swing.

## Threading

Swing components are created and updated on the Event Dispatch Thread. `LibraryService` uses `SwingWorker` for initial database loading, folder scanning and database writes; it publishes progress and final track snapshots back on the Event Dispatch Thread. Search filtering runs in a debounced `SwingWorker`, so typing does not perform the full scan inside a text-field listener. Library and soundscape animations use `javax.swing.Timer`.

Audio decoding uses JavaFX Media on its own application thread. `JavaFxPlayerService` is the only class that imports JavaFX. It starts that thread in the background when the player service is created, turns commands into JavaFX work, and returns immutable state to Swing with `SwingUtilities.invokeLater`. The visible interface still uses only Swing components. The adapter can be replaced if another decoder proves better for later format support or packaging.

## Presenting the code

1. Start at `SangitamApp`: install the theme and create `MainFrame` on the Event Dispatch Thread.
2. Show `MainFrame`: one place wires screens, the library and the player together.
3. Import a test folder: `ImportMediaDialog` passes paths to `LibraryService`; `LibraryScanner` reads files; `LibraryStore` saves tracks.
4. Search: `SearchPanel` asks `TrackSearch` to filter the shared track list.
5. Play a track: a panel sends `Track` to `MainFrame`, then `PlayerService` reports a `PlaybackState` to `PlayerBar`.
6. Show the Session Composer and soundscape as the next product stage. Today they preview an intent and duration, but they do not yet select songs.

## Next boundary

The next stage adds a session service that chooses and orders actual tracks using clear rules, shows reasons and duration, permits editing, and saves the resulting queue. It should use `Track` data from `LibraryService` and stay independent of Swing so its selection rules can be tested directly.
