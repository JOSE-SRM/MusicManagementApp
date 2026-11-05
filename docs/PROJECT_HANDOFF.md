# Sangitam project handoff

**Purpose:** Give a new development conversation enough context to take over this repository without assuming that the previous implementation is complete or that the reported UI behavior is correct.

**Snapshot date:** 2026-11-04  
**Checkout observed:** branch `codex/sangitam-takeover`, HEAD `d59fdd4` (`Complete sessions and playlists, improve tags and playback startup`). The checkout has pre-existing modified and untracked files. This document describes that working tree, not just the commit. Re-check `git status` before editing or switching branches.

## 1. What Sangitam is intended to become

Sangitam is a Windows-first, Java Swing desktop music player. The user wants a polished dark interface, quick startup, a responsive local library, reliable playback and seeking, editable playlists and mood sessions, classical-music-aware metadata, global media controls, and provider catalog discovery. Local playback and data ownership are the foundation. Provider playback must follow official service rules; where in-app playback is not permitted, Sangitam should hand off to the provider's own app or page.

The original feature prompts describe a broad end state, not a claim about what this checkout already delivers. Development should proceed in stages, with the three user-reported usability issues below at the front of the queue.

## 2. Current checkout and how to launch it

### Repository state

At the snapshot, the branch is `codex/sangitam-takeover` at `d59fdd4`. The working tree already contains uncommitted edits to the README, architecture notes, Maven configuration, launcher, source, and one test, plus untracked user/project files including `env.txt` and `run2.bat`. Additional source files for VLCJ playback, the custom browser, settings, credential storage, and media keys are also untracked. These changes predate this documentation task. Preserve them; inspect their contents and status before making changes. Do not publish or copy credentials from `env.txt` or elsewhere.

The November 5 library overhaul passed the clean build and 34 Maven tests. Windows checks used an isolated library to exercise embedded artwork, playlist creation and searchable addition, drag-and-drop, collection pages, queued playback, and automatic collapse. Placeholder-label repair was also checked against a read-only copy of the reported catalog, where all twelve affected entries were recovered.

### Build and run on Windows

Install JDK 21 or newer, open a terminal at the repository root, then:

```bat
build.bat
run.bat
```

`build.bat` invokes the Maven wrapper for a clean compile with tests skipped. `run.bat` launches existing compiled classes and exits with a build reminder if the expected class is missing. Build after changing source; launching alone does not rebuild. Run the existing test suite with `mvnw.cmd test` when verification is requested. On macOS/Linux the README documents `make run`, `make compile`, and `make test`.

The current player uses VLCJ/libVLC when native VLC can be discovered and falls back to JavaFX Media otherwise. VLC is not bundled. The fallback does not support as many formats. The launcher is a development launcher, not a packaged app with a bundled runtime. Database and preferences live outside the source tree under the user's application data/preferences locations; `sangitam.dataDir` can select an isolated database directory.

## 3. Architecture and data flow

| Area | Current role and boundaries |
|---|---|
| `app` | `SangitamApp` starts credential preload, installs the theme, shows `MainFrame`, then defers workspace loading until after first paint. This is asynchronous startup, but no measured sub-500 ms guarantee exists. |
| `ui` | Swing screens, navigation, menus, dialogs, library panel, session composer, discover/search views, player bar, and custom controls. `MainFrame` coordinates selection, queue state, and services. |
| `model` | `Track` identifies a local track by normalized absolute path and holds basic title, artist, album, duration, format, and genre. `TrackTags` holds measured energy/activity plus analysis freshness. `Playlist` is a named ordered list of local paths. |
| `library` | `LibraryScanner` recursively scans selected sources and extracts basic tags. `AudioAnalyzer` estimates energy/activity. `LibraryService` uses `SwingWorker` for import, database work, and analysis, then publishes catalog snapshots. `TrackSearch` filters the loaded catalog. |
| `storage` | `LibraryStore` owns SQLite operations and migrations; playback memory/preferences and provider credential abstractions are also in this area. Provider credentials use Windows Credential Manager support with environment-variable development fallback. |
| `playback` | `PlayerService` is the UI-facing transport interface. `VlcjPlayerService` queues VLC commands on a worker and falls back to `JavaFxPlayerService`; playback events are passed as `PlaybackState`. `GlobalMediaKeys` binds global keyboard media events. |
| `session` | `SessionPlanner` scores local tracks against simple session intent/energy/duration preferences and explains its choices. Queues can be edited and saved as named playlists. |
| `discovery` | Spotify, YouTube, Apple Music, and MusicBrainz catalog adapters return metadata and provider links. Catalog results are distinct from playable local tracks. There is no Amazon Music adapter. |
| `history` | Qualified local plays and optional Spotify, Last.fm, and ListenBrainz recent-history sources feed Rediscover favorites. Provider histories are bounded and source-specific. |

### Important threading and persistence facts

- Swing components should be created and changed on the Event Dispatch Thread. Import, audio analysis, SQLite access, catalog HTTP, and workspace loading are intended to happen in background workers.
- `LibraryService.Catalog` currently carries full track, tag, play-count, and manual-tag maps in memory. Large-library database paging is not implemented or benchmarked.
- Audio files remain in their original locations. SQLite stores references and metadata; a missing or moved file can become unplayable.
- Local audio analysis currently estimates energy and activity, not a definitive mood, BPM, spectral centroid, or genre.
- The current schema/model is basic. It does not implement the full classical role hierarchy or 100+ tag vocabulary requested in the prompts.

## 4. Feature status against the two original prompts

**Implemented** means a working feature is represented in the current source and described by the current README. It does not mean every interaction is polished or independently runtime-verified. **Partial** means groundwork exists, the feature is limited, or the implementation has known usability/runtime gaps. **Missing** means there is no end-to-end feature in this checkout.

| Capability | Status | Current state / remaining work |
|---|---|---|
| Dark Swing UI, navigation, screens, theme, icons | Implemented / partial | Main panels and custom styling exist. The library folder tree follows the filesystem hierarchy and uses dark rows, short names, and full-path tooltips. Several menu placeholders and artwork views remain open. |
| Async startup and background I/O | Partial | UI is shown before workspace loading and library work uses background workers. Startup time is not measured; some screens are still built eagerly; no 500 ms guarantee. |
| Import local files and recursive folders | Implemented | The browser supports drive selection and editable folder paths. Scanner accepts MP3, WAV, FLAC, OGG/OGA, AAC, M4A, AIFF, and WMA by extension and reads basic metadata. Decoder support varies by engine and installation. |
| SQLite library, file references, basic tags | Implemented / partial | The catalog supports incremental rescan, unavailable-track status, and persistent local detail edits. General shows embedded artwork with icon fallbacks. Large-catalog paging and richer metadata roles are pending. |
| Search local library | Partial | Search filters the in-memory catalog across imported paths and offers an Available only filter. Database-backed paging and search performance across 100,000+ entries are pending. |
| Local playback controls | Implemented / partial | Play/pause, stop, and seek have been exercised with generated WAVs. Next/previous, logarithmic volume, shuffle, and repeat-current are wired. Wider formats, global media keys, and audio output behavior need their own validation. |
| Audio formats | Partial | Import recognizes WAV, MP3, FLAC, OGG/OGA, AAC, M4A, AIFF, and WMA. Actual playback depends on the selected VLC/JavaFX engine; no format matrix has been verified on the user's machine. |
| Precise seek, instantaneous start/end seeking | Implemented / partial | Click/drag seeking uses the decoder's duration and seekability. The 10-minute WAV checks below measure decoder position updates. Exact endpoints and latency across compressed formats need separate validation. |
| dB volume and equalizer | Partial / missing | Slider maps through a logarithmic gain curve in MainFrame, but full calibrated dB behavior is not established. Bass/mid/treble or 5–10 band DSP equalizer is missing. |
| ReplayGain, crossfade, gapless transitions, spectrum data | Missing | No normalization, crossfade, gapless provider/local transitions, or decoder-driven visualization stream. A live-spectrum disable option is also missing. |
| Mood/audio analysis | Partial | Energy and activity estimates support basic Focus/Explore/Unwind session planning. BPM, RMS profile, spectral centroid, dynamic range, richer mood scores, user-defined mood curves, and a dedicated Mood DJ view are missing. |
| Playlists | Implemented / partial | Local playlists can be created from track context menus, edited, searched, pinned, and moved into nested virtual folders. Albums and playlists open in the main pane. Smart playlists and mixed-source entries remain open. |
| Classical metadata | Missing | No specialized composer/conductor/soloist/orchestra distinction, opus, key, movement, period, form, instrumentation model, or broad auto-tagging vocabulary. |
| Provider catalog search | Partial | Spotify, YouTube, Apple Music, and MusicBrainz catalog search/link adapters exist. Amazon Music is absent. Local-first unified search with fair cloud result interleaving is absent. |
| Streaming and playlist mirroring | Missing by design/current scope | Catalog results open through provider links; Sangitam does not stream them through its generic buffer or mirror provider playlists. Implement only within official provider terms. |
| Credentials/settings | Partial | Settings UI and Windows Credential Manager bridge exist in current untracked/modified work. Test first-run setup, persistence, expiry, errors, and portability. No credential should be committed or written to plaintext config. |
| OS controls and shortcuts | Partial | App shortcuts and JNativeHook global media keys exist in current code. Full Windows System Media Transport Controls/taskbar integration and user-configurable bindings are missing; global behavior needs runtime validation. |
| Custom chooser | Implemented / partial | A dark custom browser lists available filesystem roots, accepts editable absolute folder paths, and remembers the last folder opened. Mapped/network paths and removable drives need runtime coverage. |
| Cover art/dashboard carousels | Partial | General displays embedded local covers with a bounded cache and icon fallbacks. Dashboard carousels remain open. |
| Analytics | Partial / missing | Local/provider recent listening events and Rediscover views exist; no analytics dashboard with minutes, source mix, genre, classical period, or mood charts. |
| Windows app packaging | Missing | No stable packaged installer/runtime, bundled VLC deployment, or release-quality application icon/package workflow has been verified. |

## 5. Stage 0 investigation and verification

The original reports and investigation are recorded below. The Windows checks used generated WAVs and an isolated SQLite library and preference store.

- **Drive navigation and import.** Verified selecting D: from C:, importing one WAV from D:, and reopening the same folder and library after restarting. Verified entering an E: folder path with Enter and importing a WAV in that folder and another in a subfolder. The Search view listed tracks from both drives. A nonexistent folder showed an error and kept the previous listing.
- **Play/pause and seeking.** Verified the VLC-backed Swing controls show Pause during playback, keep the current position when paused, seek while paused, resume from the new position, and reset to zero on Stop. Next and Previous switched tracks, shuffle picked a different track at the end, and repeat restarted the current track. Direct checks on VLC 3.0.24 and JavaFX Media covered saved start, duplicate pause, seek and resume, stop and restart, rapid track replacement, missing and corrupt files, and recovery with a valid file. In the 600-second stereo PCM WAV, repeated paused seeks from 1.2 to 598.2 seconds appeared in decoder snapshots within about 110 ms on this machine. Those timings are for reported positions. Both engines played the repository's short MP3 fixture. VLC also reported the requested gain values during WAV playback.
- **Remaining verification.** Exercise disconnected, removable, and network locations and measure seek and audio output across more formats. VLC discovery failure and automatic JavaFX fallback selection need a separate Windows launch check.

### P0 — Music chooser only browses C drive

**Observed:** User has their music on another drive. The supplied screenshot shows the chooser displaying `C:\` and the library pane behind it already containing paths under `D:\Music\Downloaded\Classical\...`.

**Confirmed in the original source:** The chooser offered only Up and displayed a read-only path. Up had no parent at a drive root. `LibraryScanner` accepted user-selected paths from any drive.

**Conclusion:** The screenshot supports a chooser navigation defect, not proof that the indexed library or its search query is limited to C. Confirm separately whether the Search screen searches already imported tracks on all paths.

**Fix:** List available filesystem roots in the Drive control and accept absolute folder paths. Directory listing, file type checks, and preference reads and writes run on background workers. A failed navigation keeps the previous listing and shows a path, drive, or permissions message.

**Acceptance:** Starting at C, select a folder and a file on D (and any available additional drive), import recursively, restart the app, and confirm the location can be revisited. Confirm inaccessible/unmounted drives report a useful message and existing D-drive library items remain searchable/playable.

### P0 — Play button stays as Play; second click restarts

**Observed:** Audio is heard, but the button does not become Pause. A second click starts the song from the beginning.

**Confirmed in the original source:** The VLC ready callback could publish LOADING after the playing callback, and subsequent time updates preserved that status. MainFrame could respond to a Play/Pause press in LOADING by opening the selected track again at zero.

**Fix:** Sample native VLC state on the command thread and scope end/error events to the active media player. The JavaFX adapter scopes its callbacks to the latest open request and queues commands in submission order. Play/Pause is disabled in LOADING. Its icon, tooltip, and accessible name follow the reported transport state.

**Acceptance:** With each supported engine, start a local file and confirm the control transitions to Pause when audio begins; pressing again pauses at the current position, and pressing once more resumes there. Repeat during loading and immediately after track changes. Confirm errors and missing files leave a usable control state.

### P0 — Playhead cannot be moved

**Observed:** User reports that seeking does not work. Screenshot shows `2:34 / 6:41` while the status text says “Loading audio...” and the visible player icon is still Play.

**Confirmed in the original source:** The seek bar stayed disabled as long as the UI received LOADING snapshots. The displayed metadata duration could differ from the decoder duration.

**Fix:** The decoder reports seek availability and supplies the seek duration. Clicking and dragging preview a position immediately and submit one seek on release. The displayed and saved positions reconcile from decoder updates.

**Acceptance:** Drag and click the playhead near the beginning, middle, and end of a long local file, in both directions. The displayed time and audible location should follow promptly without restarting the file. Test while paused, while playing, after resume, and on each supported decoder. Confirm the slider cannot issue invalid seeks when duration is unknown.

### Other usability and quality risks

- Many requested controls exist only partially, and some menu entries/actions are placeholders. Treat visual presence as insufficient; trace each action to a working result.
- Long metadata/file names, unsupported formats, network locations, moved files, duplicate imports, and permission failures need clear feedback.
- Eager screen construction, full-catalog in-memory filtering, and artwork memory use can undermine the performance goals. Measure before claiming fast startup or 100k-track support.

## 6. Security and provider constraints

- A provider credential was previously present in an untracked environment file and was removed from its contents in earlier work, but it has **not been confirmed rotated**. Rotate any exposed key/token in its provider account console. Do not recover, quote, or commit the old value. Check `git status` and ignore rules before any commit.
- Windows Credential Manager support is present in the current worktree; verify it on Windows and avoid logging secret values. Environment variables remain useful for local development but should not be saved to the repository.
- Spotify, Apple Music, YouTube, and Amazon Music integrations must follow their current official terms, authentication rules, rate limits, and playback restrictions. Catalog search and external playback handoff are the current safe boundary. Do not promise a universal mixed-provider queue through Sangitam's decoder.
- Amazon Music has no adapter in this checkout. Its access and API eligibility must be researched before planning implementation.
- MusicBrainz enrichment should be opt-in; do not upload user audio for local analysis or metadata work.

## 7. Staged roadmap

### Stage 0 — Stabilize reported daily-use behavior

1. Drive selection/import and remembered paths are fixed. The remaining drive cases are listed in the verification notes.
2. Play/pause state and repeated startup presses are addressed for both decoder adapters. Verify automatic fallback selection on a Windows installation without VLC.
3. Click and drag seeking are fixed. The long WAV measurements are recorded above. Verify exact endpoint behavior and latency across more formats.
4. Missing and corrupt tracks and inaccessible import paths report errors. The Windows checks covered the local transport controls. SQLite migrations and playback history schema were preserved.

### Stage 1 — Reliable local engine and library

1. Verify VLC/libVLC packaging and seek latency on Windows before adopting it as the release decoder. Define supported format behavior for WAV, MP3, FLAC, OGG, AAC, M4A and other practical local formats; report format/runtime differences honestly.
2. Implement robust decoder lifecycle, stop, calibrated dB volume, multi-band EQ, ReplayGain, local crossfade and direct URL/HLS/radio only where the chosen engine supports them reliably.
3. Incremental rescan and unavailable-file handling are implemented. Use **Library → Rescan library** to check registered sources and indexed paths. Rescan reuses metadata by size and modification time, updates existing canonical paths in place, and preserves unavailable rows. Legacy libraries need to add a folder once to register it for discovery. Identical files at different paths are separate tracks. Database-backed paging/filtering and large-catalog benchmarking remain open.
4. Placeholder label repair is implemented. The twelve reported MP3s had an embedded `tmp` title and no usable artist tags. Their filenames and `Classical\Surname, Given` folder names supply the display labels. Valid embedded labels take priority, and startup refreshes affected rows in place. Expand the metadata schema for bitrate, artwork references, and separate classical roles in a later change.

### Stage 2 — Metadata, playlists, and approachable UI

1. Provide editable user tags and conservative automatic classical classification for forms, periods, instrumentation, movements, keys, and opus. Add 100+ flexible tags incrementally; support opt-in MusicBrainz enrichment for unresolved metadata.
2. The General and Locations library tabs, nested virtual folders, playlist context menus and drag-and-drop, pins, and album and playlist pages are implemented. Smart playlists and database-backed paging remain open.
3. Replace confusing placeholders with working menu actions and clear labels. Add artwork and dashboard sections for recently played and favorite playlists; use bounded caches and virtualized lists.
4. Add configurable app shortcuts, accessible shuffle/repeat controls, and a live-spectrum on/off option. Keep global media-key handling distinct from full Windows media transport integration.
5. Build the custom chooser into a complete drive-aware experience, retaining last-location preferences and clear file/folder selection modes.

### Stage 3 — Mood sessions, providers, and analytics

1. Extend local analysis to BPM, RMS energy, spectral centroid, and dynamic range. Map these into editable mood scores, then create a session view with target mood and a user-defined mood curve.
2. Support source-aware playlist metadata and fair local-first unified catalog search. Keep official provider playback in the official application/surface or link out when terms do not permit playback in Sangitam.
3. Implement playlist mirroring as metadata/references only where provider APIs allow it; gracefully handle expired authentication, rate limits, unavailable items, and permissions.
4. Add local analytics for listening minutes, source mix, genre, classical periods, and mood histograms. Make provider history optional and label sources accurately.
5. Defer Amazon Music until legitimate API access and usage terms are confirmed. Do not make cross-provider gapless playback a requirement unless every provider explicitly permits that design.

### Stage 4 — Windows release packaging

After local behavior and dependencies are stable, build a Windows package with an app icon and an appropriate runtime. Decide how to satisfy native VLC deployment and licensing/notices, then verify clean-machine install, first boot, upgrade behavior, and launch without rebuilding.

## 8. Definition of done for major changes

- The behavior works through the visible UI from the user's perspective, not only through a service method or mock screen.
- The relevant work stays off the Swing Event Dispatch Thread; failures, missing dependencies, and unsupported media have understandable outcomes.
- Existing user library and saved state survive migrations and upgrades.
- Claims about latency, formats, scalability, shortcuts, or provider support are measured or explicitly marked unverified.
- `README.md`, `docs/ARCHITECTURE.md`, and this handoff agree with the implementation where their scope overlaps. Update this handoff when a major reported defect changes status or a roadmap decision is made.
- Report which files changed, what was verified, any unverified runtime behavior, and any follow-up the user must perform (such as rotating an exposed credential).

## 9. Instructions for the next conversation

Start by reading this file and `AGENTS.md`, then inspect `git status` and the current source. Use the Stage 0 verification notes for remaining cases before working through Stage 1 in order. Preserve user files and credentials.

