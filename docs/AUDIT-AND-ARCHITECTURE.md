# Audit of v0.3.0 and architecture of v0.4.0

## Audit method and honesty note
The audit was a read-through of every source file (no Android SDK was available, so nothing was compiled, profiled or run).
The performance findings below are therefore diagnoses from reading the code, not measurements.

## Findings in v0.3.0 (priority order)
1. **Slow, blank icons.** `applyLoaded` cleared the entire icon cache on every reload; one worker thread processed requests
   first-in-first-out, so visible icons waited behind off-screen ones; the home grid, dock, library and folder each rendered
   their own bitmap size (about four renders per app); nothing was preloaded; a view showed nothing until its icon arrived.
2. **Page and view churn.** `applyState` ran on every layout or data event and called `notifyDataSetChanged` with
   `POSITION_NONE` for every page, rebuilding all pages, every icon view, the dock and the library list each time.
3. **Drag and drop** only changed the model on drop; there was no live rearrangement, no lifted shadow, no in-folder drag.
4. **Single fixed transition** (zoom and fade); no speed or intensity control; no preview.
5. **Settings** were a flat dialog; no icon packs, gestures, performance or backup options.
6. **Entrance animation on return** set icons to alpha 0 while they might still be loading (visible pop-in).
7. Gesture actions were hard-coded; widgets were not supported.

## Preserved
All v0.3.0 behaviour: layout model and migration (schema 1 and 2 files still load), folders, hidden apps, recents, edit mode,
Control Center, App Library, default-home flow.

## Responsibilities (files)
- Discovery: `AppRepository` (LauncherApps, debounced change events with package names).
- Icon loading and caching: `IconCache`; icon packs: `IconPacks.kt`.
- Layout model and persistence: `HomeModel` (pure Kotlin, unit-tested), `LayoutStore`, `Backup`.
- Settings storage: `LauncherSettings`, `Gestures`; settings UI: `SettingsActivity`.
- Home UI: `LauncherActivity`, `HomePagerAdapter`, `HomeGridView`, `AppIconView`, `DockView`, `FolderView`, `LibraryView`,
  `WidgetsPageView`, `ControlCenterView`, `PageDots`.
- Drag and drop: `LauncherActivity` (events, drop logic) and `ReorderPreview` (live movement).
- Animation: `Transitions` (page styles, speed), `Springs` (spring helper).

## Data compatibility
`layout_v1.json` keeps its file name; schema 1 (v0.1) and 2 (v0.2+) are read, writes use schema 2. Old icon-size setting is
migrated to a percentage. New settings use new keys with defaults, so existing preferences are preserved.
