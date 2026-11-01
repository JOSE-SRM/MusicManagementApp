# Sangitam desktop

Sangitam is a local music library and listening workspace built with Java Swing. It imports MP3 and WAV files, saves their metadata and measured audio tags in SQLite, searches the collection, and plays selected tracks. The Session Composer builds an editable, playable queue from those tags. A separate Discover screen searches online catalogs and opens results with their providers.

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

## Discover online

**Discover online** searches four catalogs. Select a provider, enter a song, artist, or album, and press **Search catalog**. Double-click a result or use **Open in provider** to open its official page. These results are links and metadata; they are not downloaded, played by Sangitam, imported into the local library, or added to Session Composer. Local analysis and sessions still use only imported files.

| Source | Setup before starting Sangitam | What it provides |
| --- | --- | --- |
| MusicBrainz | None | Recording metadata and a MusicBrainz page. |
| Spotify | `SANGITAM_SPOTIFY_ACCESS_TOKEN` | Catalog tracks and Spotify links. Supply a short-lived OAuth access token obtained with Spotify's [PKCE flow](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow); refresh it when it expires. |
| YouTube | `SANGITAM_YOUTUBE_API_KEY` | Music-category video search and YouTube links. Enable the [YouTube Data API](https://developers.google.com/youtube/v3/getting-started) in your own Google Cloud project. Searches consume quota. |
| Apple Music | `SANGITAM_APPLE_MUSIC_DEVELOPER_TOKEN` | Catalog songs and Apple Music links. Generate the signed [developer token](https://developer.apple.com/documentation/AppleMusicAPI/generating-developer-tokens) outside the desktop app. Optional `SANGITAM_APPLE_MUSIC_STOREFRONT` is a two-letter storefront, defaulting to `in`. |

Set these variables in the terminal that launches the app; for example, `export SANGITAM_YOUTUBE_API_KEY=...` on macOS/Linux or `$env:SANGITAM_YOUTUBE_API_KEY='...'` in PowerShell. Restart Sangitam after changing them. Do not commit tokens or API keys to Git. The app does not ask for account passwords or store credentials in its database. Spotify and Apple access tokens can expire; an access-denied message means the credential or its permissions need checking. Spotify development-mode apps have account and user limits. See each provider's developer documentation before sharing an integration beyond the team.

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
  discovery/ online catalog adapters and bounded HTTP requests
bin/sangitam/desktop/   generated class files
```

JavaFX is used only to decode and control audio. Every visible screen and control remains a Swing component. See [architecture notes](docs/ARCHITECTURE.md) for the flow and threading rules.

## Current limits

The app currently supports MP3 and WAV. The audio tags are lightweight estimates from a short sample; a quiet introduction or a long track can make them less representative. Session queues are editable during the current run but are not yet saved as named playlists. Advanced metadata repair, format expansion and a packaged installer are later work. Online provider sign-in, in-app remote playback, and remote results in sessions are not implemented; provider APIs here are for search and official-page handoff only. Menus for features not yet implemented still show notifications.

The project uses OpenJFX Media, SQLite JDBC, Jaudiotagger, MP3SPI (including its decoder dependencies), and Gson for provider JSON. Review their licenses and package the required notices before distributing a release build.
