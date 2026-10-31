# Curbox agent guide

Curbox is an Android screen time manager. One accessibility service blocks apps, short videos, keywords, and selected UI elements.

This file applies to the whole repository. Preserve more specific instructions if a nested `AGENTS.md` is added later.

## Start here

- Modules: `:app` and `:apitester`. The latter is a sample client for the Curbox API.
- Language and UI: Kotlin, classic Views, Fragments, and ViewBinding.
- Do not add Jetpack Compose or the Navigation component.
- JVM target: 1.8.
- Package root: `app/src/main/java/neth/iecal/curbox`.
- Prefer the smallest change that follows the nearest existing implementation.
- Readability is more important than cleverness. Follow the style of each file you touch.
- Ask before making an architectural change. Explain the user impact before changing the Room schema because migrations are destructive.
- Preserve unrelated work in the repository. Do not rewrite nearby code unless the task requires it.
- Add comments only when they explain behavior the code itself cannot express.

## Working sequence

1. Read the affected class and the closest similar feature before editing.
2. Trace all relevant process, flavor, persistence, and lifecycle boundaries.
3. Make the narrowest complete change. Reuse current patterns and dependencies.
4. Check every entry point, refresh path, cleanup path, and optional feature gate.
5. Run the most focused useful verification, then broader flavor builds when the change can affect them.
6. Report what changed, which checks ran, and any checks that could not run.
7. Only write for english translations until explicitly asked to write for others
## Nonnegotiable invariants

### Accessibility service safety

`services/AppBlockerService.kt` must never crash because an individual feature fails.

- Keep feature calls inside the service's existing error containment boundaries.
- Log nonfatal failures through `CrashLogger.logNonFatalError` and swallow them.
- In coroutine workers, rethrow `CancellationException`; do not report it as a crash.
- Wire every new feature through its complete lifecycle: setup, receivers, event handling, and cleanup.

Never traverse accessibility node trees directly in `onAccessibilityEvent`.

- Cheap work such as app blocking, grayscale, focus mode, and lightweight tracking may run inline.
- Node walking belongs in the background worker fed by `Channel.CONFLATED`.
- Copy queued events with `AccessibilityEvent.obtain(event)`.
- Recycle every copied event in all paths, including failed sends, dropped events, exceptions, and shutdown.

### Multiple processes

The app runs in three processes:

| Process | Main component |
| --- | --- |
| Main | UI and `Curbox` application initialization guarded by `isMainProcess()` |
| `:app_blocker_service` | `AppBlockerService` |
| `:crash_handler` | `CrashLogActivity` |

This has concrete consequences:

- Access Room only through `AppDatabase.getInstance()`. It enables multi instance invalidation.
- Access settings only through `DataStoreManager` in `utils/DataStore.kt`.
- Never create a second DataStore for the same file. `DataStoreManager` owns the `MultiProcessDataStoreFactory` singleton.
- Do not assume initialization guarded by `Curbox.isMainProcess()` also ran in the service process.
- Sync initializes only in the main process.
- UI to service configuration updates use broadcasts unless that feature already collects the settings flow. Follow the feature's existing pattern.

### Settings compatibility and delay

`data/models/Settings.kt` is serialized as JSON with Gson.

- Every field must have a default value so older stored JSON still loads.
- Do not rename a field casually. A rename silently discards its stored value.
- Add a setting through `Settings`, an `updateX()` method in `DataStoreManager`, the UI, and the feature's refresh path.

Restriction settings are gated by the settings change delay. A change that weakens a restriction is stored as a `PendingSettingsChange`; a stricter change applies immediately and removes the pending value for that field. `RestrictionComparator` is conservative: a change it cannot prove stricter counts as weaker.

For every new gated field, update all of these:

1. `GatedSettingsField`
2. The `withFieldValue` branch
3. `utils/RestrictionComparator.kt`
4. The label in `ui/fragments/main/reducers/advanced/SettingsChangeDelayFragment.kt`

Due pending changes are applied by the service heartbeat, app startup, and the delay screen. Preserve all three paths.

### Room data

Room uses `fallbackToDestructiveMigration()`.

- Any entity change requires a database version bump in `data/db/AppDatabase.kt`.
- A version bump wipes local user data on upgrade.
- Tell the user about that tradeoff and get direction before implementing a schema change.
- Store large or growing records in Room. Store configuration in DataStore.

### Build variants

Gate optional behavior at every entry point, including UI, service setup and cleanup, manifests, and API responses.

| Flavor | Sync | UI hider | Anti uninstall | Internet |
| --- | --- | --- | --- | --- |
| `full` | Yes | Yes | Yes | Yes |
| `playstore` | Yes | No | No | Yes |
| `fdroid` | No | Yes | Yes | No |

Relevant flags in `app/build.gradle.kts`:

- `SUPPORTS_UI_HIDER`
- `SUPPORTS_ANTI_UNINSTALL`
- `SUPPORTS_WRITE_SECURE_SETTINGS`
- `FDROID_VARIANT`
- `SYNC_USE_FCM`

The Play Store manifest removes `AdminReceiver` and `NodePickerService`. Do not expose those features through another route.

Real sync code lives in `app/src/sync/java`, which is compiled only by `full` and `playstore`. Interfaces and `SyncGateway` live in main. The F-Droid stub in `app/src/fdroid/java` returns `NoopSyncProvider`. Any sync change must leave `fdroid` compilable without sync only classes, Firebase, Supabase, or the INTERNET permission.

Sync is currently free in both sync flavors. `SyncEntitlement` is a free stub, and no flavor compiles Play Billing. Preserve that behavior unless the task explicitly restores billing. `SUPPORTS_WRITE_SECURE_SETTINGS` is false in `playstore`; keep grayscale and related UI consistent with that capability. `SYNC_USE_FCM` controls the FCM push migration in sync flavors.

## Architecture map

### Accessibility host

`services/AppBlockerService.kt` hosts all blockers, trackers, and anti-stimulants. It extends `services/BaseBlockingService.kt`, which owns the foreground notification, service protection heartbeat, global actions, and `isDelayOver()`.

A typical feature is a plain class with this lifecycle:

1. Instantiate it as a service field.
2. Call its setup method in `onServiceConnected()`.
3. Register its receivers after setup.
4. Call its cheap event check inline or its node walking check in the background worker.
5. Remove receivers and release resources in `onDestroy()`.

Feature broadcasts use companion constants such as `AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER`. Settings screens write through `DataStoreManager` before sending the refresh broadcast.

### Feature locations

- `blockers/AppBlocker.kt`: schedules and usage limits. A package can belong to several groups and blocks when any group requires it.
- `blockers/ReelBlocker.kt`: Instagram reels, YouTube shorts, and Facebook reels. Runs in the background worker.
- `blockers/KeywordBlocker.kt`: reacts to website observations written by `WebsiteUsageTracker`; it does not scan visible screen text. Its unsupported browser check runs in the background worker.
- `blockers/FocusModeBlocker.kt`: temporary app and keyword blocking.
- `blockers/BrowserBlocker.kt` and `blockers/AntiUninstallBlocker.kt`: browser and device admin protection.
- `blockers/uihider/`: overlay based UI hiding and its scripting language. Node picking uses `services/NodePickerService`.
- `trackers/`: app usage, website usage, and short video counts. `WebsiteUsageTracker` has a 15 second heartbeat for browsers that omit URL change events.
- `anti_stimulants/`: `AutoDnd`, `GrayScaleFilter`, and `MindfulMessage`. Combine DND requests through `AppBlockerService.syncDndState()`.
- `hardcoded/`: all per app view IDs and configuration. Do not inline these values in feature code.
- `api/`: the exported AIDL service, user approval flow, and auth store. Keep responses consistent with flavor flags. See `CURBOX_API.md` and the `:apitester` client.

User facing blocking screens go through `ui/activity/WarningActivity` with the appropriate `Constants.WARNING_SCREEN_MODE_*` value.

### Service protection

`ServiceWatchdogJob`, `utils/ServiceProtectionManager`, `receivers/BootReceiver`, and the `BaseBlockingService` heartbeat keep the accessibility service alive and detect stops. Anti uninstall UI lives under `ui/fragments/main/reducers/advanced/`.

### Sync and cleanup

- End to end encryption uses `CryptoBox` and is covered by unit tests.
- `SupabaseRest` uses OkHttp directly. There is no Supabase Android SDK.
- Firebase is initialized manually from `FcmConfig`; there is no Google Services Gradle plugin or `google-services.json`.
- Remote usage is merged through `remoteAppUsage()` and `remoteWebsiteUsage()`; synced websites use `SYNCED_WEB_PACKAGE`.
- `utils/UsageStatsCleaner.kt` purges old local data.
- Backend cleanup SQL lives in `supabase/migrations/` and is applied separately, not by the Android build.

## UI and product copy

- Use ViewBinding and existing Fragment transactions.
- Use Material defaults and dynamic colors. Keep the visual style calm, minimal, and easy to scan.
- Prefer smooth transitions when views appear, disappear, or resize.
- Coolvetica is for strong onboarding typography. Inter is the normal app font.
- Put all user facing text in `app/src/main/res/values/strings.xml`. Follow the existing localization pattern for translations.
- Write for a nontechnical reader at about a sixth grade level.
- Keep sentences short and concrete. Use a real world example when an idea is difficult.
- Do not use hyphens, en dashes, or em dashes in user facing copy. This restriction applies to displayed text, not code, resource names, or developer documentation.

## Common change recipes

### Add a blocker or tracker

1. Add a plain class in `blockers/` or `trackers/` based on the closest feature.
2. Declare refresh actions as companion constants.
3. Wire the full lifecycle in `AppBlockerService`.
4. Keep node traversal in the background worker.
5. Add defaulted configuration to `Settings` and an updater to `DataStoreManager`.
6. Add delay gating when the setting controls restriction strength.
7. Wire UI and refresh behavior.
8. Apply every relevant flavor flag.

### Add a browser or app mod

Edit `hardcoded/BrowserUrlBarIds.kt` or `hardcoded/ReelAppConfig.kt`. Copy the closest entry and change only the package specific values. See `CONTRIBUTING.md`.

### Change the database

Update the entity and DAO, register them in `AppDatabase`, and bump the database version only after the destructive migration tradeoff is accepted.

## Build and verification

Use the check that matches the change. Documentation only changes do not need a Gradle build.

```bash
./gradlew testFullDebugUnitTest
./gradlew assembleFullDebug
./gradlew assemblePlaystoreDebug
./gradlew assembleFdroidDebug
./gradlew installAndGrantAccessibilityFullDebug
```

- `testFullDebugUnitTest` includes `CryptoBoxTest` and UI hider `ScriptLanguageTest`.
- Build all three flavors after changing shared source, source sets, manifests, BuildConfig gates, or optional feature wiring.
- Use the install task only when an emulator or device is available. It installs, grants accessibility through adb, and launches Debug Curbox.
- Lint does not abort builds, so inspect relevant warnings rather than treating a successful build as proof that lint is clean.
- Debug builds use the `.debug` application ID suffix and the name `Debug Curbox`.
- If a relevant check cannot run, say why in the handoff.

## Tuning detection on a real device

Detection rules (reel detectors, UI hider rules, browser URL bar IDs) depend on third party layouts that change without notice. Contributors, including people who do not code, may ask you to fix them on their phone. A build passing proves nothing here: every rule change must be reproduced, fixed, and verified on a connected device.

### Loop

1. Confirm the device with `adb devices`. If none is connected, stop and say so. Do not change detection rules blind.
2. Install the flavor already on the device. Check with `adb shell dumpsys package neth.iecal.curbox.debug | grep versionName`, then run the matching `installAndGrantAccessibility<Flavor>Debug` task.
3. Reproduce the problem by driving the app with `adb shell input tap|swipe|keyevent` and `adb exec-out screencap -p`. Read screenshots to confirm the state.
4. Observe what Curbox sees by adding temporary logging inside the service, then read it with `adb logcat -s <TAG>`.
5. Change the rule, reinstall, and repeat the same scenarios until they pass. Then test the edges: swiping back, leaving and returning, other entry points into the same screen, and ads.
6. Remove every temporary log, build all three flavors, and report the scenarios, results, app version, and device.

### Device pitfalls

- Some devices set `log.tag=I` and silently drop `Log.d`. Use `Log.w` for temporary diagnostics. Check with `adb shell getprop log.tag`.
- `uiautomator dump` and `uiautomator events` use UiAutomation, which can suppress other accessibility services while connected. Never run them while measuring Curbox. `uiautomator dump` also fails while video plays. To see the tree, log it from the background worker instead.
- Keep temporary diagnostics in the background worker or in a feature's worker path. Never walk the node tree in `onAccessibilityEvent`, even for debugging.
- Installing restarts the service process and clears in-memory state such as reel deduplication history. Account for this when you compare runs.
- Do not open messages, emails, or other private content to test. Use public feeds, Explore, and search.

### Reading app trees

- Hidden copies of a screen often stay in the tree, such as Instagram's Reels tab under a reel opened from Explore. `find()` returns the first match, so use `findAll()` with `isOnScreen()`.
- Node scoped searches can miss children in some apps. If `node.find(id=...)` returns null for a visible child, match window wide `findAll()` results by bounds.
- Each lookup is a separate IPC call, so a screen can move between two reads. Re-check the anchor after reading, or read only when the screen is settled.
- Scroll events from pagers report the exact page in `fromIndex`. They are throttled to about every 100 ms, so they lag behind the content.

### Feature specifics

- **Reel counter:** detectors live in `hardcoded/ReelAppConfig.kt`; counting logic is in `trackers/ReelsCountTracker.kt`. Debug builds log every counting decision under the `ReelsCountTracker` tag. Read Curbox's stored count with `adb exec-out run-as neth.iecal.curbox.debug cat databases/curbox_db` (also copy `curbox_db-wal`). For Instagram, run `python3 tools/reel-counter-test/reel_counter_test.py --reels 100` before and after a change. It needs macOS and checks the counter against OCR of real screenshots. Treat a mismatch as a Curbox bug only after the trace and screenshot confirm it; the OCR ground truth can be wrong too.
- **UI hider rules:** starter rules live in `hardcoded/UiHiderSamples.kt` and use the script language in `blockers/uihider/script`. Verify that the right area is hidden, that nothing else is, and that the rule stops when the user leaves that screen. Run `./gradlew testFullDebugUnitTest` after touching the script language.
- **Browsers:** URL bar IDs live in `hardcoded/BrowserUrlBarIds.kt`. Verify by visiting a site and checking that website usage records it and that a keyword block triggers.

## Reference docs

- `CONTRIBUTING.md`: contributor workflows
- `CURBOX_API.md`: API contract and integration guide
- `Readme.md`: product overview
- `tools/reel-counter-test/`: on-device reel counter accuracy test
