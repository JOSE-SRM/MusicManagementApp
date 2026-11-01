# Sangitam desktop

Sangitam is a local music library and listening workspace built with Java Swing. It now imports MP3 and WAV files, keeps their metadata in a local SQLite database, searches the imported collection, and plays selected tracks. The Session Composer remains a preview for the next stage: it does not yet build a real queue.

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

Use **Add music** to choose one or more MP3/WAV files or a folder. You can include subfolders. The original files stay in place. Double-click a track in the dashboard, search results or library to play it. The player bar supports play/pause, previous/next, seeking and volume. The search screen filters real titles, artists and albums. If a file is moved after import, playback reports that it is missing.

Compiled classes mirror the package structure under `bin/sangitam/desktop/`. The library database is stored in the current user's application data folder, not in the Git checkout. For an isolated demo, pass `-Dsangitam.dataDir=/path/to/demo-data` to the Java/Maven process.

## Build and test

```sh
make compile
make test
make clean
```

Windows users can compile with `build.bat`. Tests use generated silent audio and cover folder scanning, metadata, duplicate paths, persistence, search and the background import-to-Swing update path.

## Structure

```text
src/sangitam/desktop/
  app/       Swing entry point
  ui/        all visible Swing screens and controls
  model/     Track data
  library/   file scanning, background import and search rules
  storage/   SQLite persistence and application-data path
  playback/  audio interface and JavaFX Media adapter
bin/sangitam/desktop/   generated class files
```

JavaFX is used only to decode and control audio. Every visible screen and control remains a Swing component. See [architecture notes](docs/ARCHITECTURE.md) for the flow and threading rules.

## Current limits

The first stage supports MP3 and WAV. The Session Composer still shows a visual recipe without choosing tracks. Playlists, listening history, advanced metadata repair, format expansion and a packaged installer are later work. Menus for features not yet implemented still show notifications.

The project uses OpenJFX Media, SQLite JDBC and Jaudiotagger. Review their licenses and package the required notices before distributing a release build.
