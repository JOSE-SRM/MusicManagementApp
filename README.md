# Sangitam desktop

Sangitam is a local music library and listening workspace built with Java Swing. It imports common local audio formats, saves metadata and measured tags in SQLite, searches the collection, and plays selected tracks. The Session Composer builds an editable, playable queue that can be saved as a named playlist. A separate Discover screen searches online catalogs and opens results with their providers.

To learn how it works, start with the [implementation guide](docs/IMPLEMENTATION_GUIDE.md). It follows a song through import, metadata overrides, SQLite paging, Swing workers, and playback, with source links, test commands, and small exercises.

## Rediscover favorites

Open **Rediscover** in the left navigation to see **Most played** and **Forgotten favorites**. Sangitam records a local play after you actually listen to half the track or four minutes, whichever is shorter. The in-app collection groups songs by normalized title and artist, merges reports of the same play from different sources when their timestamps are within two minutes, and keeps separate repeats from the same source. A forgotten favorite has at least three known plays and no observed play in the last 30 days. Old local play counts from earlier releases are preserved, but their last-played date is unknown, so they do not enter the forgotten list until a dated play is observed.

**Save & sync** connects optional histories. Enter your public ListenBrainz or Last.fm username on the Rediscover screen; usernames are remembered on this computer. Spotify needs a token with the additional `user-read-recently-played` OAuth scope; Last.fm needs an API key configured in Settings. Each source is optional and syncs separately; an expired token does not discard other sources' results. Syncs run in the background and repeat syncs do not add the same provider event twice. The screen offers **Play local file** when a matching imported song exists and **Open source** for a provider link. **Export collection** writes a new UTF-8 CSV into a folder you choose, without copying or downloading music.

**Listening insights** shows measured time on this device by genre, classical period, and estimated mood. It includes time from removed recordings in a separate bucket and labels recordings awaiting analysis. The source section counts local and previously synced play events separately from measured minutes. Open the tab or choose **Refresh insights** to load the latest saved totals.

These are **known plays**, not guaranteed lifetime totals. Spotify returns at most 50 recent plays per request; this version requests that window. ListenBrainz and Last.fm fetch bounded recent scrobbles (500 and 200 respectively). If you already scrobble from another player to either service, those reported plays can be included. Apple Music's recent-track API does not give this feature a reliable timestamped play count, and YouTube's Data API does not expose watch history, so neither is counted here. The existing Apple Music and YouTube catalog search still works separately. Sangitam stores track metadata, source, links, and timestamps locally in SQLite; it never stores provider tokens in the database. See [Spotify recently played](https://developer.spotify.com/documentation/web-api/reference/get-recently-played), [ListenBrainz listens](https://listenbrainz.readthedocs.io/en/latest/users/api/core.html), [Last.fm recent tracks](https://www.last.fm/api/show/user.getRecentTracks), and [YouTube watch-history API limitation](https://developers.google.com/youtube/data/reference/rest/v3/playlistItems/list).

## Run

Install **JDK 21 or newer**. The Maven wrapper downloads the build tools and dependencies on the first build; a separate Maven installation is not needed.

On macOS or Linux:

```sh
make run
```

On Windows, run `package-windows.ps1` from PowerShell once and open the printed **Sangitam.exe** path. The portable app image includes Java, uses the same icon as the window, and opens without a terminal. Keep the entire `Sangitam` folder together. The one-time build needs JDK 21 or newer with `jpackage` on PATH. The scale test guide describes how to create a separate 100,000-file library and measure first paint. [Windows launcher and scale test](docs/WINDOWS_LAUNCH_AND_SCALE_TEST.md).

For a developer launch with terminal output:

```bat
build.bat
run.bat
```

`run.bat` uses the already-compiled classes and does not rebuild. VLC for Windows is used as the primary local decoder for broad format support and direct seeking. If VLC is not installed, Sangitam falls back to JavaFX Media, which has narrower format support. VLC is an external native runtime and is not bundled in this development build.

Use **Add music** to choose audio files or folders. In the browser, pick a **Drive** or enter a full folder path and press **Enter** or **Go**. Double-click a folder to open it, select items to import them, or use **Use current folder** with no selection. The browser remembers the last folder it opened. Folder imports can include subfolders and leave the original files in place.

Use **Library → Rescan library** after changing files or reconnecting a drive. Sangitam revisits sources saved by **Add music**, including each folder’s subfolder setting. Unchanged files reuse their indexed metadata. A file at an already indexed path keeps its existing library entry and manual tags. Files copied to different paths are separate entries.

Unavailable tracks stay in the library with their playlist references and listening history. Reconnect the drive or restore the file to its original path, then rescan to clear the label. Search has an **Available only** filter. For folders imported before rescan support, add the folder once to let future rescans discover new files there. Existing tracks from those folders are checked on every rescan.

**General** mixes songs, artists, albums, playlists, and virtual folders. It shows embedded cover art when available, with icons for other entries. Use the search field to filter the active tab. General defaults to **Recent**, based on when tracks were added or played and when collections were updated. Switch to **Alphabetical** or **Most played** from the same toolbar. Pins appear first. **Locations** shows the actual drive and folder hierarchy. Hover a row for its full path, use Enter to expand a folder, or double-click a track to play it. The expand button gives the library more room, and navigating to another screen contracts it.

General displays **100 entries per page**, with Previous/Next and **Alt+Page Up / Alt+Page Down** while the list is focused. Searching, changing order, or entering a virtual folder starts at page one. Catalog updates keep the current page when possible and clamp it after removals. Filtering and sorting run on workers; old results cannot replace a newer request. Album, playlist, and folder actions still use their complete membership, including tracks outside the displayed page.

Locations loads children when you expand a folder and shows at most **100 direct children per branch page**, with Previous/Next rows activated by Enter or double-click. Collapsing a folder releases its loaded descendants. Search includes indexed paths, song labels, classical details, and custom tags without browsing the disk. Selection restores by path, including a song on a later branch page. Failures offer Retry, and unavailable files retain their normal labels and context actions. This bounds the displayed rows/nodes; the catalog and path index still live in memory. Learn the implementation in [Library presentation paging](docs/LIBRARY_PRESENTATION_PAGING.md).

Use the General category picker to browse songs, artists, albums, playlists, or virtual folders. Artists include separate musician credits such as composers and conductors. The library expands, contracts, and hides with an eased width transition. Use **+** to import music or create a playlist or virtual folder. Right-click an item, or use **Shift+F10**, for playback, queue, playlist, editing, and organization actions. **Add to playlist** offers a new playlist or a searchable list of saved playlists. Double-click an album or playlist to open its songs in the main pane. Hover a General row for song details or collection statistics and sample titles. Pins use a drawn icon.

**Listen Now** shows up to 12 recently played local tracks and eight pinned playlists, including smart playlists. Recent listening uses recorded plays, excludes unavailable files, and does not count imports alone. Pinned playlists appear alphabetically. Double-click or press Enter to play a recent track or open a playlist; right-click or press Shift+F10 for the usual library actions. Covers load in the background with icon fallbacks and refresh after a rescan detects a changed file.

Choose **+ → Create smart playlist…** to save rules instead of individual songs. Combine literal text, exact genre, required tags, energy/movement ranges, minimum local plays, availability, ordering, and a track limit. All enabled conditions must match. **Preview matches** shows the matching count and up to 20 examples; Save includes up to your selected limit (default 100, maximum 5,000). Rating filters exclude unmeasured or outdated automatic ratings. Matches refresh in the background when the catalog changes. Smart playlists can be moved, pinned, played, and queued. Their context menu offers **Edit rules**, **Refresh matches**, and **Save as regular playlist** for a fixed copy. Manual additions and drops target regular playlists; existing playback queues keep their captured order. Learn the implementation in the [smart playlist walkthrough](docs/SMART_PLAYLISTS.md).

Drag playlists or virtual folders onto another virtual folder to move them. Dropping an album or playlist on a playlist appends its tracks in order and skips paths already present. Dropping an album on a folder creates a playlist named after the album. Virtual folders organize library references. Deleting a virtual folder removes its nested folders and playlists. **Remove from library** removes indexed entries and excludes those locations from rescans until explicitly imported again. Both actions leave the audio files on disk. Edit Details saves catalog corrections locally and keeps them across rescans.

When an embedded title is blank or says `tmp`, Sangitam tries the MP3’s alternate title tag before using its filename. For an absent artist, it checks album artist and composer tags, then uses the named composer folder for tracks under `Classical\Surname, Given`. That folder name identifies the composer. The app refreshes affected library labels in the background after startup. **Rescan library** can retry once an unavailable file returns.

**Edit details** separates **Basics**, **Classical / credits**, **Tags**, **Audio file**, and **Analysis**. Composer, conductor, performers, soloist, orchestra, work, movement, key, opus, period, form, and instrumentation have separate fields. File values take priority over conservative title/folder hints, whose source is shown in a tooltip. Periods are not guessed from a composer's name. Manual corrections and explicitly cleared fields survive rescans. When editing a collection, select **Apply** for each classical field you want to replace; **Replace tags on all selected tracks** is explicit too. Tags offer over 100 optional suggestions and accept your own comma-separated labels. The Audio File tab shows available bitrate, sample rate, bit depth, channels, and embedded-artwork presence. Older entries refresh their metadata in the background after startup without changing the audio files.

**Search** queries SQLite in pages of 100 songs, albums, or artists. Search includes classical details and custom tags, with Recent, Alphabetical, Most played, and Available only controls. Use Previous/Next to page, or double-click an album/artist to open its collection. General, Locations, dashboards, and session planning still use the loaded catalog; the whole application is not yet designed around database paging.

To follow file metadata again, right-click a song or album and choose **Restore file details…**. Select only the basic labels, classical credits, or custom tags you want to restore. Sangitam rereads those files and releases the selected corrections so future rescans can update them. Missing or unreadable files keep their corrections. Other edits, energy/movement ratings, playlist membership, and listening history remain in place. This changes the catalog only; it does not rewrite audio tags. See the [Home and metadata restoration walkthrough](docs/HOME_AND_METADATA_RESTORE.md) to learn the implementation.

Double-click a track in Listen Now’s recent shelf, search results, or Library to play it. The rounded playbar shows embedded cover art (or a music icon), a scrolling long title, linked musician credits, and distinct larger Play/Pause controls. Click the title or cover to open its tagged album; an untagged album opens that song's details. Credits prioritize composer, conductor, soloist, other performers, ensemble, album artist, then the remaining artist credit. Semicolons and spaced slashes separate people; commas are preserved for surname-first names. Click a name to open its artist page. The heart and the song context menu add or remove tracks from **Liked Songs** under Library → General → Playlists. Existing song pin marks appear there. The built-in playlist follows the heart marks and can be opened, played, queued, pinned, or copied.

The position row shows elapsed, remaining, and total time. Click or drag the rounded seek bar to preview a position and release to seek. Play/Pause is disabled during decoder startup. On the next launch, the selected song returns paused at its saved position. Volume follows VLC's percentage scale, is colored by range, supports mute/restore, and is saved locally, including the final change on a normal close. Continuous volume requests are coalesced on the decoder thread rather than building a backlog; audible responsiveness still needs a runtime check. Missing files report an error without removing their library/history references.

Artist pages show your ten most-played featured tracks, liked songs, albums in the local library, and musicians credited on the same tracks. Play or shuffle the artist's local music. Opening an artist automatically looks up a Wikipedia biography and, when available, a freely licensed Wikimedia Commons portrait. The profile shows a short preview with **Read more**, source links, and portrait credit. It caches the profile and image locally. Confirmed matches group aliases such as Beethoven under Ludwig van Beethoven. **Refresh / match…** accepts a fuller name for ambiguous results, **Online profile** remembers your automatic lookup preference, and **Edit artist profile…** saves local corrections. The online-catalog link opens a prefilled search; you choose the provider and explicitly search. These local albums are not a complete or globally ranked discography. Device listening time measures playback from this update onward, ignores seek distance and pauses, and retains credited totals after library removal. Earlier play counts cannot reconstruct historical listening minutes.

The **Queue** button opens the bottom half of the collapsed library, with **Queue** (the current prepared/playing session) and **Recently played** (up to 50 available local tracks). Enter/double-click plays a selected row; context menus reuse library actions. Queue order and repeated tracks are preserved. **File → Recently played** opens the history tab directly. Expanding the library closes the dock. **View** offers working library sort and player-bar visibility controls; unfinished menu actions are disabled and labelled by tooltip.

Open **Playback → Audio & equalizer…**, or the EQ button beside the volume slider, for a ten-band EQ and presets. Moving a band or preamp automatically enables EQ; bypass remains available. Changes affect the playing track immediately and continue updating while you drag. Enabling flat EQ at a 0 dB preamp keeps the same level as bypass; boosted bands do not automatically turn down the whole track. The preamp provides manual attenuation. **Save** remembers your settings; **Cancel** restores the saved sound. The playbar shows a miniature saved EQ curve. ReplayGain explanations are in the **?** hover tooltip instead of occupying the editor.

Volume uses VLC's **0–150%** scale, with 100% as the normal level and amplification above it. The JavaFX fallback supports up to 100%, and its slider shows that limit. **ReplayGain** can use existing track or album tags with VLC; its changes apply at the next track start after saving. Untagged files keep their level. JavaFX supports EQ but does not provide ReplayGain here. Audio settings are saved beside the library in `audio.properties`, including when `sangitam.dataDir` selects an isolated data folder.

The spectrum button toggles a panel below the playbar. It displays real decoder spectrum with JavaFX and clears on pause/stop. The current VLC adapter does not expose samples and shows that limitation instead of simulated animation.

**Tools / Help → Keyboard shortcuts…** configures 16 app commands. Click a binding and press a modified key or function key; Clear disables it, Restore defaults resets it, and duplicate keys must be resolved before saving. Bindings persist in SQLite and update menu accelerators. With the default binding, Space plays or pauses even after clicking a button, list, or slider. Editable text fields keep Space for typing. Holding Space toggles playback once per press. Hardware media keys are separate from these app-local bindings. Unsupported menu commands no longer intercept Undo/Redo/Select-all keys.

On Windows 10/11, `build.bat` also compiles the small SMTC companion using the installed .NET Framework compiler and Windows metadata, without downloading another toolchain. It connects the Windows media panel to Play/Pause, Stop, Previous/Next, seeking, shuffle/repeat, track metadata, and the timeline. If that optional companion cannot build/start, in-window controls and the global media-key fallback remain available. Windows volume keys control system volume; the playbar controls Sangitam's gain. Native Windows media-panel behavior is wired but awaits interactive verification; no desktop session was started for the automated checks. Learn the implementation and its limits in [Playbar, artists, and shortcuts](docs/PLAYBAR_ARTISTS_AND_SHORTCUTS.md).

The custom importer recognizes MP3, WAV, FLAC, OGG/OGA, AAC, M4A, AIFF, and WMA files. VLC/libVLC supplies broad decoding when installed; the JavaFX fallback may not decode every listed format. Audio analysis currently remains limited to formats supported by Java Sound and MP3SPI.

During import, Sangitam estimates **energy** from loudness and movement, and **movement** from short changes in volume and sound texture. For longer songs it samples the opening and a later passage, so a quiet intro is less likely to classify the whole track. Existing libraries get the revised analysis in the background on the next launch. Genre comes from embedded file metadata; when absent, an explicitly named genre folder such as `Rock` or `Jazz` provides a hint. Other tracks remain **Genre untagged**. Right-click a song and choose **Edit details → Basics** to set genre or explicitly apply energy and movement ratings. The classical movement field identifies a part of a work and is separate from this activity score. Corrections are saved in Sangitam and survive rescans; the original audio file is not changed. Audio that cannot be decoded remains **Not analyzed**. Analysis is local and does not upload audio.

On **Listen Now → Shape the session**, choose Focus, Explore, or Unwind, a mood palette, start and end energy, and a duration. **Build my journey** creates an unsaved track list. Select a point on the energy plot or a recording to see its selection reason, replace a slot, reorder, remove, or add recordings. **Play journey** starts playback and **Save journey** stores a regular playlist, which you can find in Library. **File → Export → Export current playlist…** writes an M3U8 with local file paths.

**Auto** can suggest a starting energy and mood from recent playback in this session, or from your own historical listening patterns at similar local times and dates. Clear the context checkbox to set the start yourself. Use a song, album, or artist’s **Taste profile** menu to favour, downrank, avoid, or reset recordings. The journey also offers taste controls. Ratings persist independently of likes and pins.

**Deep analyse library** fills in estimated BPM, key, RMS and peak level, dynamics, spectral brightness, and mood for supported local audio. See **Edit details → Analysis** for the measured passage and evidence. The session planner also uses editable instrumentation tags. Tempo, key, and mood are estimates and can be uncertain for orchestral recordings, rubato, or long pieces with changing sections. Learn the implementation in [Artist enrichment and mood sessions](docs/ARTIST_ENRICHMENT_AND_MOOD_SESSIONS.md).

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

VLC-backed playback depends on a compatible VLC installation. Playlists and smart rules use local file references. Audio traits are estimates from bounded passages, so complex songs can still need manual correction. In-app OAuth sign-in, cloud playback, and broader automatic metadata enrichment remain future work. Provider catalog results open in their official services. Rediscover account sync reads bounded recent history from connected sources.

The project uses OpenJFX Media, vlcj/libVLC, JNativeHook, SQLite JDBC, Jaudiotagger, MP3SPI (including its decoder dependencies), and Gson for provider JSON. Review their licenses and include required notices before distributing a release build.
