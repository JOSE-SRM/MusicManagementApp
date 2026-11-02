# Sangitam desktop

Sangitam is a local music library and listening workspace built with Java Swing. It imports MP3 and WAV files, saves their metadata and measured audio tags in SQLite, searches the collection, and plays selected tracks. The Session Composer builds an editable, playable queue from those tags. A separate Discover screen searches online catalogs and opens results with their providers.

## Rediscover favorites

Open **Rediscover** in the left navigation to see **Most played** and **Forgotten favorites**. Sangitam records a local play after you actually listen to half the track or four minutes, whichever is shorter. The in-app collection groups songs by normalized title and artist, merges reports of the same play from different sources when their timestamps are within two minutes, and keeps separate repeats from the same source. A forgotten favorite has at least three known plays and no observed play in the last 30 days. Old local play counts from earlier releases are preserved, but their last-played date is unknown, so they do not enter the forgotten list until a dated play is observed.

**Save & sync** connects optional histories. Enter your public ListenBrainz or Last.fm username on the Rediscover screen; usernames are remembered on this computer. Spotify uses `SANGITAM_SPOTIFY_ACCESS_TOKEN` with the additional `user-read-recently-played` OAuth scope. Last.fm also needs `SANGITAM_LASTFM_API_KEY`. Restart after setting an environment variable. Each source is optional and syncs separately; an expired token does not discard other sources' results. Syncs run in the background and repeat syncs do not add the same provider event twice. The screen offers **Play local file** when a matching imported song exists and **Open source** for a provider link. **Export collection** writes a new UTF-8 CSV into a folder you choose, without copying or downloading music.

These are **known plays**, not guaranteed lifetime totals. Spotify returns at most 50 recent plays per request; this version requests that window. ListenBrainz and Last.fm fetch bounded recent scrobbles (500 and 200 respectively). If you already scrobble from another player to either service, those reported plays can be included. Apple Music's recent-track API does not give this feature a reliable timestamped play count, and YouTube's Data API does not expose watch history, so neither is counted here. The existing Apple Music and YouTube catalog search still works separately. Sangitam stores track metadata, source, links, and timestamps locally in SQLite; it never stores provider tokens in the database. See [Spotify recently played](https://developer.spotify.com/documentation/web-api/reference/get-recently-played), [ListenBrainz listens](https://listenbrainz.readthedocs.io/en/latest/users/api/core.html), [Last.fm recent tracks](https://www.last.fm/api/show/user.getRecentTracks), and [YouTube watch-history API limitation](https://developers.google.com/youtube/data/reference/rest/v3/playlistItems/list).

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
  history/   qualified plays, account history adapters and favorite grouping
bin/sangitam/desktop/   generated class files
```

JavaFX is used only to decode and control audio. Every visible screen and control remains a Swing component. See [architecture notes](docs/ARCHITECTURE.md) for the flow and threading rules.

## Current limits

The app currently supports MP3 and WAV. The audio tags are lightweight estimates from a short sample; a quiet introduction or a long track can make them less representative. Session queues are editable during the current run but are not yet saved as named playlists. Advanced metadata repair, format expansion and a packaged installer are later work. In-app OAuth sign-in, in-app remote playback, and remote results in sessions are not implemented; provider audio still opens in its own service. Rediscover account sync reads only bounded recent history with externally supplied credentials or public usernames. Menus for features not yet implemented still show notifications.

The project uses OpenJFX Media, SQLite JDBC, Jaudiotagger, MP3SPI (including its decoder dependencies), and Gson for provider JSON. Review their licenses and package the required notices before distributing a release build.
