# iOS Home Launcher v0.4.0

An independent, iOS-inspired Android launcher. Native Kotlin, plain Android views, two small AndroidX libraries
(`viewpager`, `dynamicanimation`). Not affiliated with Apple; no Apple assets are used.

**Status: v0.1.0 compiled on GitHub Actions. v0.2.0 to v0.4.0 have NOT been compiled or run by the author**
(the authoring environment has no Android SDK and no network). Expect to fix a few compile errors on the first
v0.4.0 build; see Troubleshooting. Nothing in this README is claimed as tested unless the test report says so.

## Build

**GitHub Actions (no PC needed):** upload the contents of this folder to a repo (keep `.github/workflows/build.yml`),
open Actions > Build APK, download `ios-home-launcher-debug-apk` from the run's Artifacts, install `app-debug.apk`.

**Android Studio:** open the folder, sync with Gradle 8.7 and JDK 17, Build > Build APK(s).
**Command line:** `gradle assembleDebug` (APK at `app/build/outputs/apk/debug/app-debug.apk`), `gradle testDebugUnitTest`.
A release build needs your own signing key (`assembleRelease` is unsigned; sign it with `apksigner`).

Versions: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7, compileSdk/targetSdk 34, minSdk 26.
To become the Home app: Settings (inside the launcher) > Default home app, or Android Settings > Apps > Default apps > Home app.

## What is in v0.4.0 (see CHANGELOG.md for details)
- **Icon loading**: priority queue (visible page, neighbouring pages, background), one canonical bitmap per app,
  placeholders instead of blank slots, merged duplicate requests, per-package invalidation, memory-pressure trimming,
  pages reused instead of rebuilt.
- **Live drag and drop**: icons spring out of the way while you drag, lifted drag shadow, edge page flipping, gradual
  folder-target growth, in-folder reordering by drag, dock takes part, rollback on cancel, settle animation on drop.
- **Icon packs**: ADW / Nova / GO / Apex style packs (appfilter.xml), pack-wide application, fallback composition for
  unthemed apps, per-app custom icons from the pack, restore original, shape option (iOS rounded or original).
- **Seven page transitions** (Slide, 3D Cube, Parallax, Zoom, Fade, Rotation, Depth) with live preview, speed and intensity.
- **Settings screen** with sections: Home Screen, Icons and Themes, Animations, Gestures, Dock, Appearance, Performance,
  Widgets, Backup and Restore, About; gestures are customisable.
- **Widgets**: real Android widgets (AppWidgetHost) on an optional widgets page before the home pages.
- Everything from v0.3.0: folders, edit mode, hidden apps, recents, search, Control Center, rounded icons.

## Known limitations / not done
- Pages exist only when icons overflow a page (a new empty page cannot be created by dragging); empty pages vanish.
- Widgets live on their own page, not in the icon grid, and have no resize handles (they follow the provider's size).
- Not done: work-profile apps, Android 13 themed (monochrome) icons, App Library categories, notification badges,
  Notification Center, lock screen, Dynamic Island, Assistive Touch, analytics (kept offline by design).
- Icon packs that ship a non-standard format (no appfilter.xml) cannot be read.
- Page-transition speed uses a reflection hook on ViewPager's internal scroller; if a future library version renames it,
  speed adjustment silently stops working (the rest is unaffected).
- Compatibility beyond what the code targets (API 26 to 34) has not been tested on any device.

## Test report (what actually ran)
| Check | Result |
|---|---|
| XML resources well-formed; bracket balance of all Kotlin and Gradle files | Done, passed |
| Layout-model logic (folders, drag moves, hide, recents, reconcile, in-folder moves) re-implemented in Python and run against the same scenarios as the unit tests | Done, passed |
| Live-preview index maths (home grid and dock) checked exhaustively against the real move operation | Done, 812 + dock cases, 0 mismatches |
| Kotlin compile, lint, debug and release builds | **Not run** |
| Unit tests (`HomeModelTest`, 30 cases) | Written, **not run** |
| Everything that needs a device: drag feel, icon-pack rendering, transitions, widgets, performance, battery, memory, FPS | **Not run** |

## Troubleshooting
- Build error: paste the failing step's log to get a fix.
- Gradle sync fails: confirm JDK 17 and Gradle 8.7.
- Stuck with another launcher: Android Settings > Apps > Default apps > Home app.
- An icon pack is not listed: it must declare one of the standard actions (Nova, ADW, GO, Apex) and be installed.
