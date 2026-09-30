# Sangitam desktop UI

A responsive, dependency-free Java Swing prototype for managing and browsing music.

## What makes it different

Sangitam is designed as a **listening workspace**, rather than another library browser. Its Session Composer lets a listener describe the next block of time using intent, energy and duration, then creates a visual session recipe. The interaction is implemented with basic Swing models and listeners so it remains approachable for students and reviewers.

Other distinctive details include a searchable `Ctrl/Cmd+K` command palette, a smoothly expanding library, resolution-independent Java2D icons, responsive panel visibility and keyboard focus feedback. See `docs/ARCHITECTURE.md` for an explanation of how each idea maps to a Swing concept.

## Project structure

```text
src/
  sangitam/desktop/app/SangitamApp.java    application entry point
  sangitam/desktop/ui/                     modular Swing screens and components
bin/
  sangitam/desktop/...                     generated `.class` files
```

## Build and run

On macOS or Linux:

```sh
make run
```

On Windows:

```bat
build.bat
java -cp bin sangitam.desktop.app.SangitamApp
```

The current version intentionally implements GUI interactions only. Commands provide a notification until media playback and persistence services are added.
