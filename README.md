# Sangitam desktop

Sangitam is a local music library and listening workspace built with Java Swing. It imports common local audio formats, saves metadata and measured tags in SQLite, searches the collection, and plays selected tracks. The Session Composer builds an editable, playable queue that can be saved as a named playlist. A separate Discover screen searches online catalogs and opens results with their providers.

To learn how it works, start with the [implementation guide](docs/IMPLEMENTATION_GUIDE.md). It follows a song through import, metadata overrides, SQLite paging, Swing workers, and playback, with source links, test commands, and small exercises.

## Rediscover favorites

Open **Rediscover** in the left navigation to see **Most played** and **Forgotten favorites**. Sangitam records a local play after you actually listen to half the track or four minutes, whichever is shorter. The in-app collection groups songs by normalized title and artist, merges reports of the same play from different sources when their timestamps are within two minutes, and keeps separate repeats from the same source. A forgotten favorite has at least three known plays and no observed play in the last 30 days. Old local play counts from earlier releases are preserved, but their last-played date is unknown, so they do not enter the forgotten list until a dated play is observed.

**Save & sync** connects optional histories. Enter your public ListenBrainz or Last.fm username on the Rediscover screen; usernames are remembered on this computer. Spotify needs a token with the additional `user-read-recently-played` OAuth scope; Last.fm needs an API key configured in Settings. Each source is optional and syncs separately; an expired token does not discard other sources' results. Syncs run in the background and repeat syncs do not add the same provider event twice. The screen offers **Play local file** when a matching imported song exists and **Open source** for a provider link. **Export collection** writes a new UTF-8 CSV into a folder you choose, without copying or downloading music.

**Listening insights** shows measured time on this device by genre, classical period, and estimated mood. Choose **All time** or the last **7**, **30**, or **90 days**. The daily trend shows local listening minutes and recorded play counts in separate columns. All-time totals use lifetime measurements and show the last 30 days in the trend. Open the tab or choose **Refresh insights** to load the latest saved totals.

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

**General** mixes songs, artists, albums, playlists, and virtual folders. It shows embedded cover art when available, with icons for other entries. Use the search field to filter the active tab. General defaults to **Recent**, based on when tracks were added or played and when collections were updated. Switch to **Alphabetical** or **Most played** from the same toolbar. Pins appear first. **Locations** shows the actual drive and folder hierarchy. Hover a row for its full path, press Enter to open a folder, or double-click a track to play it. The expand button gives the library more room, and navigating to another screen contracts it.

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

Double-click a track in Listen Now’s recent shelf, search results, or Library to play it. The playbar shows artwork, the title, and linked musician credits. Click the title or cover to open the tagged album or the song’s details. Click a credit to open the artist page.

Use the heart or **Like song** in a song’s context menu to add it to **Liked Songs** under Library → General → Playlists. Use **Pin to library** to pin it independently. On upgrade, existing song pins become likes. To recover a previous pin, right-click its song and choose **Restore former song pin**.

The position row shows elapsed, remaining, and total time. Click or drag the rounded seek bar to preview a position and release to seek. Play/Pause is disabled during decoder startup. On the next launch, the selected song returns paused at its saved position. Volume follows VLC's percentage scale, is colored by range, supports mute/restore, and is saved locally, including the final change on a normal close. Continuous volume requests are coalesced on the decoder thread rather than building a backlog; audible responsiveness still needs a runtime check. Missing files report an error without removing their library/history references.

Artist pages show your ten most-played featured tracks, liked songs, albums in the local library, and musicians credited on the same tracks. Play or shuffle the artist's local music. Opening an artist automatically looks up a Wikipedia biography and, when available, a freely licensed Wikimedia Commons portrait. The profile shows a short preview with **Read more**, source links, and portrait credit. It caches the profile and image locally. Confirmed matches group aliases such as Beethoven under Ludwig van Beethoven. **Refresh / match…** accepts a fuller name for ambiguous results, **Online profile** remembers your automatic lookup preference, and **Edit artist profile…** saves local corrections. The online-catalog link opens a prefilled search; you choose the provider and explicitly search. These local albums are not a complete or globally ranked discography. Device listening time measures playback from this update onward, ignores seek distance and pauses, and retains credited totals after library removal. Earlier play counts cannot reconstruct historical listening minutes.

The **Queue** button opens the bottom half of the library rail. Press Enter or double-click a row to play it. **File → Recently played** opens the last 50 available local tracks. Expanding the library closes the dock.

With **Shuffle** on, **Shuffled queue** shows the current track and the upcoming order. **Next** and end-of-track advancement use that order. **Previous** revisits tracks that started successfully, and **Next** retraces them before taking another upcoming track. New queue additions go after existing upcoming entries. Removing an entry drops it from the shuffle, and reordering a session changes its regular order. A shuffle pass stops at the end. **Repeat current track** loops the current recording until you advance or turn Repeat off.

Open **Playback → Audio & equalizer…** or the EQ button beside the volume slider for a ten-band EQ and presets. The editor stays open while you select tracks, seek, and change volume. Opening it again brings the same editor forward. Moving a band or the preamp enables EQ and previews it immediately, including across track changes. Flat EQ at a 0 dB preamp keeps the same level as bypass. **Save** remembers your settings. **Cancel**, Escape, or closing the editor restores the saved EQ. The playbar shows the saved curve.

Volume uses VLC's **0–150%** scale, with 100% as the normal level and amplification above it. The JavaFX fallback supports up to 100%, and its slider shows that limit. **ReplayGain** can use existing track or album tags with VLC; its changes apply at the next track start after saving. Untagged files keep their level. JavaFX supports EQ but does not provide ReplayGain here. Audio settings are saved beside the library in `audio.properties`, including when `sangitam.dataDir` selects an isolated data folder.

The spectrum button toggles 96 slim bars under the playback controls. Each bar has a marker that briefly holds its recent peak. Right-click the spectrum or its button, or open **View → Spectrum**. Under **Color scheme**, choose a preset such as **Blue to red**, **Green to yellow**, or **Dark yellow to gold**, or pick **Primary color…** and **Secondary color…** individually. Gradients run from primary at the low end to secondary at the high end. **Exponential** gives strong peaks more height, and **Extend peaks** adapts the height range for loud passages. Sangitam remembers your appearance choices. VLC shows the spectrum before audio effects. The bars and markers clear on pause, stop, seek, and track change.

**Help → User guide…** covers importing, browsing, playlists, playback, and troubleshooting offline. Use **View → Show navigation labels** or the button in the left rail to switch between labels and icons. Scroll vertically over Home shelves to move the page. Use Shift with the wheel to move a shelf horizontally.

**Help → Keyboard shortcuts…** configures 16 app commands. Click a binding and press a modified key or function key. Clear disables it and Restore defaults resets it. Resolve duplicate bindings before saving. Bindings persist in SQLite and update menu accelerators. With the default binding, Space toggles playback once per press. Editable text fields keep Space for typing.

On Windows 10/11, `build.bat` also compiles the small SMTC companion using the installed .NET Framework compiler and Windows metadata, without downloading another toolchain. It connects the Windows media panel to Play/Pause, Stop, Previous/Next, seeking, shuffle/repeat, track metadata, and the timeline. If that optional companion cannot build/start, in-window controls and the global media-key fallback remain available. Windows volume keys control system volume; the playbar controls Sangitam's gain. Native Windows media-panel behavior is wired but awaits interactive verification; no desktop session was started for the automated checks. Learn the implementation and its limits in [Playbar, artists, and shortcuts](docs/PLAYBAR_ARTISTS_AND_SHORTCUTS.md).

The custom importer recognizes MP3, WAV, FLAC, OGG/OGA, AAC, M4A, AIFF, and WMA files. VLC/libVLC supplies broad playback support when installed. The JavaFX fallback supports fewer formats. Local analysis uses a bundled FLAC decoder alongside Java Sound and MP3SPI and can run without VLC.

During import, Sangitam estimates **energy** from loudness and movement, and **movement** from short changes in volume and sound texture. Recordings longer than three minutes with a known duration use five 36-second passages. FLAC sampling can seek across the recording, and Java Sound sampling traverses up to the first 30 minutes. Shorter files and files with unknown duration use a bounded continuous passage. Genre comes from embedded file metadata or a clearly named genre folder such as `Rock` or `Jazz`. Other tracks remain **Genre untagged**. Right-click a song and choose **Edit details → Basics** to set genre or explicitly apply energy and movement ratings. The classical movement field identifies a part of a work. Corrections are saved in Sangitam and survive rescans. Analysis runs locally.

On **Listen Now → Shape the session**, choose Focus, Explore, or Unwind, a mood palette, start and end energy, and a duration. **Build my journey** creates an unsaved track list. Select a point on the energy plot or a recording to see its selection reason, replace a slot, reorder, remove, or add recordings. **Play journey** starts playback and **Save journey** stores a regular playlist, which you can find in Library. **File → Export → Export current playlist…** writes an M3U8 with local file paths.

**Auto** can suggest a starting energy and mood from recent playback in this session, or from your own historical listening patterns at similar local times and dates. Clear the context checkbox to set the start yourself. Use a song, album, or artist’s **Taste profile** menu to favour, downrank, avoid, or reset recordings. A like adds a fixed preference bonus, and qualified local plays contribute a smaller, gradual bonus. Preference helps rank session candidates and forgotten favorites. **Avoid** excludes recordings from both.

**Deep analyse library** measures supported local audio. **Edit details → Analysis** shows the estimated pulse, a range when local pulses vary, and key estimates or competing candidates. It also shows saved measurements while a refresh is pending, and identifies unavailable files or analysis failures. Use **Classical / credits → Musical key** to record a known key. The analysis panel shows this saved value alongside the audio estimate. Existing libraries refresh older measurements in the background on the next launch. Choose **Cancel analysis** to stop a batch and keep completed measurements. Choose **Deep analyse library** again to resume missing measurements or retry failures. If you replaced a file, rescan the library to update its file revision and measurements. [Artist enrichment and mood sessions](docs/ARTIST_ENRICHMENT_AND_MOOD_SESSIONS.md)

A known movement key takes priority in the analysis summary. The **Work key** field can describe the overall composition, and **Audio key estimate** describes the sampled recording. The built-in score references cover Beethoven's Pathétique movements and Für Elise. Use **Musical key** to enter a confirmed key for other recordings. [Pathétique score and movement keys](https://imslp.org/wiki/Piano_Sonata_No.8_(Beethoven,_Ludwig_van)) · [Für Elise Urtext](https://www.henle.de/Bagatelle-in-a-minor-WoO-59-Fuer-Elise/HN-1347)

Deep analysis saves suggested mood tags from the audio. You can find them in **Edit details → Tags**, Search, and smart playlist tag rules. Right-click a song and choose **Re-analyse track…** to select distributed passages, the first 30 seconds, or the first 90 seconds. You can enter a musical key, turn suggestions on or off, and review or choose tags before saving. Tags such as Romantic and Anger are available to select. Each reanalysis replaces the selected track's analysis tags. Custom tags and manually set energy and movement ratings are preserved.

Use **Library → Set up managed library…** to choose a managed folder, hierarchy, and import policy. Preview the destinations and space, then activate. Sangitam organizes existing indexed tracks and routes later imports through the same settings. **Copy** creates independent managed files and analyzes them before adding them to the catalog. Use **Locations ▾** to switch between **Source Location** and **Managed Library**. Search and playback use the managed catalog. Sangitam checks imported source folders at startup and every five minutes while open. Use **Rescan library** to check immediately.

Edits and successful background metadata lookups can refile managed tracks. To write supported metadata into your MP3 and FLAC copies, enable **Write verified metadata to managed audio files**. **Hard link to original** shares the original audio data on a supported volume. Sangitam keeps automatic tag writing disabled for hard links. If a category is missing, Sangitam uses the available levels and retries online lookup when **Identify recordings automatically** is enabled.

Startup and **Rescan library** can restore a missing indexed managed file from its recorded original. Playlist order, repeats, and catalog edits follow the managed path. In **Locations**, use a row’s chevron to expand or collapse it, double-click a folder or press **Enter** to open it, and use **Back** to return. The folder menu includes **Collapse folder and subfolders**. **General** puts the category and sort controls together, with **×** beside search to clear it.

### Sheet music

Right-click a track and choose **Download sheet music…**. Appassionata uses a complete engraved Noctave edition. For other works, Sangitam looks for an engraved edition on Mutopia, ranks complete editions through IMSLP’s catalogue, and can use a matching public-domain score on Wikimedia Commons. Sangitam reuses the PDF across recordings of the identified work. When Mutopia lists matching movements separately, Sangitam combines them into one PDF. Downloads run only when requested. [Noctave Appassionata](https://noctavepiano.com/en/free-library/beethoven-sonata-no-23-appassionata-op-57-complete/) · [Mutopia catalogue](https://www.mutopiaproject.org/piece-list.html) · [IMSLP API](https://imslp.org/wiki/IMSLP:API)

Use **Sheet music…** to reopen scores offline. New files are organized under **Sheet Music → Composer → Work** in the managed library or under Sangitam’s local data folder when no managed root is configured. **Sources → Find editions on IMSLP…** lets you pick an edition. If the provider requires its browser flow, use **Open browser and import**, then save the PDF to your Downloads folder. Sangitam looks for the selected filename and imports it when the PDF arrives. You can also attach a PDF from another folder. **Sources** can download a separate historical scan from Wikimedia Commons. **Remove PDF…** removes the catalog association and timings. The file stays in its folder.

The separate score window uses the main player’s shuffle, previous, play/pause, next, repeat, and seek controls. It loads the next recording’s saved score when the main player changes tracks. Press **F11** to enter full screen and **Esc** to return. Use **Ctrl + wheel** to zoom around the pointer or scroll to pan the continuous PDF. Windows trackpad pinch zooms when the driver sends the standard zoom-wheel gesture. The score menu can show the optional note strip. Right-click the track title in the main player to open its song menu.

**Sync recording** matches the printed notes to the actual recording. Downloading a score attempts this automatically when the movement pages are known or the score is short. For a long PDF with uncertain movement boundaries, right-click the score and choose **Sync a page range…**. Sangitam saves the range with that recording’s alignment. The cursor follows recognized notes and you can click those passages to seek. Sangitam ships recognized notation for the Noctave Appassionata first movement. Local Audiveris recognition may still take several minutes on a new scan. When the portable build omits Audiveris, use **Sources → Set up Audiveris…** to select the engine. **Set page time…** remains available for a passage the engine cannot align. [Audiveris releases](https://github.com/Audiveris/audiveris/releases)

On Windows, import and attach use the system file picker. **Files…** and **Folders…** support multiple selections. The score picker opens in Downloads the first time and remembers its last folder. Attached PDFs are shared across an identified work by default. Use **Attach PDF to this recording only…** in the score menu for a recording-specific edition.

## Discover online

**Discover online** searches four catalogs. Select a provider, enter a song, artist, or album, and press **Search catalog**. Double-click a result or use **Open in provider** to open its official page. These results are links and metadata; they are not downloaded, played by Sangitam, imported into the local library, or added to Session Composer. Local analysis and sessions still use only imported files.

| Source | Setup before starting Sangitam | What it provides |
| --- | --- | --- |
| MusicBrainz | None | Recording metadata and a MusicBrainz page. |
| Spotify | `SANGITAM_SPOTIFY_ACCESS_TOKEN` | Catalog tracks and Spotify links. Supply a short-lived OAuth access token obtained with Spotify's [PKCE flow](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow); refresh it when it expires. |
| YouTube | `SANGITAM_YOUTUBE_API_KEY` | Music-category video search and YouTube links. Enable the [YouTube Data API](https://developers.google.com/youtube/v3/getting-started) in your own Google Cloud project. Searches consume quota. |
| Apple Music | `SANGITAM_APPLE_MUSIC_DEVELOPER_TOKEN` | Catalog songs and Apple Music links. Generate the signed [developer token](https://developer.apple.com/documentation/AppleMusicAPI/generating-developer-tokens) outside the desktop app. Optional `SANGITAM_APPLE_MUSIC_STOREFRONT` is a two-letter storefront, defaulting to `in`. |

Configure Spotify, YouTube, Apple Music, and Last.fm credentials in **Settings**. On Windows Sangitam stores them in the signed-in user's Credential Manager, not in SQLite or source files. Environment variables remain supported for development. Spotify and Apple access tokens can expire; an access-denied message means the credential or its permissions need checking. Spotify development-mode apps have account and user limits. See each provider's developer documentation before sharing an integration beyond the team.

For an indexed file, right-click a song and choose **Identify recording…**. Edit the title and choose whether to include known credits, release, work, and duration before selecting **Search MusicBrainz**. Compare candidate credits and durations, then link the recording you recognize. Select a release separately when you know which edition the file comes from. The dialog offers to fill missing fields when the local details corroborate the match. **Remove saved link** removes those additions. Audio files and paths stay on the device.

Enable **Library → Identify recordings automatically** once to look up missing metadata in the background on startup and after imports. Sangitam checks local tags and its work references, then compares MusicBrainz recording and work results. Linking a recording also queues a background work check. Composition fields can come from a verified work shared by different performances. Recording credits and dates require evidence for the local performance. Inspect the results in **Edit details → Classical / credits**.

Key labels in **Classical / credits** and **Analysis** display musical accidentals such as B♭ and F♯. Existing tags keep their stored spelling until edited.

For music recognition when text is inconclusive, install [Chromaprint](https://acoustid.org/chromaprint) and put `fpcalc` on PATH, or set `SANGITAM_FPCALC` to the executable path. Enter a registered **AcoustID application key** in Settings, or supply `SANGITAM_ACOUSTID_KEY` for a development launch. Sangitam sends the fingerprint and duration to AcoustID only during enabled automatic identification. Use **Library → Check recording metadata now** to retry.

Compiled classes mirror the package structure under `bin/sangitam/desktop/`. The library database is stored in the current user's application data folder, not in the Git checkout. For an isolated demo, pass `-Dsangitam.dataDir=/path/to/demo-data` to the Java/Maven process.

## Build and test

```sh
make compile
make test
make clean
```

Windows users can compile with `build.bat`. Tests use generated audio and cover folder scanning, metadata, duplicate paths, persistence and migration, MP3/WAV/FLAC analysis, session selection, search, and the background import-to-Swing update path.

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

The project uses OpenJFX Media, vlcj/libVLC, JNativeHook, SQLite JDBC, Jaudiotagger, MP3SPI (including its decoder dependencies), Gson, jsoup, and Apache PDFBox with JBIG2 and JPEG 2000 image readers. Review their licenses and include required notices before distributing a release build.
