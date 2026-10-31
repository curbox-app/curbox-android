# Curbox API integration guide

This document describes the public API implemented in this repository, including custom warning
screen requirements and the security hardening in the current source. Use it as a reference when
building an Android client or giving implementation instructions to a coding agent.

**Current API version: `3`.** The transport is a local Android Binder service with an AIDL
interface. There is no HTTP server, web API, API key, OAuth flow, or remote endpoint. A browser,
server, or desktop application needs an Android companion to use this API.

The working example is the [`:apitester`](apitester/src/main) Android app. It supports every public
command, query, and list, and includes a sample unlock requirement provider.

## Contents

1. [Capabilities and limits](#capabilities-and-limits)
2. [Project setup](#project-setup)
3. [Discovering and connecting to Curbox](#discovering-and-connecting-to-curbox)
4. [User approval and authorization](#user-approval-and-authorization)
5. [Calling conventions and errors](#calling-conventions-and-errors)
6. [Command reference](#command-reference)
7. [Query reference](#query-reference)
8. [List reference](#list-reference)
9. [Custom unlock requirements](#custom-unlock-requirements)
10. [Flavors and device capabilities](#flavors-and-device-capabilities)
11. [Security model](#security-model)
12. [Testing and troubleshooting](#testing-and-troubleshooting)
13. [Implementation map](#implementation-map)

## Capabilities and limits

An approved app can:

- Discover existing focus, app blocker, keyword, grayscale, and automatic DND groups.
- Start a manual focus session using an existing group, or stop sessions when early exit is allowed.
- Enable or disable existing app blocker, keyword, and grayscale groups.
- Toggle the keyword blocker, reel blocker, UI hider, and reel counter globally.
- Request a change to Do Not Disturb.
- Read today's recorded screen time and reel count, and the current focus state.
- List UI hider script metadata.
- Register its own named unlock requirements for the user to select in warning settings.

The API does **not** expose:

- Creating, editing, or deleting blocker or focus groups.
- Choosing a group's apps, keywords, schedules, usage limits, or warning configuration remotely.
- Automatically attaching a provider requirement to a group.
- Editing UI hider scripts or toggling individual scripts.
- Editing automatic DND groups, Pomodoro cycles, anti uninstall, or settings change delay.
- Per app usage reports, website history, previous days, focus history, or a full settings export.
- Direct unrestricted cooldown grants, API access management, event subscriptions, or webhooks.

Custom requirements extend the existing warning flow. They let a provider show its own check or
practice activity; they do not replace Curbox's warning screen with arbitrary provider layouts.

## Project setup

### AIDL contract

Enable AIDL in the client module's `build.gradle.kts`:

```kotlin
android {
    buildFeatures {
        aidl = true
    }
}
```

Copy the [published AIDL file](app/src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl) to this exact
path in your app:

```text
src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl
```

Keep its package, method order, and signatures unchanged:

```aidl
package neth.iecal.curbox.api;

interface ICurboxApi {
    int apiVersion();
    boolean isGranted();
    String execute(String command, in Bundle args);
    String query(String state);
    String list(String kind);
}
```

The generated client type is `neth.iecal.curbox.api.ICurboxApi`. You do not need Curbox's internal
Kotlin classes, Room database, DataStore implementation, Supabase, Firebase, or Shizuku. Shizuku is
not a prerequisite for the API.

Examples below use AndroidX Activity Result APIs, Kotlin coroutines, and `org.json`. Add the
corresponding AndroidX and coroutine dependencies if your app does not already have them. Those
are example client choices; the Binder protocol does not require coroutines or a particular JSON
library. The bundled tester instead uses a single thread executor.

### Package visibility

Add these queries directly under `<manifest>`, outside `<application>`, so Android 11 and newer
can discover Curbox:

```xml
<queries>
    <intent>
        <action android:name="neth.iecal.curbox.api.BIND" />
    </intent>
    <intent>
        <action android:name="neth.iecal.curbox.api.REQUEST_PERMISSION" />
    </intent>
</queries>
```

The client does not need `INTERNET` or `QUERY_ALL_PACKAGES` just to use this API. Permissions for
provider checks, such as location, belong to the provider and are separate from Curbox approval.

## Discovering and connecting to Curbox

### Public actions

| Purpose | Action |
| --- | --- |
| Find and bind the API service | `neth.iecal.curbox.api.BIND` |
| Open the user approval activity for result | `neth.iecal.curbox.api.REQUEST_PERMISSION` |
| Receive an unlock check in a registered provider activity | `neth.iecal.curbox.api.CHECK_UNLOCK_CONDITION` |

Release builds normally use `neth.iecal.curbox`; debug builds use `neth.iecal.curbox.debug`.
Discover services by action rather than assuming one package is installed.

**Handle multiple installations explicitly.** Release and Debug Curbox can both be installed. If
you blindly use the first resolved service, the tester or your client may connect to an older
installation and receive `UNKNOWN_COMMAND` for a new feature.

```kotlin
val candidates = packageManager.queryIntentServices(
    Intent("neth.iecal.curbox.api.BIND"),
    0
).distinctBy { it.serviceInfo.packageName }

// No candidates: explain that Curbox is unavailable.
// One candidate: select it.
// Several candidates: show app labels and package names and let the user choose.
val labels = candidates.map {
    "${it.loadLabel(packageManager)} (${it.serviceInfo.packageName})"
}
```

Use the chosen installation for **both binding and approval**. Group IDs, grants, and provider
registrations belong to that installation. Do not request approval from release Curbox and then
send commands to Debug Curbox.

Discovery identifies apps advertising an action; an app label or intent filter alone is not proof
of a trusted publisher. If your product requires a verified Curbox distribution, implement a
signing certificate policy for that distribution. This repository does not publish a universal
trusted certificate value for all release and debug builds.

### Binding and lifecycle

These members illustrate a client owned by an Activity. `selectedCurboxPackage` must come from the
installation selection above. Run connection UI and lifecycle changes on the main thread.

```kotlin
private var api: ICurboxApi? = null
private var bindingRequested = false
private var selectedCurboxPackage: String? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        api = ICurboxApi.Stub.asInterface(service)
        // Read apiVersion() and isGranted() on a worker thread before using features.
    }

    override fun onServiceDisconnected(name: ComponentName) {
        api = null
        // The existing binding may reconnect automatically.
    }

    override fun onBindingDied(name: ComponentName) {
        disconnect()
        // Reconnect deliberately after resolving the current installation.
    }

    override fun onNullBinding(name: ComponentName) {
        disconnect()
    }
}

private fun connect(packageName: String) {
    if (bindingRequested) return
    selectedCurboxPackage = packageName
    try {
        bindingRequested = bindService(
            Intent("neth.iecal.curbox.api.BIND").setPackage(packageName),
            connection,
            Context.BIND_AUTO_CREATE
        )
        // A true return means the bind request was accepted, not that api is ready yet.
    } catch (e: SecurityException) {
        api = null
        bindingRequested = false
        // Surface an unavailable connection to your UI.
    }
}

private fun disconnect() {
    if (bindingRequested) {
        unbindService(connection)
        bindingRequested = false
    }
    api = null
}

override fun onDestroy() {
    disconnect()
    super.onDestroy()
}
```

Track whether `bindService()` succeeded separately from whether `onServiceConnected()` has run.
You still need to unbind if your Activity is destroyed while the connection is pending. For an
application scoped connection, give ownership to a component with a matching longer lifecycle.

Catch `RemoteException` and `DeadObjectException` around calls. A connected Binder can die between
checking a variable and making the call. Do not cache a Binder across process death.

## User approval and authorization

The API is off by default. Binding alone does not grant access. Launch the permission activity
**for result from an Activity** so Android supplies a verified `callingPackage`:

```kotlin
private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.StartActivityForResult()
) { result ->
    // RESULT_OK indicates approval at that moment. Recheck api.isGranted on a worker thread.
    // RESULT_CANCELED means denied, cancelled, or an invalid request.
}

private fun requestCurboxAccess() {
    val packageName = selectedCurboxPackage ?: return
    permissionLauncher.launch(
        Intent("neth.iecal.curbox.api.REQUEST_PERMISSION").setPackage(packageName)
    )
}
```

Handle `ActivityNotFoundException` if Curbox is removed or changed between selection and launch.
Do not use an ordinary `startActivity()` launch, a referrer extra, or a supplied package extra to
claim your caller identity. The permission activity rejects launches without a verified caller.

Approval also enables the global API switch. Users can revoke one app or turn the whole API off
under **Reducers → Curbox API → Connected apps**. `isGranted()` returns false when access is absent,
revoked, globally disabled, or no longer matches the approved signing certificates.

Grants are tied to the package and current APK signing certificates. Old package-only grants need
fresh approval after the security update. A signing key change also requires fresh approval.
Updating an app with the same signing certificates does not, by itself, change its approved identity.
Uninstalling an app ends its grant. Installing it again requires fresh approval.

Authorization is broad: an approved provider gets the same public commands and reads as other
approved clients. There is no provider-only or read-only permission scope. Explain this to users
before opening the approval prompt.

For shared UIDs, an approved package authorizes calls from that UID. The optional `provider_package`
argument selects which approved package in that UID owns a registration. It cannot name an arbitrary
package outside the caller UID or a package that has not been approved.

## Calling conventions and errors

### Methods and versions

| Method | Result | Approval required |
| --- | --- | --- |
| `apiVersion()` | Integer contract version | No |
| `isGranted()` | Boolean describing the calling UID's current access | No |
| `execute(command, args)` | Status string | Yes |
| `query(state)` | JSON object string or `null` | Yes |
| `list(kind)` | JSON array string, JSON object string for `STATUS`, or `null` | Yes |

The base command and query interface predates version 2. `list()` arrived in version 2. Provider
registration, removal, and `UNLOCK_CONDITIONS` require version 3. Check the version **before calling
methods or features an older installation may not implement**. A version number identifies the
contract; it does not distinguish every security patch within version 3.

All operation names and argument keys are **case sensitive**. Send exact uppercase command, state,
and list names. JSON is returned inside a Java/Kotlin string, not as a `Bundle`.

Run Binder calls off the UI thread. They are synchronous, may read the database or write settings,
and do not have a caller-supplied timeout. Wrapping a blocking Binder call in a coroutine timeout
does not guarantee that a command already submitted to Curbox will be cancelled.

```kotlin
val remote = api ?: return
val version = withContext(Dispatchers.IO) { remote.apiVersion() }
val granted = withContext(Dispatchers.IO) { remote.isGranted }
if (!granted) return // Offer the approval flow in your UI.

val result = withContext(Dispatchers.IO) {
    remote.execute("SET_REEL_BLOCKER", Bundle().apply {
        putBoolean("enable", true)
    })
}
```

The snippets assume an enclosing coroutine and appropriate exception handling. Keep UI work on the
main thread. Avoid aggressive background polling; there are no callbacks or change subscriptions.

### Execute results

| Exact result | Meaning and handling |
| --- | --- |
| `OK` | The operation completed without a reported error. It does not prove active enforcement or that a queued change has already applied. |
| `DENIED` | API access is missing or changed while a command waited, or `provider_package` is not an approved package in the caller UID. Offer a deliberate reapproval flow. |
| `UNKNOWN_COMMAND` | An authorized caller supplied an unrecognized command. Check spelling, version, and the selected Curbox installation. |
| `FAILED` | A recognized operation could not complete. Possible causes include unknown group IDs, invalid registration arguments, a protected focus session, unsupported UI hider, or an internal exception. No structured reason is returned. |

Unauthorized calls return `DENIED` before command lookup. A failed Binder transport is an Android
exception, not one of these four strings. Commands are not a transaction across all database and
settings writes; an exception is not a guarantee that every earlier side effect was rolled back.

`query()` and `list()` return `null` for denial, an unknown name, or an internal failure. They do not
encode these reasons as JSON errors. A legitimate empty listing is `"[]"`, which differs from `null`.
Check `isGranted()` and `apiVersion()` to narrow down an unexpected null result.

### Bundle types and defaults

| Key | Bundle type | Default or role |
| --- | --- | --- |
| `target` | `String` | Existing group ID; empty if omitted, causing targeted commands to fail |
| `enable` | `Boolean` | `true` if omitted |
| `minutes` | `Int` | `25` if omitted; focus duration is clamped to at least 1 minute |
| `id` | `String` | Provider requirement ID, separate from a Curbox group ID |
| `label` | `String` | Provider requirement display name |
| `activity` | `String` | Exported activity in the provider's own package |
| `provider_package` | `String` | Optional approved caller package selector, processed by `execute()` |

Use `putBoolean()` and `putInt()`, not strings such as `"true"` or `"25"`. Pass an empty `Bundle()`
for commands with no arguments. Only include arguments relevant to the operation.

### Delayed settings and retries

Restriction weakening can be held by settings change delay or by tamper protection. Stricter
changes generally apply immediately and remove the pending value for that settings field. These
rules also apply to API changes, because commands use the normal settings update paths.

`OK` can therefore mean a request was stored for later. The API does not expose pending changes,
the reason for deferral, or their application time. `STATUS` reports the currently stored effective
toggles, not a queued replacement value. It also does not prove the accessibility service is running.

Do not blindly retry mutating calls after a lost response. `START_FOCUS` can create another session
record and change the active session again. Query the relevant state or ask the user first. Setting
a toggle to its existing value is generally repeatable; identical provider registration and removal
of a missing provider ID are explicitly repeatable.

## Command reference

### Focus

| Command | Arguments | Behavior |
| --- | --- | --- |
| `START_FOCUS` | `target`: focus group ID; `minutes`: integer, default 25 | Starts manual focus using an existing group. Values below 1 minute become 1. Records a focus session and refreshes the blocker. |
| `STOP_FOCUS` | None | Clears active manual focus and marks running focus statistics as ended. |

Both commands return `FAILED` if a live manual session belongs to a group with `exitable = false`.
Replacing that session with another `START_FOCUS`, including a shorter session for the same group,
is also refused. A live session whose group cannot be found fails closed. An expired session does
not prevent a new session. Focus state is protected again inside the settings update to avoid a
concurrent change bypassing the early exit rule.

`START_FOCUS` does not create groups, accept a package list, or configure Pomodoro phases.

```kotlin
val raw = withContext(Dispatchers.IO) { remote.list("FOCUS_GROUPS") }
val groups = raw?.let(::JSONArray) ?: return
if (groups.length() == 0) return // Ask the user to create a group in Curbox.

// In a real UI, let the user choose a group rather than always choosing index 0.
val groupId = groups.getJSONObject(0).getString("id")
val status = withContext(Dispatchers.IO) {
    remote.execute("START_FOCUS", Bundle().apply {
        putString("target", groupId)
        putInt("minutes", 25)
    })
}
```

### Blockers and trackers

| Command | Arguments | Scope |
| --- | --- | --- |
| `SET_APP_BLOCKER_GROUP` | `target`, `enable` | One existing app blocker group |
| `SET_KEYWORD_BLOCKER` | `enable` | Global keyword blocker toggle |
| `SET_KEYWORD_GROUP` | `target`, `enable` | One existing keyword group |
| `SET_REEL_BLOCKER` | `enable` | Global reel blocker toggle |
| `SET_UI_HIDER` | `enable` | Global UI hider toggle; fails on Play Store builds |
| `SET_GRAYSCALE_GROUP` | `target`, `enable` | One existing grayscale group |
| `SET_REEL_COUNTER` | `enable` | Global reel counting toggle |

Group commands validate that the ID exists. Discover IDs with the matching list, and treat them as
opaque strings. A focus ID is not interchangeable with an app blocker or grayscale ID.

Enabling a group preserves its current selected apps, schedule, limits, and warning settings. It
does not mean its restrictions must trigger immediately. A keyword group still needs the global
keyword blocker enabled. Several app groups may cover the same app; disabling one does not disable
the others. A global toggle does not expose individual per app reel configuration through the API.

Grayscale settings can be updated even on a flavor without grayscale capability. In particular,
`SET_GRAYSCALE_GROUP` lacks a Play Store capability rejection, so it may return `OK` without a visual
effect. The API does not provide a general capability discovery endpoint.

### Do Not Disturb

`SET_DND` accepts `enable` and acts directly through Curbox's DND helper, rather than creating or
editing an automatic DND group.

- Curbox must already have Android notification policy access. The command does not request it.
- Enabling requests priority interruptions when the current filter is unrestricted.
- Disabling only turns DND off if Curbox recorded that it had turned it on.
- Missing permission or an unchanged filter can produce `OK` without any change.
- `STATUS` does not include the actual DND state. Other active automatic behavior may change it again.

### Provider registration

`REGISTER_UNLOCK_CONDITION` and `REMOVE_UNLOCK_CONDITION` are described in
[Custom unlock requirements](#custom-unlock-requirements). These commands require version 3 and do
not require the accessibility service merely to store or remove a registration.

## Query reference

**Every value returned by `query()` is a JSON string**, including booleans and numbers.

| State | Fields and types | Meaning |
| --- | --- | --- |
| `FOCUS_ACTIVE` | `active`: string boolean; `focus_group`: string; `focus_remaining`: string integer | Manual focus state, group name, and whole remaining minutes |
| `SCREENTIME_TODAY` | `screentime`: string integer | Whole minutes recorded for today's date, excluding ignored app packages |
| `REELS_TODAY` | `reels`: string integer | Today's recorded reel count, or zero when no count is stored |

Example active and inactive focus responses:

```json
{"active":"true","focus_group":"Study","focus_remaining":"24"}
```

```json
{"active":"false","focus_group":"","focus_remaining":"0"}
```

Remaining minutes are truncated, not rounded up. A session can still be active with `"0"` whole
minutes left. `focus_group` is a display name, not the ID required by commands. A live session can
have an empty name if its group is no longer found; this query is not a service health check.

Usage examples:

```json
{"screentime":"73"}
```

```json
{"reels":"42"}
```

"Today" uses the device's local date. Screen time is the sum of the date's stored app usage rows
excluding ignored packages. The query does not select a remote device, provide a time series, or
add website usage as a separate duration. Counts and durations depend on the relevant tracking
being enabled and recorded; they are not guaranteed to include an unflushed current event.

```kotlin
val json = withContext(Dispatchers.IO) { remote.query("SCREENTIME_TODAY") }
val minutes = json?.let { JSONObject(it).getString("screentime").toLong() }
```

## List reference

`list()` returns typed JSON: counts are numbers, flags are booleans, and names/IDs are strings.
This differs from `query()`'s string-valued objects. All list kinds except `STATUS` return an array.
Do not rely on array order, assume an item exists, or use a group name as a unique identifier.

| Kind | Item fields |
| --- | --- |
| `FOCUS_GROUPS` | `id`: string, `name`: string, `apps`: integer count, `websites`: integer count, `blockMode`: string, `exitable`: boolean, `autoTurnOnDnd`: boolean |
| `APP_BLOCKER_GROUPS` | `id`: string, `name`: string, `isActive`: boolean, `apps`: integer count |
| `KEYWORD_GROUPS` | `id`: string, `name`: string, `isActive`: boolean, `keywords`: integer count |
| `GRAYSCALE_GROUPS` | `id`: string, `name`: string, `isActive`: boolean, `apps`: integer count |
| `AUTO_DND_GROUPS` | `id`: string, `name`: string, `autoTurnOnDnd`: boolean |
| `UI_HIDER_SCRIPTS` | `id`: string, `label`: string, `packageName`: string, `isEnabled`: boolean |
| `UNLOCK_CONDITIONS` | `packageName`: string, `id`: string, `label`: string, `activityName`: string, `signingCertificates`: array of strings |

Focus `blockMode` is `BLOCK_SELECTED` or `BLOCK_ALL_EXCEPT_SELECTED`. The `websites` count is the
number of configured focus keywords. `apps`, `websites`, and `keywords` are **counts**, not arrays
of package names, URLs, or keyword values. The API does not reveal those selected contents.

Examples:

```json
[{"id":"focus-id","name":"Study","apps":12,"websites":3,"blockMode":"BLOCK_SELECTED","exitable":true,"autoTurnOnDnd":false}]
```

```json
[{"id":"apps-id","name":"Social apps","isActive":true,"apps":4}]
```

```json
[{"id":"keywords-id","name":"Distractions","isActive":true,"keywords":8}]
```

```json
[{"id":"gray-id","name":"Evening","isActive":true,"apps":3}]
```

```json
[{"id":"dnd-id","name":"Quiet time","autoTurnOnDnd":true}]
```

```json
[{"id":"script-id","label":"Hide feed","packageName":"com.example.social","isEnabled":true}]
```

```json
[{"packageName":"com.example.practice","id":"daily_cards","label":"Finish 20 cards","activityName":"com.example.practice.CheckRequirementActivity","signingCertificates":["example_sha256_hex_digest"]}]
```

The last certificate value is illustrative. Actual values are sorted lowercase SHA256 hex hashes
of the currently registered APK signing certificates. `UNLOCK_CONDITIONS` lists available providers
across approved apps, not only the caller's registrations. Missing, unauthorized, disabled, or
signer-mismatched providers are filtered out. `UI_HIDER_SCRIPTS` returns `[]` on Play Store builds.

### STATUS

`list("STATUS")` returns one object:

```json
{
  "focusActive": true,
  "focusGroup": "Study",
  "focusRemainingMinutes": 24,
  "keywordBlocker": true,
  "reelBlocker": false,
  "reelCounter": true,
  "uiHider": false
}
```

`focusGroup` is a name, not an ID. When focus is inactive, the name is empty and remaining minutes
are zero. `uiHider` is always false on unsupported flavors. No field here reports DND, accessibility
service health, pending settings changes, actual per app enforcement, or selected group contents.
Snapshots from separate calls are not an atomic combined snapshot.

## Custom unlock requirements

### Responsibility split

A requirement is a named, provider-owned check that a user selects in Curbox. For example:

- **At the library:** the provider verifies the device is inside a configured area when asked.
- **Finish 20 cards:** the provider verifies today's practice goal, or opens a practice flow first.

Curbox handles blocking, the warning screen, the delay before proceeding, access duration, proceed
limits, and any configured focus goal. The provider handles its own configuration, runtime
permissions, data access, UI, and decision. No location checker or Anki integration is bundled.

A provider registration has no arbitrary settings payload in Curbox. Store the location, radius,
card goal, or other provider-specific configuration inside your own app, keyed by requirement ID.
Treat an ID as a stable requirement with stable meaning. If its meaning changes materially, create
a new ID and let the user choose it again. Curbox cannot inspect changes in a provider's private data.

### Register

After binding and receiving approval, verify `apiVersion() >= 3` and send:

```kotlin
val status = withContext(Dispatchers.IO) {
    remote.execute("REGISTER_UNLOCK_CONDITION", Bundle().apply {
        putString("id", "daily_cards")
        putString("label", "Finish 20 cards")
        putString("activity", "com.example.practice.CheckRequirementActivity")
    })
}
```

| Field | Validation |
| --- | --- |
| `id` | Must match `[A-Za-z0-9_.]{1,100}`; unique within the owning package |
| `label` | Control and invisible formatting characters removed, then trimmed, nonempty, at most 100 characters |
| `activity` | Nonblank string of at most 300 characters; fully qualified class name, or a name beginning with `.` |
| Owner | Derived from the verified Binder UID and an approved package; never a supplied arbitrary owner |
| Activity | Must exist inside that owner package, be exported and enabled, have an enabled application, and require no manifest permission |
| Certificates | Must resolve to a nonempty current signer list |
| Capacity | At most 50 registered requirements per provider package in this Curbox installation |

For example, `.CheckRequirementActivity` expands relative to the owning provider package.
The activity may live in another namespace within the same APK, but cannot belong to another app.

Repeating an identical registration returns `OK`. An existing `(packageName, id)` cannot be edited
in place: a changed label, activity, or signer list makes registration fail. Register a new ID for
an intentional new definition. This avoids silently retargeting a user's selected requirement.

### User setup

1. Configure and register the requirement in the provider app.
2. In Curbox, create or edit an app blocker group and select the apps to restrict.
3. Open **Warning Screen Configuration**.
4. Choose **Require effort to unlock → Require another app**.
5. Select the registered requirement and configure the unlock time and other warning options.
6. Save the group and enable its intended restrictions.

The same shared warning option also exists for keyword and reel blockers. Provider registration
is available in all flavors. The provider cannot attach itself to a group through the public API.
Keep the provider app outside restrictions that would block its own check or practice activity.

For app groups with **On each open** behavior, approval follows Curbox's selected-app session
behavior. Otherwise, the configured unlock interval controls access. A provider cannot return a
longer duration in its result. Other blocker groups covering the target still apply independently.

Removing or switching an existing warning requirement is conservatively treated as a weakening
and follows the normal settings change delay and tamper protection rules. Deleting a provider
registration does not remove the requirement from a saved group's warning settings.

### Declare the check activity

In the provider manifest, inside `<application>`:

```xml
<activity
    android:name=".CheckRequirementActivity"
    android:exported="true" />
```

Use the default activity launch mode and normal result delivery. Do not add a manifest permission
that prevents Curbox from launching it. No intent filter is necessary: Curbox uses the registered
explicit component. Exporting this activity means arbitrary apps may try to launch it, so validate
inputs and avoid exposing privileged side effects through that entry point.

### Request and response contract

Curbox launches the activity **for result**:

| Request field | Value |
| --- | --- |
| Action | `neth.iecal.curbox.api.CHECK_UNLOCK_CONDITION` |
| Component | The exact registered provider package and activity class |
| String extra `condition_id` | The registered requirement ID |
| String extra `request_id` | Fresh opaque attempt ID; echo it unchanged on approval |

There is no group ID, target app package, location, unlock duration, or Anki data in this request.
Do not assume undocumented extras are present. Treat the request ID as a temporary challenge, not
as a login credential or a reusable access token.

To approve **only after verifying the real requirement**:

```kotlin
setResult(Activity.RESULT_OK, Intent().putExtra("request_id", requestId))
finish()
```

To deny, cancel, or report that verification is unavailable:

```kotlin
setResult(Activity.RESULT_CANCELED)
finish()
```

Do not send an internal cooldown broadcast, call `SET_APP_BLOCKER_GROUP` to disable restrictions,
or turn off blockers as a substitute for returning the result.

### Provider implementation pattern

The following is an abstract template, not a bundled location or Anki implementation. Supply a
concrete subclass, implement the two methods from your own app's data, and register that subclass.
If you need a visible practice or permission flow, keep the Activity open and run the check when
that flow completes, rather than immediately returning cancellation.

```kotlin
abstract class RequirementCheckActivity : AppCompatActivity() {
    // Read from your own saved connection selection, never from a caller-supplied extra.
    protected abstract fun selectedCurboxPackage(): String?

    // Check authoritative provider data. Return false if unavailable or incomplete.
    protected abstract suspend fun requirementIsMet(conditionId: String): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val conditionId = intent.getStringExtra("condition_id")
        val requestId = intent.getStringExtra("request_id")
        val expectedCaller = selectedCurboxPackage()
        val valid = intent.action == "neth.iecal.curbox.api.CHECK_UNLOCK_CONDITION" &&
            expectedCaller != null && callingPackage == expectedCaller &&
            conditionId != null && conditionId.matches(Regex("[A-Za-z0-9_.]{1,100}")) &&
            requestId != null && requestId.isNotEmpty() && requestId.length <= 100
        if (!valid) {
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                // Also reject IDs that your app has not configured or has retired.
                val approved = withContext(Dispatchers.IO) {
                    requirementIsMet(requireNotNull(conditionId))
                }
                if (approved) {
                    setResult(RESULT_OK, Intent().putExtra("request_id", requestId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Log using your app's normal nonfatal error reporting. Keep RESULT_CANCELED.
            } finally {
                finish()
            }
        }
    }
}
```

Use the same selected Curbox installation for provider registration, approval, and caller checking.
For stronger publisher verification, also validate that installation's signing identity using your
product's trusted certificate policy. Do not trust `referrer`, display names, or a supplied package
extra as authentication. Guard repeated UI submissions and avoid approving from a cached result
without checking that the requirement is still satisfied for the current attempt.

For a location provider, obtain your own runtime permission and evaluate a sufficiently fresh fix
against your configured area. Deny if location cannot be verified. For a practice provider, obtain
practice records using an integration supported by that app, define what counts as completed work,
and check the relevant goal and date. Merely opening Anki is not proof that cards were practiced.

### Validation, expiry, and lifecycle

An approval is accepted at most once, for the matching request ID, within **10 minutes** of the
attempt starting. Curbox uses elapsed time for this limit. It also rechecks:

- The provider is still approved and the global API is still enabled.
- The authorization revision has not changed since the attempt began. Revocation and reapproval,
  or switching the API off and back on, invalidate old attempts. Unrelated grant changes can also
  invalidate an attempt because the revision is global to API authorization.
- The exact provider definition is still registered, its activity is usable, and its signing
  certificates match the saved definition.
- The relevant blocker/group is still enabled and its warning configuration matches the screen
  that initiated the check.
- The warning screen is ready to proceed, including its existing focus goal and proceed limits.

If a result is invalid, expired, or cancelled, the target remains blocked. A provider should not
retry approval by broadcasting a token or reusing a previous attempt's result.

Outstanding attempts live only in Curbox's warning Activity memory. Recreating that Activity,
including rotation or process death, discards the attempt and requires a new check. Provider-side
recreation does not extend Curbox's time limit. A missing provider or missing registration fails
closed; it does not fall back to a simple Continue button.

Location is checked at unlock time. Leaving the location does not revoke an already granted access
interval. Continuous geofencing or immediate relocking on departure is not part of this protocol.

### Remove and restore a registration

```kotlin
val status = withContext(Dispatchers.IO) {
    remote.execute("REMOVE_UNLOCK_CONDITION", Bundle().apply {
        putString("id", "daily_cards")
    })
}
```

Only registrations owned by the selected verified caller package are removed. A missing ID is a
successful no-op. Existing groups keep their saved requirement and remain blocked until it is
available again or the user changes the group through Curbox.

Restoring the exact same definition with the same signing certificates can make an existing
selection available again. A changed definition requires the user to select the new definition.
There is no command to edit other providers' registrations.

Registrations are stored in Curbox's settings. Incoming settings sync preserves this installation's
local registration list. A synced warning configuration still requires its exact provider definition,
matching signatures, and local API approval on the receiving installation. Sync does not grant API
access or install the provider app.

## Flavors and device capabilities

| Capability | `full` | `playstore` | `fdroid` |
| --- | --- | --- | --- |
| Local AIDL API and custom requirements | Yes | Yes | Yes |
| UI hider | Yes | No | Yes |
| Grayscale capability through secure settings | Yes, with required access | No | Yes, with required access |
| Sync | Yes | Yes | No |
| Curbox internet permission | Yes | Yes | No |

Blocking requires Curbox's accessibility service to be enabled and running. Storing a toggle or
registering a requirement does not enable accessibility. Grayscale and DND also need their separate
device permissions/capabilities. A provider may have its own internet or location access even when
Curbox is the F-Droid flavor; those permissions belong to the provider's own app.

There is no `CAPABILITIES` method. Use documented flavor behavior, handle failures, and avoid
interpreting `OK` or a stored toggle as proof that a device effect occurred.

## Security model

- **Verified caller:** Binder UID determines eligible packages. A client cannot impersonate another
  package by placing its name in a command Bundle.
- **Pinned grants:** approval is tied to the package and signing certificates. Legacy grants and
  changed signers need fresh approval. Group data is preserved.
- **Protected approval UI:** only a verified Activity caller can request approval. The dialog shows
  package identity, filters obscured approval touches, and hides non-system overlays on Android 12
  and newer.
- **Internal broadcasts:** cooldown, warning visibility, and focus unsuspend receivers are private
  to the app. Curbox's UI and service can communicate across their own processes without exposing
  those operations to unrelated apps. Public integrations must use AIDL.
- **Command ordering:** execute operations are serialized in the API service. Authorization is
  checked again after waiting. This does not make separate API calls or database/settings writes
  into a general transaction.
- **Restriction rules:** API toggle changes use normal settings gates, and protected manual focus
  sessions cannot be ended or replaced through the API.
- **Limited approval lifetime:** provider responses are tied to one attempt, current access,
  registration, signing identity, and warning configuration.
- **Trusted providers:** Curbox cannot independently prove location, practice progress, or private
  provider data. An approved app still has the full API surface. Install and approve providers you
  trust; this is not a sandbox for hostile plugins.

Never ask users to disable all blockers to complete a provider check, expose unconditional approval
buttons in a production provider, or represent a successful registration as proof that a requirement
has been completed. The tester's approval button is intentionally a simulation.

## Testing and troubleshooting

### Bundled tester

Build and install an updated Curbox and tester. For example:

```bash
./gradlew :app:installFdroidDebug :apitester:installDebug
adb shell am start -n neth.iecal.curbox.apitester/.MainActivity
```

Enable Debug Curbox's accessibility service through Android settings when testing enforcement.
The repository also provides `:app:installAndGrantAccessibilityFdroidDebug`; its current helper
writes Android's enabled accessibility service list directly, so it can replace other enabled
services. Use ordinary installation and manual accessibility setup if you need to preserve them.

In API Tester:

1. Tap **Connect** and select the intended Curbox installation if prompted.
2. Check the log for the selected package and `apiVersion=3`.
3. Tap **Request permission** and approve in that same Curbox installation.
4. Check `isGranted`, list groups, and send a command.
5. Use **Dump everything** to inspect all list kinds and today's usage queries.

For provider testing, tap **Register sample requirement**, select **Sample practice goal** in a
Curbox group's warning settings, trigger that group's block, and tap **Check requirement**. Approve
or deny in the sample provider. Use **List requirements** to inspect the registration and **Remove
sample requirement** to verify that a saved group remains blocked without its provider definition.
The sample checks no real location or practice data.

### Suggested integration checks

- Discover no installation, one installation, and both release/debug installations.
- Bind, disconnect, recreate the client Activity, and update Curbox while bound.
- Deny, grant, revoke, and reapprove API access; test the global API switch.
- Use an empty group list, a deleted ID, a wrong command name, and wrong Bundle types.
- Verify a weaker toggle change can return `OK` while its effective setting stays unchanged.
- Verify a protected focus session rejects both stopping and replacement.
- Register the same provider definition twice, then try changing it under the same ID.
- Approve, cancel, return a wrong/missing request ID, and return an expired approval.
- Revoke and restore access while a provider check is open; the old response must not unlock.
- Disable/remove the group or change its warning configuration while checking; the old response
  must not unlock.
- Remove a registration, disable the provider activity, recreate the warning Activity, and test
  signer mismatch; each must fail closed.
- Exercise normal warning timing, proceed limits, on each open behavior, and overlapping groups.
- Verify direct external cooldown broadcasts are rejected and normal Curbox unlocks still work.

### Common problems

| Symptom | Likely cause or next check |
| --- | --- |
| No Curbox service found | Curbox is not installed, package visibility queries are missing, or the installed build has no matching service. |
| `bindService()` returned true but `api` is null | Binding is asynchronous; wait for `onServiceConnected()`. |
| `UNKNOWN_COMMAND` when registering | Check exact `REGISTER_UNLOCK_CONDITION` spelling, `apiVersion() >= 3`, and the selected package. An older release may be installed beside updated Debug Curbox. |
| `DENIED` after previously working | Access was revoked, the API is off, a legacy grant needs reapproval, the app was reinstalled, signing identity changed, or the supplied `provider_package` is invalid. |
| Permission activity immediately closes | Launch for result from an Activity and use the chosen installation's package. Referrer extras do not establish identity. |
| `FAILED` for a targeted toggle | Refresh its group list; verify that `target` is the correct kind of existing ID. |
| `FAILED` for focus start/stop | A live session may disallow early exit, or the target group may be missing. |
| `FAILED` when registering | Check field validation, exported/enabled activity, owning package, manifest permission, duplicate ID definition, signing certificates, and the 50 item limit. |
| `OK` but a blocker stays enabled | The weaker change may be pending, tamper gated, or another group still blocks the target. |
| `OK` but no blocking occurs | Check accessibility service state, schedules, limits, group membership, and relevant global toggles. |
| DND returns `OK` but does not change | Curbox lacks policy access, the filter was already changed, or Curbox did not record ownership of DND. |
| Grayscale returns `OK` but has no effect | Check flavor capability and secure settings access; the command itself does not reject Play Store builds. |
| Query/list returns `null` | Recheck authorization, version, exact name, and Curbox logs. Null does not distinguish these causes. |
| Query values have unexpected types | `query()` uses strings; `list()` uses typed booleans and numbers. |
| Requirement does not appear | Confirm successful registration in the same Curbox installation, version 3, API access, activity availability, and matching certificates. |
| Saved requirement cannot unlock | It may have been removed, disabled, changed, revoked, or become stale. Restore the matching provider or edit the group through Curbox. |
| Provider result is rejected | Check result code, exact request ID, elapsed time, authorization changes, current warning configuration, and Activity recreation. |
| Provider cannot open | Keep its own package outside groups that block the check screen and use a normal exported result activity. |
| Location approval survives leaving the area | Expected: this protocol checks at unlock time and grants the configured access interval. |

### Repository verification

For contributors changing the API implementation:

```bash
./gradlew testFullDebugUnitTest
./gradlew assembleFullDebug assemblePlaystoreDebug assembleFdroidDebug :apitester:assembleDebug
./gradlew connectedFdroidDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=neth.iecal.curbox.api.ApiSecurityTest
```

The last command requires a connected Android device or emulator. Security instrumentation tests
use separate test preferences so they do not change actual API authorizations. Device tests do not
replace testing the real provider Activity handoff and cross-process blocker lifecycle.

## Implementation map

| Source | Responsibility |
| --- | --- |
| [`ICurboxApi.aidl`](app/src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl) | Public Binder interface; preserve package, method order, and signatures |
| [`CurboxApiContract.kt`](app/src/main/java/neth/iecal/curbox/api/CurboxApiContract.kt) | Version, exact operation names, common argument/result keys |
| [`CurboxApiService.kt`](app/src/main/java/neth/iecal/curbox/api/CurboxApiService.kt) | Caller authentication, serialized execution, JSON serialization |
| [`CurboxApiCommands.kt`](app/src/main/java/neth/iecal/curbox/api/CurboxApiCommands.kt) | Command effects, queries, and list response schemas |
| [`ApiAuthStore.kt`](app/src/main/java/neth/iecal/curbox/api/ApiAuthStore.kt) | Grants, signing pins, API switch, and authorization revision |
| [`ApiPermissionActivity.kt`](app/src/main/java/neth/iecal/curbox/api/ApiPermissionActivity.kt) | User approval and verified Activity caller |
| [`ApiPackageIdentity.kt`](app/src/main/java/neth/iecal/curbox/api/ApiPackageIdentity.kt) | APK signing certificate hashes |
| [`ExternalUnlockProviders.kt`](app/src/main/java/neth/iecal/curbox/api/ExternalUnlockProviders.kt) | Registration validation, provider availability, and explicit check intent |
| [`ExternalUnlockAttempt.kt`](app/src/main/java/neth/iecal/curbox/api/ExternalUnlockAttempt.kt) | One-use request ID, expiry, and authorization revision matching |
| [`ApiRestrictionPolicy.kt`](app/src/main/java/neth/iecal/curbox/api/ApiRestrictionPolicy.kt) | Protected focus and current warning configuration checks |
| [`WarningActivity.kt`](app/src/main/java/neth/iecal/curbox/ui/activity/WarningActivity.kt) | Provider Activity launch/result and existing unlock flow |
| [`DataStore.kt`](app/src/main/java/neth/iecal/curbox/utils/DataStore.kt) | Provider persistence, settings gates, sync preservation, atomic focus guard |
| [`MainActivity.kt`](apitester/src/main/java/neth/iecal/curbox/apitester/MainActivity.kt) | Client discovery, installation chooser, permission, and calls |
| [`SampleRequirementActivity.kt`](apitester/src/main/java/neth/iecal/curbox/apitester/SampleRequirementActivity.kt) | Deliberately simulated provider result flow |

Treat the implementation and published AIDL contract as the source of truth when updating this
guide. Do not invent commands, response fields, SDK classes, or implicit bypasses that are not
implemented. Extend the public contract deliberately and bump its version when adding features.
