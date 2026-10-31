# iOS Home Launcher (v0.1.0, phase 1)

An independent, iOS-inspired Android home-screen replacement. Native Kotlin, plain Android views,
one AndroidX dependency (`viewpager`). Not affiliated with Apple; no Apple assets are used.

**Status: source only. It has NOT been compiled or run yet** (the authoring environment had no Android SDK
and no network). Expect to fix a few compile errors on the first build; see Troubleshooting.

## Build the APK

### Option A - GitHub Actions (no PC needed)
1. Create a GitHub repo and upload the *contents* of this folder (keep `.github/workflows/build.yml`).
2. Open the **Actions** tab -> **Build APK** -> wait for the run (about 5-10 min).
3. Download `ios-home-launcher-debug-apk` from the run's **Artifacts**, unzip, install `app-debug.apk`
   (allow "install unknown apps" for your browser/file manager).
4. Settings -> Apps -> Default apps -> Home app -> **iOS Home Launcher**
   (or long-press the home screen -> Settings -> Default home app).

### Option B - Android Studio
Open this folder, let Gradle sync (use Gradle 8.7 and JDK 17), then Build -> Build APK(s).
The project ships no Gradle wrapper; Android Studio can create one, or run `gradle wrapper --gradle-version 8.7`.

### Command line
`gradle assembleDebug` -> `app/build/outputs/apk/debug/app-debug.apk`; `gradle testDebugUnitTest` for unit tests.
A release build needs your own signing key (`assembleRelease` produces an unsigned APK; sign it with `apksigner`).

Versions: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.7, compileSdk/targetSdk 34, minSdk 26.

## What phase 1 implements
- Real HOME + LAUNCHER intent filters; Android's role dialog (API 29+) or Home-app settings to become default.
  "Default home app: Yes" only shows when Android reports it.
- Installed apps via `LauncherApps` (no QUERY_ALL_PACKAGES), real icons rendered into iOS-style rounded squares
  (adaptive and legacy icons), bounded LRU cache, background loading, fallback icon, cache cleared on package changes.
- Edge-to-edge layout with status bar, navigation bar, display-cutout and keyboard insets; transparent window over
  the system wallpaper.
- Multi-page home grid (page count derived from screen size, so rotation never loses icons), page dots, dock with
  translucent static background (no blur), search pill.
- App Library page (swipe past the last page): alphabetical list, live case-insensitive search, clear button,
  keyboard search action, empty and loading states.
- Long-press menus: add/remove from Home, add to Dock, move earlier/later, App Info, Uninstall (Android's own prompt).
- Layout persisted as versioned JSON with atomic writes; corrupt or missing file falls back to a default layout.
  Newly installed apps are appended; removed apps are dropped; apps you removed from Home stay in the Library.
- Settings (long-press empty space): columns, icon size, dock icons, labels, animations, haptics, wallpaper picker
  shortcut, default-home shortcut, reset layout, about.
- Press-scale animation, page swipe, dock fade into the library. Animations can be turned off and follow Android's
  animator-duration scale.
- No network permission, no analytics, no background services.

## Not implemented yet (planned phases)
Drag-and-drop, edit-mode jiggle, folders, widgets (AppWidgetHost), in-app wallpaper picking, app categories,
recently used, hidden apps, backup/restore, icon packs, light/dark launcher theme, work-profile apps, notification badges.
Wallpaper is handled by Android's own picker. Back/Home behaviour: Home returns to page 1, Back unwinds search/pages.

## Known limitations
- Only the current user profile's apps are shown (no work-profile apps).
- If an app is updated and Android briefly reports it missing, its icon may move to the end of the home screen.
- iOS system-level app-launch animations cannot be reproduced; a scale-up transition is used instead.
- Compatibility beyond the targeted API range has not been tested on any device.

## Test report
| Check | Result |
|---|---|
| XML resources well-formed | Done, passed |
| Bracket balance of all Kotlin/Gradle files | Done, passed |
| Kotlin compile, lint | **Not run** |
| Unit tests (`HomeModelTest`, 9 cases) | Written, **not run** |
| Install, default-launcher selection, any on-device behaviour, performance | **Not run** |

## Troubleshooting
- Build error: paste the full log back to the assistant to get a fix.
- Gradle sync fails: confirm JDK 17 and Gradle 8.7.
- Icons blank at first: they load in the background; if they never appear, report the logcat.
- Stuck with another launcher: Settings -> Apps -> Default apps -> Home app.
