# Windows launcher and 100,000-file test

Open **Sangitam.exe** in the generated `dist/windows/<build>/Sangitam` folder. You can pin it to the taskbar or make an Explorer shortcut. Keep the entire folder together, including `app` and `runtime`. On this development build, VLC is still an optional external installation for broader format support. The package includes the JavaFX fallback.

To build a fresh image from the repo, run this in PowerShell on Windows with JDK 21 or newer and `jpackage.exe` on PATH.

```powershell
$launcher = .\package-windows.ps1
```

The script compiles the app, stages runtime dependencies, writes a manifest, and creates a portable Windows app image. The generated runtime has a class data sharing archive on supported JDKs. `tools/WriteLauncherIcon.java` converts `AppIcons.applicationIcon()` to a multiresolution ICO. Swing uses the same source for the window icon. The app executable and optional system media helper use the Windows GUI subsystem. The packaging tool starts the JVM directly when you open the executable. [Oracle jpackage reference](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jpackage.html).

## Open a test library with actual files

```powershell
$shortcut = .\create-scale-test.ps1 -Launcher $launcher
```

The script creates a new ignored `scale-tests/<build>/` folder with a `Data` directory, 100,000 playable short WAV paths in nested `Music` directories, and **Open 100k Library.lnk**. The generator uses one short tone per folder and hard links where supported. The fallback copies the samples and can use roughly 0.8 GB for audio plus catalog and filesystem overhead. The fixture has synthetic titles, albums, artists, and cached measurements. It uses its own database and leaves your existing library alone. Build and clean keep the package and test folders.

1. Open **Open 100k Library.lnk**. Wait for the library to show entries and switch between General and Locations while it loads.
2. In General, select **Songs** and try the first few pages in each sort. Search for `Scale 099999`, then clear the search. Try a collection hover and open **Liked Songs** after marking a track with its heart or its context menu.
3. In Locations, expand a `Shelf`, then a `Folder`. Each folder has 500 files. Advance to later branch pages, collapse, and expand again. Try resizing and expanding the library while a search is active.
4. In the main Search view, find a title and an album. Play a sample, then give a button, list, or slider focus and press Space. In a search field, Space should type a space. The short samples mainly exercise navigation and file availability. Use your normal library to judge pause and seek timing on longer recordings.
5. To exercise import from scratch, launch the same executable with `--data-dir` pointing to a new sibling directory, then import the fixture's `Music` folder recursively. The preindexed benchmark startup and a fresh 100,000-file import measure different operations.

### Measure the 500 ms target

The test shortcut writes `startup.log` beside itself. Each line includes the PID and milliseconds from Java `main` and from Windows process start. Read `first_paint` for the first Swing paint, `workspace` for shell controls and screens being wired, `catalog_100000` for the saved catalog reaching the UI, and `library_page` for the first General page result. A process time of `-1` means the OS did not supply a process start timestamp. For meaningful comparison, close the app and test the first launch after boot plus several repeated launches. First paint measures Swing's paint completion and excludes any additional desktop compositor delay. Disk, antivirus, hardware, and Windows cache state affect these times.

The normal executable accepts optional `--data-dir <directory>` and `--startup-report <file>` arguments. Create the report's parent directory first. The fixture shortcut also disables automatic artist lookups for synthetic names. **A sub-500 ms first paint is a target.** A local app image and headless benchmark do not measure a cold interactive Windows launch.

## Follow the code

- [SangitamApp](../src/sangitam/desktop/app/SangitamApp.java) builds the frame on Swing's event thread. The shell schedules the rest of the workspace after its first paint. The decoder starts on playback demand, online views open lazily, and startup skips file probes for analysis that matches indexed stamps. Use Rescan to check changes on disk.
- [LibraryService](../src/sangitam/desktop/library/LibraryService.java) builds the immutable track index and analysis count on workers. A recorded play updates counts and recency without rereading every metadata payload. [MainFrame](../src/sangitam/desktop/ui/MainFrame.java) serializes collection edits on one worker and coalesces passive refreshes.
- [GeneralLibraryPages](../src/sangitam/desktop/ui/GeneralLibraryPages.java) caches ordering per snapshot and uses a bounded pair of page buffers. The right rail still keeps its source snapshot in memory. [LocationIndex](../src/sangitam/desktop/ui/LocationIndex.java) builds a path index off Swing and loads branch windows. Whole-library import, grouping, and analysis still scale with catalog or file size.
- [SpaceTransport](../src/sangitam/desktop/ui/SpaceTransport.java) handles the default Space binding before a focused widget. Editable text receives Space, and holding the key toggles only once. Custom Play/Pause bindings still work through the shortcut settings.
- [LibraryCollections](../src/sangitam/desktop/model/LibraryCollections.java) projects song heart marks as **Liked Songs** without copying audio or writing a second membership table. Existing song pins use the same marks, so they appear immediately. The built-in playlist is managed by Like and Unlike and can still be played, queued, pinned, or copied to a regular playlist.
