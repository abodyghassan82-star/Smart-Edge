# Smart Edge Fork: "Dock to Edge Bubble"

Spec and brief for Claude Code.

- **Target device:** Nubia Neo 3 GT 5G, Android 15 (MyOS 15)
- **Base project:** Smart Edge: Sidebar & Gestures v1.3.6 (MIT license)
  - Repo: https://github.com/Imtiaz-Official/Smart-Edge
  - Package: `com.imi.smartedge.sidebar.panel`
- **Reference behavior:** Tecno Pova 5 "Smart Panel"

---

## 0. Start here (for the person, not Claude Code)

1. On GitHub, open the Smart Edge repo and tap **Fork**. Your fork will be at `github.com/<you>/Smart-Edge`.
2. Add `android-build.yml` (provided separately) to your fork at `.github/workflows/android-build.yml`.
3. Open the fork in Claude Code (desktop, terminal, or the Claude mobile app's Code feature). Paste the prompt from section 10.
4. Every push builds an APK. Open the repo's **Actions** tab, open the latest run, and download the `app-debug-apk` artifact.
5. Install the APK on the phone (allow "install unknown apps" for your browser or file manager). Start Shizuku (Wireless debugging) and test with section 8.

Note: the debug APK is signed with a debug key. That's fine for personal use, but it can't update over the original F-Droid/GitHub build. Uninstall the original first, or change the applicationId (see section 9).

---

## 1. Goal

Add one feature to Smart Edge: **minimize a floating window into a small docked bubble on the screen edge, keep the app running while docked, and restore it with a tap.**

Smart Edge already provides the edge handle, the app panel, and launching apps as freeform windows via Shizuku. Don't rebuild those.

## 2. Reference behavior (from screenshots of the Tecno Pova 5)

1. A thin handle sits on the screen edge. Swiping in from it opens a small white rounded panel with a scrollable 2-column grid of app icons (Telegram, YouTube, Calculator, Chrome, WhatsApp, Gmail, games).
2. Tapping an app opens it as a floating window over the home screen or any other app.
3. The floating window has a header bar with three controls:
   - **Expand** (top-left): makes the window fullscreen.
   - **Drag handle** (top-center, three dots): moves the window.
   - **Close X** (top-right): closes the app.
4. Dragging the window to the screen edge collapses it into a **bubble**: a small white rounded tab containing the app icon, docked flush against the edge, floating above other apps.
5. **The app keeps running while the bubble is showing.** For example, a YouTube video keeps playing. Tapping the bubble restores the window to its previous size and position.

## 3. Already working (do NOT rebuild)

- Edge handle and panel
- Launching apps into freeform windows (with Shizuku running)
- Settings, themes, permissions onboarding

**First task:** read the codebase and write a short summary of how the panel launches freeform windows (which classes, how Shizuku is used, how task IDs are obtained). Don't change behavior until that's done.

## 4. New feature requirements

### 4.1 Window header controls
- Draw a header overlay attached to each floating window launched from the panel, matching section 2.3: expand, drag handle, close, plus a new **minimize/dock** button.
- The header follows the window as it moves or resizes.
- Header appears only for windows launched from the panel.

### 4.2 Dock to bubble
- Trigger: tap the dock button, or drag the window so it overlaps the left or right screen edge zone.
- On dock:
  1. Save the window's task ID, package name, and current bounds.
  2. Shrink the task to the smallest size the system allows and keep it **fully on-screen**, near the edge.
  3. Show an overlay bubble (rounded white tab, app icon, docked flush to the edge) on top of the shrunken window.
- Bubble can be dragged vertically along the edge, and snaps to the left or right edge on release.
- Multiple bubbles stack without overlapping.
- Tap = restore to saved bounds and bring the task to the front.
- Long-press = menu with Close and Open fullscreen.

### 4.3 Keep-alive
- The app must not be paused or killed while docked.
- Run a foreground service with a notification (the Smart Edge service already exists; extend it).
- Add an in-app check that shows whether battery optimization is set to Unrestricted, with a shortcut to the settings screen.
- Show a clear status when Shizuku isn't running (it stops after every reboot) with a button that opens Shizuku.

### 4.4 Cleanup
- If the task is closed elsewhere, remove its bubble.
- If the service is stopped, remove all bubbles and restore or close windows sensibly.

### 4.5 Settings
- A toggle for "Dock to edge bubble" (default on).
- Bubble size, bubble opacity, and edge zone width.

## 5. Implementation hints (UNVERIFIED, must be tested on Android 15)

These are starting points. Verify each on the real device.

- **Resizing and moving tasks:** try shell commands through Shizuku, such as `cmd activity task resize <taskId> l t r b`, or the hidden `IActivityTaskManager.resizeTask` via `ShizukuBinderWrapper`. Some of the older `am stack`/`am task` commands were removed in recent Android versions.
- **Getting the task ID and bounds:** `dumpsys activity activities` or `cmd activity` / `ActivityManager.getRunningTasks` alternatives via Shizuku.
- **Bubble overlay:** `TYPE_APPLICATION_OVERLAY` window (the "Display over other apps" permission is already granted).
- **Why the window stays on-screen:** Android may pause the activity of a window that's fully off-screen or invisible. That's likely the main risk to background playback. If a tiny on-screen window still gets paused, fall back to the approaches in section 7.

## 6. Non-goals

- No root requirement.
- No forcing apps that block background playback to play in the background (for example, YouTube background play is a Premium feature). The goal is that the docked window counts as visible so playback continues.
- No changes to the existing panel look.

## 7. Known risks and fallbacks

| Risk | Fallback |
|---|---|
| Tiny freeform window gets paused | Keep it larger (e.g., 120x120 dp) behind the bubble |
| Shizuku call for resizing fails on this ROM | Use `wm` / `cmd` variants, or hidden API via ShizukuBinderWrapper |
| Some apps refuse resizing | Skip docking for that app and show a toast |
| Accessibility/overlay permissions revoked by the ROM | Detect and show a guided fix screen |

## 8. Acceptance tests (on the Nubia Neo 3 GT)

1. Open YouTube from the panel, play a video, dock it, wait 60 seconds, restore. The video timer advanced about 60 seconds. (Audio should also stay audible while docked.)
2. Repeat with WhatsApp (call or voice message), Telegram, Chrome (a video), and Calculator (state preserved).
3. Dock two apps at once. Both bubbles are visible and tappable.
4. Rotate the phone with a bubble docked. The bubble stays on the edge.
5. Close an app from Recents. Its bubble disappears.
6. Reboot. Shizuku-not-running status is shown clearly, and the panel still works after restarting Shizuku.
7. Existing features (panel, split-screen drag, notifications section) still work.

## 9. Build and packaging

- Add `.github/workflows/android-build.yml` (provided) so GitHub builds a debug APK on every push.
- To let the fork install alongside the original, change `applicationId` in `app/build.gradle(.kts)` (for example to `com.<yourname>.smartedgedock`), and change the app label.
- Keep the MIT license file and the original author's copyright notice.
- For a release-signed APK, create a keystore locally and add it as GitHub Secrets. Optional, and not needed for personal use.

## 10. Prompt to paste into Claude Code

```
This is a fork of Smart Edge (Kotlin, MIT license). Read SPEC.md (or smart-edge-dock-spec.md) in full.

Step 1: explore the repo and summarize how the panel launches freeform windows and how Shizuku is used. Don't change code yet.

Step 2: propose a plan for the "Dock to Edge Bubble" feature in small commits, flagging anything in section 5 you can't verify without a device.

Step 3: implement it. Don't break existing features. Add the settings from 4.5. Change the applicationId so it installs alongside the original. Keep the GitHub Actions workflow working.

After each step, tell me exactly what to test on my Nubia Neo 3 GT (Android 15) and what logs to send back if it fails.
```
