# Changelog

## 0.4.0
**Performance and icon loading**
- IconCache rewritten: two worker threads fed by a priority queue (visible page 0, neighbouring pages 1, rest 2, library 3).
- One canonical bitmap per app and configuration; dock, library, folders reuse it (views scale it down slightly) instead of
  rendering separate sizes. Folder previews are built from the cached bitmaps with step-wise downscaling.
- Duplicate requests for the same icon are merged; results are delivered to every waiting view.
- A placeholder is shown immediately and the real icon fades in; no blank slots.
- Only the entries of a changed package are invalidated (AppRepository now reports which packages changed). The whole cache
  is dropped only for icon pack / shape / override changes or severe memory pressure (`onTrimMemory`).
- Adapter keeps pages whose content signature did not change; dock and library only rebind when their data changed.
- Preloading around the current page, configurable (Settings > Performance); cache size configurable.
- Removed the per-icon entrance animation when returning from an app (it hid icons that were still loading).

**Drag and drop**
- System drag (reliable across views) plus a live preview: siblings spring to their future cells during the drag, in the grid,
  the dock and inside folders; the saved layout still changes once, on a successful drop.
- Lifted drag shadow (enlarged, soft shadow), haptics when the slot changes, when a folder target is ready and on drop.
- Hovering the centre of an icon grows it gradually; after about 0.45 s it is a folder target; folder creation settles with a spring.
- Dragging inside an open folder reorders; dragging out closes the folder and continues onto the home screen or dock.
- Edge hover flips home pages; pages are created by overflow and removed when empty.
- Release in place opens the item's menu; dropping outside a valid target changes nothing.

**Icon packs** (IconPacks.kt, Settings > Icons and Themes): discovery through the standard intent actions, appfilter.xml parsing
(item, iconback, iconmask, iconupon, scale), component then package matching, fallback composition, per-app overrides,
restore original, shape option, persistence, queries declared in the manifest (no broad package visibility).

**Page transitions** (Transitions.kt): Slide, 3D Cube, Parallax, Zoom, Fade, Rotation, Depth; intensity and speed; live preview
in settings; respects the animation switch, the animation-quality setting and Android's animation scale.

**Settings**: new SettingsActivity with sections, sliders, switches and choices; gestures (swipe down, swipe up, double tap,
long press, top-right swipe) bound to selectable actions; dock opacity, radius and icon size; light/dark appearance for dock,
folders and library; performance options; backup and restore of layout, folders, hidden apps, recents, icon choices and settings.

**Widgets**: AppWidgetHost with bind approval, configure activity support, removal and size reporting on an optional widgets page.

**Other**: rows and spacing settings, label size, springs via androidx.dynamicanimation, alphabetical fast-scroll in the library.

## 0.3.0
Control Center, rounded-square icon fix (the mask never cleared the corners), animations.
## 0.2.0
Drag and drop, edit mode, folders, hidden apps, recents, swipe-down search.
## 0.1.0
Launcher shell, icon cache, dock, pages, library and search, persistence.
