# Sangitam desktop

Sangitam is a local music library and listening workspace built with Java Swing. It imports common local audio formats, saves metadata and measured tags in SQLite, searches the collection, and plays selected tracks. The Session Composer builds an editable, playable queue that can be saved as a named playlist. A separate Discover screen searches online catalogs and opens results with their providers.

## Rediscover favorites

Open **Rediscover** in the left navigation to see **Most played** and **Forgotten favorites**. Sangitam records a local play after you actually listen to half the track or four minutes, whichever is shorter. The in-app collection groups songs by normalized title and artist, merges reports of the same play from different sources when their timestamps are within two minutes, and keeps separate repeats from the same source. A forgotten favorite has at least three known plays and no observed play in the last 30 days. Old local play counts from earlier releases are preserved, but their last-played date is unknown, so they do not enter the forgotten list until a dated play is observed.

**Save & sync** connects optional histories. Enter your public ListenBrainz or Last.fm username on the Rediscover screen; usernames are remembered on this computer. Spotify needs a token with the additional `user-read-recently-played` OAuth scope; Last.fm needs an API key configured in Settings. Each source is optional and syncs separately; an expired token does not discard other sources' results. Syncs run in the background and repeat syncs do not add the same provider event twice. The screen offers **Play local file** when a matching imported song exists and **Open source** for a provider link. **Export collection** writes a new UTF-8 CSV into a folder you choose, without copying or downloading music.

These are **known plays**, not guaranteed lifetime totals. Spotify returns at most 50 recent plays per request; this version requests that window. ListenBrainz and Last.fm fetch bounded recent scrobbles (500 and 200 respectively). If you already scrobble from another player to either service, those reported plays can be included. Apple Music's recent-track API does not give this feature a reliable timestamped play count, and YouTube's Data API does not expose watch history, so neither is counted here. The existing Apple Music and YouTube catalog search still works separately. Sangitam stores track metadata, source, links, and timestamps locally in SQLite; it never stores provider tokens in the database. See [Spotify recently played](https://developer.spotify.com/documentation/web-api/reference/get-recently-played), [ListenBrainz listens](https://listenbrainz.readthedocs.io/en/latest/users/api/core.html), [Last.fm recent tracks](https://www.last.fm/api/show/user.getRecentTracks), and [YouTube watch-history API limitation](https://developers.google.com/youtube/data/reference/rest/v3/playlistItems/list).

## Run

Install **JDK 21 or newer**. The Maven wrapper downloads the build tools and dependencies on the first build; a separate Maven installation is not needed.

On macOS or Linux:

```sh
make run
```

On Windows, compile once and launch separately:

```bat
build.bat
run.bat
```

`run.bat` uses the already-compiled classes and does not rebuild. VLC for Windows is used as the primary local decoder for broad format support and direct seeking. If VLC is not installed, Sangitam falls back to JavaFX Media, which has narrower format support. VLC is an external native runtime and is not bundled in this development build.

Use **Add music** to choose audio files or folders. In the browser, pick a **Drive** or enter a full folder path and press **Enter** or **Go**. Double-click a folder to open it, select items to import them, or use **Use current folder** with no selection. The browser remembers the last folder it opened. Folder imports can include subfolders and leave the original files in place.

Use **Library → Rescan library** after changing files or reconnecting a drive. Sangitam revisits sources saved by **Add music**, including each folder’s subfolder setting. Unchanged files reuse their indexed metadata. A file at an already indexed path keeps its existing library entry and manual tags. Files copied to different paths are separate entries.

Unavailable tracks stay in the library with their playlist references and listening history. Reconnect the drive or restore the file to its original path, then rescan to clear the label. Search has an **Available only** filter. For folders imported before rescan support, add the folder once to let future rescans discover new files there. Existing tracks from those folders are checked on every rescan.

**General** mixes songs, artists, albums, playlists, and virtual folders. It shows embedded cover art when available, with icons for other entries. Use the search field to filter the active tab. General defaults to **Recent**, based on when tracks were added or played and when collections were updated. Switch to **Alphabetical** or **Most played** from the same toolbar. Pins appear first. **Locations** shows the actual drive and folder hierarchy. Hover a row for its full path, use Enter to expand a folder, or double-click a track to play it. The expand button gives the library more room, and navigating to another screen contracts it.

Use **+** to import music or create a playlist or virtual folder. Right-click an item, or use **Shift+F10**, for playback, queue, playlist, editing, and organization actions. **Add to playlist** offers a new playlist or a searchable list of saved playlists. Double-click an album or playlist to open its songs in the main pane.

Drag playlists or virtual folders onto another virtual folder to move them. Dropping an album or playlist on a playlist appends its tracks in order and skips paths already present. Dropping an album on a folder creates a playlist named after the album. Virtual folders organize library references. Deleting a virtual folder removes its nested folders and playlists. **Remove from library** removes indexed entries and excludes those locations from rescans until explicitly imported again. Both actions leave the audio files on disk. Edit Details saves catalog corrections locally and keeps them across rescans.

When an embedded title is blank or says `tmp`, Sangitam tries the MP3’s alternate title tag before using its filename. For an absent artist, it checks album artist and composer tags, then uses the named composer folder for tracks under `Classical\Surname, Given`. That folder name identifies the composer. The app refreshes affected library labels in the background after startup. **Rescan library** can retry once an unavailable file returns.

Double-click a track in the dashboard, search results, or library to play it. Play/Pause is disabled during decoder startup and becomes Pause when playback begins. Once seeking is available, click the position bar or drag it to preview a time and release to seek. The controls also include stop, previous/next, logarithmic volume, shuffle, and repeat-current. On Windows, media play/stop/next/previous and volume keys work globally when the app is minimized. On the next launch, Sangitam shows the last selected song at its saved position. Press Play to resume it. If a track is unavailable, check that its drive is connected or import it from its new location.

The custom importer recognizes MP3, WAV, FLAC, OGG/OGA, AAC, M4A, AIFF, and WMA files. VLC/libVLC supplies broad decoding when installed; the JavaFX fallback may not decode every listed format. Audio analysis currently remains limited to formats supported by Java Sound and MP3SPI.

During import, Sangitam estimates **energy** from loudness and movement, and **movement** from short changes in volume and sound texture. For longer songs it samples the opening and a later passage, so a quiet intro is less likely to classify the whole track. Existing libraries get the revised analysis in the background on the next launch. Genre comes from embedded file metadata; when absent, an explicitly named genre folder such as `Rock` or `Jazz` provides a hint. Other tracks remain **Genre untagged**. Select a song in **Your library** and choose **Edit selected tags…** to set its genre, energy and movement. These corrections are saved in Sangitam and survive rescans; the original audio file is not changed. Audio that cannot be decoded remains **Not analyzed**. Analysis is local and does not upload audio.

On the dashboard, choose Focus, Explore, or Unwind, set energy and available time, then select **Build my session**. The queue shows why each track was selected. Reorder, remove, or manually add tracks, then press **Play session**. Choose **Save playlist** to store the current queue by name. The **Playlists** item in the left navigation opens the saved playlist selector, where you can load or delete a playlist after a restart. **File → Export → Export current playlist…** writes an M3U8 file with local file paths for other players. **Prioritise familiar tracks** uses the local play count. Energy and movement are simple measurements, not a claim to identify a song's emotion, genre, vocals, or exact BPM.

## Discover online

**Discover online** searches four catalogs. Select a provider, enter a song, artist, or album, and press **Search catalog**. Double-click a result or use **Open in provider** to open its official page. These results are links and metadata; they are not downloaded, played by Sangitam, imported into the local library, or added to Session Composer. Local analysis and sessions still use only imported files.

| Source | Setup before starting Sangitam | What it provides |
| --- | --- | --- |
| MusicBrainz | None | Recording metadata and a MusicBrainz page. |
| Spotify | `SANGITAM_SPOTIFY_ACCESS_TOKEN` | Catalog tracks and Spotify links. Supply a short-lived OAuth access token obtained with Spotify's [PKCE flow](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow); refresh it when it expires. |
| YouTube | `SANGITAM_YOUTUBE_API_KEY` | Music-category video search and YouTube links. Enable the [YouTube Data API](https://developers.google.com/youtube/v3/getting-started) in your own Google Cloud project. Searches consume quota. |
| Apple Music | `SANGITAM_APPLE_MUSIC_DEVELOPER_TOKEN` | Catalog songs and Apple Music links. Generate the signed [developer token](https://developer.apple.com/documentation/AppleMusicAPI/generating-developer-tokens) outside the desktop app. Optional `SANGITAM_APPLE_MUSIC_STOREFRONT` is a two-letter storefront, defaulting to `in`. |

Configure Spotify, YouTube, Apple Music, and Last.fm credentials in **Settings**. On Windows Sangitam stores them in the signed-in user's Credential Manager, not in SQLite or source files. Environment variables remain supported for development. Spotify and Apple access tokens can expire; an access-denied message means the credential or its permissions need checking. Spotify development-mode apps have account and user limits. See each provider's developer documentation before sharing an integration beyond the team.

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

VLCJ/libVLC is the primary decoder, with JavaFX Media as a fallback; every visible screen and control remains a Swing component. See [architecture notes](docs/ARCHITECTURE.md) for the flow and threading rules.

## Current limits

VLC-backed playback depends on a compatible VLC installation. Playlists use local file references. Audio traits are estimates from short passages, so complex songs can still need manual correction. In-app OAuth sign-in, cloud playback, smart playlists, advanced classical metadata, and analytics charts remain future work. Provider catalog results open in their official services. Rediscover account sync reads bounded recent history from connected sources.

The project uses OpenJFX Media, vlcj/libVLC, JNativeHook, SQLite JDBC, Jaudiotagger, MP3SPI (including its decoder dependencies), and Gson for provider JSON. Review their licenses and include required notices before distributing a release build.
