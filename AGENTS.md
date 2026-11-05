# Sangitam agent instructions

## Project purpose

Sangitam is a Windows-first, Java 21 desktop music player and local music library built with Java Swing. The long-term goal is a responsive, polished application for importing and organizing local music, playing it reliably, building mood-oriented listening sessions, and discovering provider catalogs while respecting each provider's playback and API rules.

Read [the project handoff](docs/PROJECT_HANDOFF.md) before making substantial changes. It records the current checkout, known defects, the distinction between completed and incomplete features, and the staged roadmap. Treat that document as a dated snapshot; inspect the source before relying on it.

## Working agreements

- Work incrementally, starting with the user's reported local-library and playback defects. Keep the Swing interface responsive and keep disk, database, decoding, analysis, and network work off the Swing Event Dispatch Thread.
- Preserve the existing package boundaries (`app`, `ui`, `model`, `library`, `session`, `storage`, `playback`, `discovery`, and `history`) unless a concrete implementation need justifies a change.
- Inspect `git status` before editing. This checkout may contain intentional in-progress changes and untracked user files. Do not discard, overwrite, expose, or clean them up as part of unrelated work. Do not commit, reset, or switch branches unless asked.
- Do not put API keys, access tokens, passwords, or other credentials in source, documentation, logs, screenshots, or Git. Prefer Windows Credential Manager for user credentials. If a credential has been exposed, document the rotation action without copying the credential itself.
- Keep local audio analysis on-device. MusicBrainz metadata enrichment is opt-in. A provider's catalog search result is metadata/link information unless its official integration explicitly permits playback in Sangitam. Do not download or extract provider audio or combine provider content in a generic player contrary to provider terms.
- Treat features as **implemented** only when their end-to-end behavior is present and usable. Mark partial work, placeholders, and known regressions as such. Keep user-observed symptoms separate from source-confirmed causes and label unverified explanations as hypotheses.
- Prefer focused changes and established project patterns. Avoid broad rewrites that jeopardize the user's working build or data. Preserve SQLite migration compatibility and existing media paths.
- Make controls understandable and accessible: visible state, meaningful labels/tooltips, keyboard behavior where appropriate, and clear errors for unavailable files, unsupported formats, and provider failures.

## Build and run

Use JDK 21 or newer. On Windows, run these from the repository root:

```bat
build.bat
run.bat
```

`build.bat` performs a clean Maven compile and skips tests. `run.bat` launches already compiled classes and does not build; run the build script after source changes. The Maven wrapper is included, so a separate Maven installation is not required. VLC/libVLC is currently an external runtime dependency for the broadest local format support; it is not bundled with this development checkout. JavaFX Media is the fallback and supports fewer formats.

The repository's test command is:

```bat
mvnw.cmd test
```

Do not claim tests or runtime checks passed unless they were actually run for the current change. When the user requests verification, run focused checks and state their exact result. The UI should also be exercised manually for changes to Swing interaction, audio transport, file browsing, or Windows integration.

## Documentation practices

- Update `README.md` when user-facing setup, launch, or everyday behavior changes.
- Update `docs/ARCHITECTURE.md` when module boundaries, data flow, threading, storage, or provider integration changes.
- Update `docs/PROJECT_HANDOFF.md` when a significant feature becomes usable, a reported defect is fixed or re-diagnosed, a roadmap decision changes, or build/security guidance changes. Keep it a useful continuation record rather than a list of every code edit.
- Keep this file focused on durable project instructions. Put dated status, symptom reports, and implementation details in the handoff.
- Cross-check documentation against source and scripts. Do not copy outdated claims forward. Mention known limitations explicitly and use absolute repo-relative links where practical.

## Priorities

1. Fix and verify drive selection/import, play/pause state, and playhead seeking for local files.
2. Improve reliable local playback and library behavior before expanding scope.
3. Implement the remaining library, classical-metadata, session, and UI features in the handoff roadmap.
4. Add provider and analytics features only with compliant playback boundaries and secure credential handling.
5. Package a Windows release only after core behavior and native dependencies are stable.

