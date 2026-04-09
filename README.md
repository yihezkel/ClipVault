# ClipVault

An Android clipboard manager that auto-captures everything you copy, lets you pin unlimited clips, search your full history, and paste directly into any text field with one tap.

Built for Samsung Galaxy devices (S26 Ultra, etc.) where the built-in clipboard has strict limits on pinned items.

## Features

- **Unlimited pinned clips** — pin as many as you want; they're never auto-deleted
- **Auto-capture** — every text you copy is saved automatically (up to 1,000 unpinned clips; oldest are pruned)
- **Full-text search** — search across all clips instantly via FTS4
- **One-tap paste** — tap a clip in the overlay panel and it pastes directly into the text field that had focus
- **Overlay panel** — triggered via the accessibility button in the navigation bar; works from any app
- **Sort toggle** — switch between "pinned first" and "recent first" views
- **Multi-select** — long-press to select multiple clips, then pin, unpin, or delete in bulk
- **Lock screen aware** — overlay auto-dismisses when the screen turns off
- **Manual entry** — add clips manually via the "+" button in both the overlay and the main app

## How It Works

ClipVault uses an Android `AccessibilityService` to:

1. **Detect clipboard changes** via three independent mechanisms:
   - Standard `OnPrimaryClipChangedListener` (works on stock Android)
   - Timestamp polling of `ClipDescription` every 30 seconds (works on Samsung One UI)
   - Accessibility event detection of system "Copied" toasts
2. **Show an overlay panel** (`TYPE_ACCESSIBILITY_OVERLAY`) when the accessibility button is tapped
3. **Paste into focused fields** using `AccessibilityNodeInfo.ACTION_PASTE`

A transparent `ClipboardCaptureActivity` is used as a fallback for clipboard reading on Android 10+ where background clipboard access is restricted.

## Architecture

```
com.clipvault.app/
├── ClipVaultApp.kt                  # Application class (database + repository singletons)
├── data/
│   ├── ClipEntity.kt                # Room entity
│   ├── ClipFts.kt                   # FTS4 virtual table for full-text search
│   ├── ClipDao.kt                   # Room DAO with search, sort, prune queries
│   ├── ClipDatabase.kt              # Room database
│   └── ClipRepository.kt            # Repository (1,000 unpinned clip limit, dedup)
├── service/
│   ├── ClipboardAccessibilityService.kt  # Core service: clipboard monitoring, paste, overlay
│   └── ClipboardCaptureActivity.kt       # Invisible activity for foreground clipboard reads
├── overlay/
│   ├── OverlayPanelManager.kt       # Overlay lifecycle, search, sort toggle, multi-select
│   └── ClipAdapter.kt               # RecyclerView adapter with selection support
└── ui/
    └── MainActivity.kt              # Jetpack Compose settings/management screen
```

## Requirements

- Android 8.0+ (API 26)
- Galaxy S26 Ultra or any Android device
- No root required
- No Google Play Store publishing required (sideload)

## Building

1. Open the project in Android Studio
2. Let Gradle sync complete
3. **Build → Build APK(s)**
4. Output: `app/build/outputs/apk/debug/app-debug.apk`

## Installing

1. Enable **Developer Options** on your phone (Settings → About phone → tap "Build number" 7 times)
2. Enable **USB Debugging** in Developer Options
3. Connect via USB and allow the debugging prompt
4. Press **Run** in Android Studio (or `adb install app-debug.apk`)
5. The app stays installed after you unplug

## Setup

1. Open ClipVault
2. Tap the "Accessibility service not enabled" banner
3. Find **ClipVault** in the list and toggle it ON
4. The accessibility button (small floating icon) appears in your navigation bar

## Usage

- **Copy text anywhere** — it's auto-saved within ~30 seconds
- **Tap the accessibility button** — overlay slides in from the right
- **Tap a clip** — it pastes directly into the text field you were typing in
- **Search** — type in the search bar at the top of the overlay
- **Toggle sort** — tap "📌 first" / "⏱ recent" to switch sort order
- **Pin/unpin** — tap the pin icon on any clip
- **Delete** — tap ✕ (with confirmation prompt)
- **Multi-select** — long-press a clip, then select more; use the action bar to pin/unpin/delete
- **Add manually** — tap "＋" in the overlay header or the FAB in the main app

## Tech Stack

- **Kotlin** with coroutines
- **Jetpack Compose** (main activity)
- **Room** with FTS4 (database + full-text search)
- **Android Views** (overlay panel — Compose can't render in `TYPE_ACCESSIBILITY_OVERLAY`)
- **AccessibilityService** (clipboard monitoring, paste, overlay window)

## Performance

The app is designed to have minimal impact on normal phone usage:

- Accessibility events are filtered to only notifications/announcements (not every UI event)
- Clipboard polling runs once every 30 seconds (one lightweight binder call)
- No wake locks, no foreground notification, no background location

## License

Personal use.
