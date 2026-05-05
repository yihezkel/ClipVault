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
- **Google Drive sync** — pinned clips are mirrored to a hand-editable JSON file in your Drive (`/ClipVault/pinned-clips.json`); edits there propagate to every device on the next sync

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
├── sync/
│   ├── SyncFileFormat.kt            # JSON schema for the cloud file
│   ├── SyncMerge.kt                 # Pure 3-way merge by exact text identity
│   ├── SyncSnapshot.kt              # Last-synced pinned set (local file)
│   ├── SyncPrefs.kt                 # SharedPreferences for revision/file ID/account
│   ├── DriveClient.kt               # Google Drive v3 REST client (OkHttp)
│   ├── GoogleAuth.kt                # Google Authorization API wrapper
│   ├── SyncManager.kt               # Pull → 3-way merge → push orchestration
│   ├── SyncWorker.kt                # WorkManager job
│   └── SyncScheduler.kt             # Daily + on-demand scheduling
└── ui/
    ├── MainActivity.kt              # Jetpack Compose settings/management screen
    └── SettingsActivity.kt          # Sign-in, sync status, manual sync button
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

## Cloud sync setup (one-time, before first sign-in)

ClipVault stores your pinned clips in a Google Drive JSON file you can hand-edit. The app uses Google's Authorization API and the Drive REST API directly, requesting only the **`drive.file`** scope — meaning ClipVault can only see and modify files it created itself, never the rest of your Drive. Because this is a sideloaded app, you must register a small Google Cloud project so Google trusts your APK. No code changes or embedded secrets are needed — Google identifies the app at runtime by package name + signing-cert SHA-1.

1. Create a Google Cloud project at https://console.cloud.google.com.
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **APIs & Services → OAuth consent screen** → User type: **External**. App name: ClipVault. Add yourself as a test user. (Personal Google Workspace accounts can use Internal instead.)
4. **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Application type: **Android**
   - Package name: `com.clipvault.app`
   - SHA-1 certificate fingerprint: the SHA-1 of the keystore that signs your APK. For a debug build, run `keytool -list -v -keystore "%USERPROFILE%\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android` and copy the SHA-1. If you later sign release APKs with a different keystore, register a second OAuth client with that SHA-1 (or you'll get sign-in errors after switching).
5. Save. (No client ID needs to go into the app code.)
6. Build & install ClipVault, open it, tap the **gear icon → Sign in with Google**, pick your account, grant Drive access. The first sync runs immediately and creates `/ClipVault/pinned-clips.json` in your Drive root.

In the same gear-icon screen you can also **Sync now** on demand, view the last sync status, and **Sign out** (which clears local sync state but leaves the cloud file untouched).

### Editing the cloud file by hand

Open `pinned-clips.json` in Drive (web, desktop, or the mobile Drive app):

```json
{
  "_comment": "ClipVault pinned clips. Edit this file …",
  "schemaVersion": 1,
  "lastUpdated": "2026-05-05T03:14:00.000Z",
  "pins": [
    { "text": "your pinned text", "createdAt": "2026-05-01T08:00:00.000Z" },
    { "text": "another pin",      "note": "useful link" }
  ]
}
```

- Identity is the exact `text` value. To "rename" a pin, delete it and add a new one.
- `note` and `createdAt` are optional. Leave `_comment` and `schemaVersion` alone.
- Multi-line clips are encoded with `\n` escapes inside the JSON string.

### When does sync run?

- **Daily**, scheduled overnight by WorkManager (~3 AM local).
- **Whenever you pin or unpin** a clip on the phone (debounced ~10 seconds).
- **On demand** via Settings → "Sync now".

### Conflict handling

Identity is the exact text, so divergent edits are merged automatically: anything added on either side is kept; anything removed on either side is removed. There is no overwrite. For the truly paranoid, use **Sync now** before bulk-editing the cloud file to make sure local changes are pushed first.

### Troubleshooting sync

- **"Consent cancelled" / sign-in flashes and returns to Settings.** The Google Cloud OAuth client's package name + SHA-1 must exactly match the APK you installed. Re-check step 4. If you switched between debug and release builds, register a second OAuth client for the other SHA-1.
- **"This app isn't verified" / 403 access_denied.** The OAuth consent screen is in **Testing** mode and your account isn't on the test-users list. Add it under **OAuth consent screen → Test users**.
- **Drive API errors mentioning quotas or `accessNotConfigured`.** The Drive API isn't enabled for the Cloud project — re-do step 2.
- **`pinned-clips.json` doesn't appear in Drive.** Open Drive web, search for `ClipVault`, and check the **owner** column — the file is owned by the signed-in account, which may not be your primary one. Because the app uses the `drive.file` scope, it can't see files it didn't create, so renaming or moving the file in Drive is fine but deleting it forces a fresh first-sync (and a new file).
- **Logs.** Filter logcat by tag `ClipVaultSync` for sync errors, or `ClipVaultAuth` for sign-in errors.

## Toolchain pinning

The project is locked to **Gradle 8.13 + AGP 8.13.2 + Kotlin 2.1.0 + KSP 2.1.0-1.0.29**. Newer combinations (AGP 9, KSP 2.x) trigger a Room/KSP compatibility bug (`unexpected jvm signature V`) until Room is updated. If Android Studio offers to auto-upgrade these tools, decline.

## License

Personal use.
