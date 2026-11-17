# Sangitam: innovation research and RACE implementation prompt

**Prepared:** 1 November 2026  
**Decision stage:** Proposal only; no application code changed  
**Repository state reviewed:** `codex/modular-swing-ui` at `a681d65`

## Recommendation

Build Sangitam into a **personal listening studio for music people own**. Its first complete journey should be: **import a folder → find a track → shape a timed session → inspect and edit the sequence → play → save**. Keep the visible interface in Java Swing, as previously requested, and make the rules and data flows understandable enough for the team to explain. Treat a different UI stack, streaming service or cloud backend as an explicit future decision, since the team is open to broader ideas.

The distinctive part should be the *session arc*: the user chooses a purpose and duration, sees how the energy changes over time, understands why each song is in the queue, and can change it. The current Session Composer already gives this direction a recognizable starting point. This is a proposed product hypothesis, not a proven market need; test it with listeners before investing in advanced recommendation models.

## What the code currently does

The repository has 16 Java files in `src/sangitam/desktop/`, a Makefile, `build.bat`, a README and architecture notes. `SangitamApp` starts Swing on the Event Dispatch Thread. `MainFrame` wires a dashboard, search, right library, player bar, menus, import dialog, notification center and command palette. `Theme`, `UiFactory` and `AppIcons` share styling. The dark palette and typography are coherent, and the library and soundscape animate with Swing timers.

The central product flow is still simulated:

| User action | Current behavior | Needed for a real product |
|---|---|---|
| Choose files/folder | `JFileChooser` accepts either, then the dialog reports a count | Scan supported files, read metadata, persist the result and show real progress/errors |
| Search | A document listener announces typed text | Filter the imported library and show result count and empty states |
| Build session | Calculates a label and estimated track count | Select and order actual tracks within a duration target; explain selections |
| Play/pause/seek/volume | Changes icon, slider or notification | Control an audio engine with truthful state and error handling |
| View library | Static playlists, folders and 68% scan progress | Shared, persisted library and playlists; remove fake scan progress |
| Choose menu actions | Mostly notifications | Wire actions to features as those features become real |

There is no domain model, persistence, audio engine, metadata scanner, real search or automated test suite. The clean build in the prior status review passed. The current branch is aligned with `origin/codex/modular-swing-ui`; the reports and `work/` assets are untracked, so they should not be assumed to be part of a release.

## What external research changes

1. **Prompted playlists are already a mainstream feature.** Spotify's Prompted Playlists accept a natural-language idea and rules, show notes about why songs fit, and permit refinement. Therefore Sangitam should not claim that prompting or explanation alone is unique. Its credible differentiation is an offline collection, a visible time/energy arc, precise user control and a presentation-friendly deterministic engine. [Spotify Prompted Playlists](https://support.spotify.com/uk/article/prompted-playlists/)
2. **Local-library intelligence also exists.** Plexamp analyzes a user's files for sonic similarity, radios and mixes; that proves local music can support discovery, but it also raises the bar for a generic “smart playlist” claim. Plex says sonic analysis can take hours or days. We should begin with user tags, metadata and play history, and consider optional audio analysis later. [Plex sonic analysis](https://support.plex.tv/articles/sonic-analysis-music/)
3. **An opaque AI feature is a risky first investment.** Plex's recent release notes say it removed its Sonic Sage assistant because the output was not dependable enough. This is one vendor's judgment, not proof that AI never works. For Sangitam, deterministic and editable sessions make a stronger first demonstration. [Plexamp release notes](https://forums.plex.tv/t/plexamp-v4-50-3-ready-or-not/942338)
4. **A typical library requires a codec decision.** Oracle says standard Java Sound readers do not support MP3 without a service provider. An MP3 Java Sound SPI exists, but its current project documents extra runtime flags and package access considerations. JavaFX MediaPlayer supports play/pause/seek/volume and MP3, but adds another runtime and thread model. A small playback proof on the team's actual machines is needed before selecting a backend. [Oracle Java Sound](https://docs.oracle.com/en/java/javase/17/troubleshoot/java-sound.html), [MP3SPI](https://github.com/umjammer/mp3spi), [OpenJFX MediaPlayer](https://openjfx.io/javadoc/18/javafx.media/javafx/scene/media/MediaPlayer.html)
5. **Desktop growth does not initially require a server.** SQLite JDBC supports common desktop systems; SQLite FTS5 is available if simple indexed search later proves insufficient. Long folder scans belong in `SwingWorker` or equivalent background work, with UI updates returned to the Event Dispatch Thread. [SQLite JDBC](https://github.com/xerial/sqlite-jdbc), [SQLite FTS5](https://www.sqlite.org/fts5.html), [Oracle SwingWorker](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/worker.html)
6. **External services bring constraints.** MusicBrainz can enrich metadata, but requires a meaningful User-Agent and no more than one request per second. Spotify Platform streaming is restricted to Premium users and its developer policy limits use of content and data. Neither should be the foundation of the first working release. [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API), [Spotify Developer Policy](https://developer.spotify.com/policy)
7. **Visual polish must remain usable.** W3C's 4.5:1 text contrast target and visible keyboard focus are useful audit criteria for the dark theme; Oracle recommends accessible names for icon-only Swing controls. Native `jpackage` bundles can follow when the core app works, but each target platform needs its own build. [W3C text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum), [W3C visible focus](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible), [Oracle Swing accessibility](https://docs.oracle.com/javase/tutorial/uiswing/misc/access.html), [Oracle jpackage](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html)

## Product bets to consider

| Direction | User value | Distinctive angle | Main challenge | Recommendation |
|---|---|---|---|---|
| **A. Listening studio** | Builds useful sessions from a personal collection | Timed, editable energy arc with honest reasons | Reliable local playback and enough track data | **Choose as core**: closest to current prototype and strongest demo |
| **B. Library rescue** | Repairs duplicates, missing tags and scattered folders | Collection health report with reversible fixes | Accurate metadata and safe file operations | Add after core; could become a second signature feature |
| **C. Shared listening room** | Friends create a queue together | Social co-curation around a session | Accounts, networking, sync and music rights | Defer until real users ask for it |
| **D. Streaming platform integration** | Access to large catalogs | Cross-service session planning | API quotas, authentication, policy and playback restrictions | Optional adapter after the offline product works |

These are strategic comparisons and design inferences from the repository and cited products, not measurements of user demand.

## Recommended build sequence and proof

**Slice 0 — product and playback proof.** Agree on a minimum Java version and target OSs. Test one short, legally owned MP3 and WAV file with candidate backends for play/pause, seek, volume, end-of-track, recovery and packaging. Decide dependencies and licenses from evidence. Sketch the `Track`, `Library`, `Queue` and `Player` boundaries. No new UI screen is needed.

**Slice 1 — first working vertical journey.** Persist imported files/folders in one local library; show real import results and errors; support real search; play at least one verified format through the existing player bar. A reviewer should be able to close and reopen Sangitam and still find and play the imported track. Keep file work off the Swing thread.

**Slice 2 — session intelligence.** Generate a real, editable queue from library data and user-controlled tags/ratings. Use an explicit scoring rule and a simple warm-up → peak → landing arc. Show the requested and actual duration, why each track was selected, and what could not be satisfied. Save and reopen sessions. Do not pretend to infer mood from filenames or genres.

**Slice 3 — product quality.** Test empty and small libraries, missing or moved files, duplicates, unreadable files, cancellation, playback failures, keyboard-only use, larger fonts, narrow windows and reduced motion. Package an installable app with `jpackage` on each supported OS.

**Slice 4 — validated expansion.** Interview or observe a small set of listeners. Choose one additional bet based on the strongest recurring pain: collection repair, optional metadata enrichment, listening journal, device sync or social sessions. Avoid a cloud service until cross-device collaboration or sync is genuinely required.

Proposed measurements, to be tested on a documented machine and sample set: import 1,000 tracks without freezing the UI; search 10,000 local tracks promptly; produce a duration-bounded queue without duplicates; reopen a saved session after restart; complete the main journey by keyboard. These are targets for evaluation, not current performance claims.

## Architecture that can scale without becoming hard to explain

Keep `sangitam.desktop.ui` for Swing panels. Add small non-UI packages only when the first vertical journey needs them: `model` for plain data, `library` for scanning and metadata, `storage` for persistence, `playback` for an audio adapter and `session` for queue rules. One shared application service should own library and player state; panels observe that state instead of keeping separate sample arrays. Use background work for scanning, metadata and decoding, then update Swing models on the Event Dispatch Thread.

Choose SQLite if persistence and larger collections justify it; start with ordinary indexed queries and measure before adding FTS5. Use a dependency manager when external codecs, metadata readers and tests make the current hand-written classpath brittle. Maven can be configured to retain this repository's `src/sangitam/desktop/...` layout, although its default layout is different. [Maven layout documentation](https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html)

## Copy-ready RACE prompt

RACE here means **Role, Action, Context, Expectations**. The last word has variants in published examples; the four fields below define exactly what this prompt means.

> **Role** — Act as a senior Java desktop engineer, music product designer and technical mentor for a student team. Make every major choice understandable and demonstrable in a code review.
>
> **Action** — Turn Sangitam from a polished Swing prototype into a useful, distinctive music app. Inspect the repository first. Compare at least three product directions: (A) a private listening studio with timed, editable session arcs, (B) a collection repair and organization tool, and (C) a social or streaming-connected experience. Recommend one primary direction using user value, differentiation, technical feasibility, ongoing cost, rights/API constraints and team explainability. Treat direction A as the starting hypothesis, not a foregone conclusion. Once the user approves the chosen direction, implement it in small, working vertical slices.
>
> **Context** — The repository is `JOSE-SRM/MusicManagementApp`, currently on `codex/modular-swing-ui`. It has 16 Java files under `src/sangitam/desktop/`, a Makefile, a Windows batch build, and a black/light-blue Java Swing interface. `MainFrame` composes the dashboard, search, library, player bar, import dialog, menus and command palette. The Session Composer and SoundscapeCanvas preview an intent, energy and duration. Real audio, imported library data, search, metadata, persistence and queue generation are absent. Earlier requirements favor an all-Swing visible UI and code the team can explain; the team is open to ambitious music features. Propose a UI stack change separately with evidence before making one.
>
> **Expectations** — First present a decision brief with competing options, evidence and a concrete first slice. Stop for explicit user approval before changing application code. After approval, keep a runnable app at each checkpoint; make import, search, playback and saved state real before describing the app as a music player. Keep the UI responsive by moving file and audio work off Swing's Event Dispatch Thread. Use a small model/service/storage boundary; hide codec choices behind an audio interface. Make the Session Composer choose actual tracks by transparent, testable rules, with user editing and honest fallbacks. Check accessibility, keyboard flow, empty states, error recovery, dependencies and license/API restrictions. Verify the first slice with representative audio files and focused tests, then report what works, what remains and how the team can demonstrate it. Do not claim a feature is innovative merely because it uses AI, playlists or explanations; compare it with current products and test the user value.

## Approval boundary

Approval of this proposal authorizes **Slice 0 and Slice 1**: select a supported playback backend with a proof, then deliver a stored local library, real search and basic playback through the current Swing interface. Slices 2–4 are the proposed roadmap and remain open to user feedback. No application code should change until the user approves.
