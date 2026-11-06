# Learning Home shelves and selective metadata restoration

These two Stage 2 features illustrate the same design rule: prepare data in a worker, then publish a small immutable result to Swing. They are wired into Listen Now and the song/album context menus. Automated checks are headless; visual interaction checks remain pending at the user's request.

## 1. Restore means release an override

Suppose the file says “Sonata” but you have saved “My Sonata” in Sangitam. A normal rescan preserves your correction. **Restore file details… → Title** does two things together:

1. Read the file again and save its current title (or the normal filename fallback).
2. Clear the title's override flag so later rescans follow file metadata again.

Simply changing the displayed text would leave the override active and block future updates. Simply clearing the override would leave an old label visible until a rescan. The transaction handles both.

Read [DetailReset](../src/sangitam/desktop/model/DetailReset.java) and [DetailResetPanel](../src/sangitam/desktop/ui/DetailResetPanel.java) first. The record contains sets of selected basic fields and credits plus a custom-tags flag. The editor starts with no selection, and offers Select all/Clear selection. There are no decoder calls or file writes in this panel. Classical movement is a work description; energy/movement ratings are separate local measurements and are unaffected.

## 2. Follow the worker and transaction

```mermaid
flowchart LR
    A[Song or album context menu] --> B[Explicit field selection]
    B --> C[LibraryService worker]
    C --> D[Force metadata reread]
    D --> E[Successful indexed files]
    E --> F[One SQLite transaction]
    F --> G[Catalog publication on EDT]
```

In [LibraryService.restoreDetails](../src/sangitam/desktop/library/LibraryService.java), selected paths are normalized and deduplicated. The service loads indexed identities, sets `forceMetadataRead` even when file size/time are unchanged, and uses the existing scanner. Failed reads do not enter the restore transaction. This is why disconnected drives or corrupt files keep their corrections. No neighboring tracks are imported and no new source or exclusion is registered.

[LibraryStore.restoreDetails](../src/sangitam/desktop/storage/LibraryStore.java) updates existing rows only. SQL `CASE` expressions change selected labels and their flags; unselected labels retain both value and protection. [MetadataStore.restore](../src/sangitam/desktop/storage/MetadataStore.java) does the equivalent for the JSON credit fields and custom tags. A selected credit absent from the new file/hints is removed, rather than keeping an explicit user clear. Restored credits carry the scanner's embedded/title/folder origin again. The search document is rebuilt inside the same transaction.

All successful reads commit together. If stored JSON or SQL fails, rollback also undoes earlier rows' updates. Mood scores, play counts, playlist paths, and history are not edited. The operation only reads audio files.

A subtle detail: this is a **partial** refresh. The file may have changed unselected automatic values too. Therefore it does not advance the full-scan size/time stamp or extraction version. A subsequent rescan/startup upgrade can still refresh everything else. Technical header values are safe to refresh because they have no user override.

## 3. Give Home a bounded input

[LibraryDashboardStore](../src/sangitam/desktop/storage/LibraryDashboardStore.java) queries actual recorded local listening:

```sql
WHERE last_played_at > 0 AND unavailable = 0
ORDER BY last_played_at DESC, path
LIMIT ?
```

Imports alone are excluded. Path provides deterministic ordering when timestamps tie. An additive index supports the ordering; the existing database and media identities remain compatible. This uses the app's qualified local play recording, separate from optional provider history in Rediscover.

[HomeLibraryData](../src/sangitam/desktop/ui/HomeLibraryData.java) is built on the collection worker. It caps recent tracks at 12, selects at most eight pinned playlist identities, sorts them alphabetically, resolves currently indexed members, and carries only the cover candidates' file stamps and unavailable state. Smart playlists use their already-resolved membership; removed file references are omitted. Membership itself remains available so menu actions can play or queue the full playlist.

[HomeLibraryPanel](../src/sangitam/desktop/ui/HomeLibraryPanel.java) displays two horizontal Swing lists with fixed-size cells, embedded art/icon fallbacks, and clear empty states. Enter/double-click routes a recent song to playback and a playlist to its collection page. Right-click, Shift+F10, and the context-menu key reuse library actions. All SQL and file reads remain outside the EDT. Shelf updates preserve selection by identity and leave the active queue intact.

`MainFrame.changeCollections` loads the shelves alongside organization updates. A revision guard prevents an older completed result from replacing a newer one. If the immutable catalog changed while the worker ran, Home waits for a fresh snapshot instead of displaying obsolete membership. This is bounded presentation, not a claim that the entire app has stopped loading the catalog: General, Locations, and session planning still need that larger snapshot.

## 4. Cache the version, not just the path

An image cache keyed only by path can keep an old cover forever, including a cached “no artwork” result. [LibraryArtwork](../src/sangitam/desktop/ui/LibraryArtwork.java) now uses `(path, indexed file size, indexed modification time)` as its key. A rescan that detects a file change requests its cover again. A slow completion for the old version remains under the old key and cannot replace the current image.

Each instance has one background reader, 24 pending requests, and an LRU limit of 160 positive/negative results. It considers the first three members for a collection cover. Decoding rejects artwork above 8 MB or 12,000 pixels on either axis and uses subsampling before scaling. `close()` stops queued work, clears retained images, and suppresses late UI callbacks. A missing cover falls back to an icon; no network image is fetched.

Rescan is still necessary to observe external changes. Files whose size and modification time both remain identical cannot be distinguished by this version key. External cover-file references and folder-image discovery remain future work.

## 5. Read and run the checks

```powershell
.\build.bat
.\mvnw.cmd test
.\mvnw.cmd '-Dtest=DetailResetTest,LibraryServiceTest,LibraryDashboardStoreTest,DetailResetPanelTest,HomeLibraryPanelTest,LibraryArtworkTest' test
```

- `DetailResetTest`: selected flags are released, future rescans follow the file, unrelated edits/ratings/history survive, missing fields clear, and failed batches roll back.
- `LibraryServiceTest`: a generated local WAV is reread even when unchanged; missing/corrupt/unindexed paths retain their corrections. No speaker or decoder is opened.
- `LibraryDashboardStoreTest`: imports are not listening, unavailable files are excluded, ties are stable, results are limited, and corrected labels are current.
- `DetailResetPanelTest`: selection is explicit and reversible before submitting.
- `HomeLibraryPanelTest`: caps, pinned smart/regular membership, removed paths, small cover-state maps, Enter callbacks, and empty updates.
- `LibraryArtworkTest`: reads stay off the EDT, changed versions retry a cached missing cover, old results cannot replace current art, and closing suppresses callbacks. The test injects icons rather than decoding user music.

For a learning exercise, add a third Home shelf. Define exactly what it means, write the bounded storage query first, then add its immutable data and UI callbacks. Avoid deriving a ranking from every loaded track inside a renderer.
