# iOS Home Launcher (v0.3.0)

An independent, iOS-inspired Android home-screen replacement. Native Kotlin, plain Android views,
one AndroidX dependency (`viewpager`). Not affiliated with Apple; no Apple assets are used.

**Status: v0.1.0 compiled on GitHub Actions. v0.2.0 and v0.3.0 have NOT been compiled or run yet** (the authoring environment has no
Android SDK and no network). Expect to fix a few compile errors on the first build; see Troubleshooting.

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

## What v0.3.0 implements
- Real HOME + LAUNCHER intent filters; Android's role dialog (API 29+) or Home-app settings to become default.
  "Default home app: Yes" only shows when Android reports it.
- Installed apps via `LauncherApps` (no QUERY_ALL_PACKAGES), real icons rendered into iOS-style rounded squares
  (adaptive and legacy icons), bounded LRU cache, background loading, fallback icon, cache cleared on package changes.
- Edge-to-edge layout with status bar, navigation bar, display-cutout and keyboard insets; transparent window over
  the system wallpaper.
- Multi-page home grid (page count derived from screen size, so rotation never loses icons), page dots, dock with
  translucent static background (no blur), search pill.
- **Drag and drop** (Android's drag framework): rearrange icons, move between pages (hold at a screen edge to flip
  pages), move into and out of the dock, reorder inside the dock. Nothing is saved until a drop succeeds; dropping
  outside a valid target leaves the layout unchanged. Releasing an icon without moving it opens its menu.
- **Edit mode**: long-press empty space, long-press the dock background, or pinch in. Icons jiggle and show remove
  badges; Settings and Done buttons appear at the top. Exit with Done, Back, Home or by opening the App Library.
- **Folders**: hold one icon over the centre of another until it highlights, then release. Open and close with a
  scale animation, rename in place, tap outside to close, launch apps from inside, move earlier/later, move out to the
  home screen, remove a folder (apps return to the home screen). Empty folders disappear; uninstalled apps are
  removed from folders.
- **Rounded-square icons** (v0.3.0 fix): every icon, dock icon and folder preview is clipped to an anti-aliased iOS-style
  rounded square (corner radius about 22% of the icon width). v0.2.0 and earlier left the corners square because the
  alpha mask never cleared them; icons are now drawn through a BitmapShader instead.
- **Control Center** (swipe down from the top-right corner, or Settings > Open Control Center; swipe up, tap outside,
  Back or Home closes it). Real controls: flashlight (state follows the device), volume slider, media previous /
  play-pause / next, and, after the user grants "Modify system settings", brightness slider and rotation lock.
  Airplane mode, dark mode and battery saver show the real device state. Wi-Fi, Bluetooth, mobile data, dark mode and
  battery saver open Android's own screens/panels, because Android does not let launchers switch them. Also shortcuts
  to Camera, Android Settings and the launcher's own settings. The panel says which controls are shortcuts.
- **Animations** (all off when the in-app switch is off or Android's animation scale is 0): page swipe scales and fades
  pages, icons pop in when you return to the home screen, the home screen zooms out as an app opens and back in on
  return, press-scale on icons, jiggle in edit mode, edit bar fade, folder open/close (panel scale plus dim fade),
  Control Center slide, dropped icons settle into place, drop-target icon grows before a folder is made.
- **Swipe down** on the home screen opens search with the keyboard (Spotlight-style, apps only).
- **App Library**: alphabetical list, Recent section (launch history made through this launcher, stored locally,
  capped at 8), live case-insensitive search, clear button, keyboard search action, empty and loading states.
- **Hide App** (long-press menu) removes an app from the home screen, library and search; Settings > Hidden apps
  lists them and unhides on tap. Hiding never disables or uninstalls anything.
- Long-press menus: add/remove from Home, add to Dock, move earlier/later, hide, App Info, Uninstall (Android's own prompt).
- Layout (home, dock, folders, hidden apps, recents) saved as versioned JSON with atomic writes; schema 1 files from
  v0.1.0 are migrated; corrupt or missing file falls back to a default layout.
- Settings: columns, icon size, dock icons, labels, animations, haptics, wallpaper picker shortcut, default-home
  shortcut, hidden apps, reset layout, about.
- No network permission, no analytics, no background services.

## Not implemented yet (planned rounds)
Native Android widgets (AppWidgetHost), notification centre (needs notification-listener consent), screen-recording and
screenshot shortcuts (Android gives launchers no API for them), music track info (needs notification access),
in-app wallpaper picking, icon packs and themes, app categories, gesture customisation, dragging apps out of an open
folder (use the menu), folders in the dock, creating empty extra pages, work-profile apps, backup/restore.
Not planned as specified: replacing the secure lock screen (Android does not allow it), Dynamic Island and Assistive
Touch (need "display over other apps" permission; possible later as opt-in features), and remote analytics (this
project is offline by design; an optional on-device usage summary is possible instead).

## Known limitations
- Only the current user profile's apps are shown (no work-profile apps).
- If an app is updated and Android briefly reports it missing, its icon may move to the end of the home screen.
- iOS system-level app-launch animations cannot be reproduced; a scale-up transition is used instead.
- Control Center volume is read when it opens; changes made with the hardware keys while it is open are not reflected until reopened.
- Control Center needs the "Modify system settings" permission for brightness and rotation lock; using brightness switches Android to manual brightness.
- Pages exist only as icons overflow a page, so an empty new page cannot be created by dragging.
- Compatibility beyond the targeted API range has not been tested on any device.

## Test report
| Check | Result |
|---|---|
| XML resources well-formed | Done, passed |
| Bracket balance of all Kotlin/Gradle files | Done, passed |
| Layout-model logic (folders, drag moves, hide, recents, reconcile) re-implemented in Python and run against the same scenarios as the unit tests | Done, 69 assertions passed |
| Kotlin compile, lint | **Not run** (v0.1.0 did compile on GitHub Actions; v0.2.0 and v0.3.0 not yet built) |
| Unit tests (`HomeModelTest`, 26 cases) | Written, **not run** |
| Drag-and-drop, folders, edit mode, gestures, Control Center, animations, icon rounding on a device | **Not run** |
| Install, performance, battery | **Not run** |

## Troubleshooting
- Build error: paste the full log back to the assistant to get a fix.
- Gradle sync fails: confirm JDK 17 and Gradle 8.7.
- Icons blank at first: they load in the background; if they never appear, report the logcat.
- Stuck with another launcher: Settings -> Apps -> Default apps -> Home app.
