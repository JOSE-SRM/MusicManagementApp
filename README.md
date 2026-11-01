# Sangitam desktop

Sangitam is a local music library and listening workspace built with Java Swing. It imports MP3 and WAV files, saves their metadata and measured audio tags in SQLite, searches the collection, and plays selected tracks. The Session Composer builds an editable, playable queue from those tags.

## Run

Install **JDK 21 or newer**. The Maven wrapper downloads the build tools and dependencies on the first build; a separate Maven installation is not needed.

On macOS or Linux:

```sh
make run
```

On Windows:

```bat
run.bat
```

Use **Add music** to choose one or more MP3/WAV files or a folder. You can include subfolders. The original files stay in place. Double-click a track in the dashboard, search results or library to play it. The player bar supports play/pause, previous/next, seeking and volume. The search screen filters real titles, artists, albums and genre tags. If a file is moved after import, playback reports that it is missing.

During import, Sangitam samples up to 24 seconds of each track to estimate **energy** and **rhythmic activity**. It also reads a genre tag if the file has one. The library shows these tags beside the tracks. Existing libraries are analyzed in the background after launch; unchanged files keep their saved analysis. Tracks whose audio cannot be decoded remain in the library with an honest **Not analyzed** label. Analysis is local and does not upload audio.

On the dashboard, choose Focus, Explore, or Unwind, set energy and available time, then select **Build my session**. The queue shows why each track was selected. Reorder, remove, or manually add tracks, then press **Play session**. The player bar follows the queue. **Prioritise familiar tracks** uses the local play count. Energy and activity are simple measurements, not a claim to identify a song's emotion, genre, vocals, or exact BPM.

Compiled classes mirror the package structure under `bin/sangitam/desktop/`. The library database is stored in the current user's application data folder, not in the Git checkout. For an isolated demo, pass `-Dsangitam.dataDir=/path/to/demo-data` to the Java/Maven process.

## Build and test

```sh
make compile
make test
make clean
```

Windows users can compile with `build.bat`. Tests use generated audio and cover folder scanning, metadata, duplicate paths, persistence and migration, MP3/WAV analysis, session selection, search, and the background import-to-Swing update path.

If Windows reports that `Theme`, `AppIcons`, `NotificationCenter`, or `SoundscapeCanvas` cannot be found, the local folder is missing shared UI files from the `codex/modular-swing-ui` branch. Get the **whole branch**, not just the newest commit's changed files. In an existing Git checkout, run `git fetch origin`, switch to `codex/modular-swing-ui`, then run `git pull --ff-only` and `build.bat`. If Git reports local changes, save them before switching branches; do not discard them. The Windows build script checks these files and starts from a clean compilation.

## Structure

```text
src/sangitam/desktop/
  app/       Swing entry point
  ui/        all visible Swing screens and controls
  model/     Track data
  library/   file scanning, audio analysis, background import and search rules
  session/   explainable session selection rules
  storage/   SQLite persistence and application-data path
  playback/  audio interface and JavaFX Media adapter
bin/sangitam/desktop/   generated class files
```

JavaFX is used only to decode and control audio. Every visible screen and control remains a Swing component. See [architecture notes](docs/ARCHITECTURE.md) for the flow and threading rules.

## Current limits

The app currently supports MP3 and WAV. The audio tags are lightweight estimates from a short sample; a quiet introduction or a long track can make them less representative. Session queues are editable during the current run but are not yet saved as named playlists. Advanced metadata repair, format expansion and a packaged installer are later work. Menus for features not yet implemented still show notifications.

The project uses OpenJFX Media, SQLite JDBC, Jaudiotagger and MP3SPI (including its decoder dependencies). Review their licenses and package the required notices before distributing a release build.
