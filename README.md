# Sangitam desktop UI

A responsive, dependency-free Java Swing prototype for managing and browsing music.

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
