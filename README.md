# Conduit

A fully customizable automation app for Android — the Shortcuts-style
trigger → condition → action engine, built as a sideloaded personal app.

Flows are plain JSON files. The app is the runtime and the editor; the format is
the source of truth.

## Why this is a sideload app, not a Play Store app

Driving other apps requires `AccessibilityService`, and Google Play policy
restricts that API to genuine accessibility tools. Every automation app of this
kind (Tasker, MacroDroid, Automate) lives with the same constraint. For a
personal app installed over USB this costs nothing.

## What works without root

| Capability | Status |
|---|---|
| Tap / type / scroll / swipe in any app | ✅ via AccessibilityService |
| Read on-screen text into variables | ✅ |
| Launch apps, open deep links, fire intents | ✅ |
| React to notifications | ✅ via NotificationListenerService |
| Time, interval, boot, power, screen triggers | ✅ |
| Geofence triggers | ✅ needs background location |
| Do Not Disturb, ringer mode, volumes | ✅ needs DND access |
| Screen brightness | ✅ needs Modify system settings |
| Media play/pause/next | ✅ |
| **Toggle WiFi / Bluetooth / mobile data / airplane mode** | ❌ **blocked by Android since 10** — the app opens the relevant settings panel instead |
| Force-stop another app | ❌ needs Shizuku or root |
| Arbitrary shell commands | ❌ needs Shizuku or root |

The blocked rows are deliberately absent from the action vocabulary rather than
present-and-silently-failing.

## Build

Toolchain (already installed on this machine via Homebrew):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Build the debug APK:

```bash
./gradlew :app:assembleDebug
```

Install onto a connected device (USB debugging on):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First run

1. Open the app, go to **Setup**, and grant everything marked required.
   - **Accessibility service** — nothing that touches another app works without it.
   - **Unrestricted battery** — without it Android suspends the runtime after a
     few minutes and triggers quietly stop firing. This is the single most
     common cause of "it worked yesterday".
2. Go to **Inspect**, open the app you want to automate, come back, and tap
   *Capture current screen* to read out the view ids and texts you need.
3. Create a flow with **+**, or edit one of the four seeded samples.

## Flow format

See [docs/FLOW-FORMAT.md](docs/FLOW-FORMAT.md) for the full reference.

A minimal flow:

```json
{
  "id": "morning",
  "name": "Morning",
  "enabled": true,
  "triggers": [{ "type": "time", "at": "07:00", "days": ["MON", "TUE"] }],
  "conditions": [{ "type": "charging", "value": false }],
  "actions": [
    { "type": "launchApp", "packageName": "com.spotify.music" },
    { "type": "waitFor", "selector": { "textContains": "Play" }, "timeoutMs": 8000 },
    { "type": "click", "selector": { "textContains": "Play" } }
  ]
}
```

## Triggering a flow from outside the app

```bash
adb shell am broadcast -a com.revlv.conduit.TRIGGER --es name my-flow-name
```

Matches any flow with a `{"type": "broadcast", "name": "my-flow-name"}` trigger.

## Architecture

```
core/model     Flow, Trigger, Condition, Action — the JSON format
core/engine    FlowEngine (control flow), FlowRuntime (scheduling, concurrency)
core/store     FlowStore (JSON files on disk), RunHistory
service        AccessibilityService (UiController), DeviceController, foreground service
triggers       Alarms, notifications, system events, geofences
ui             Compose: flow list, JSON editor, run logs, screen inspector, setup
```

`FlowEngine` depends only on the `UiController` / `DeviceController` interfaces,
so control flow can be tested without a device.

## Known constraints

- **Screen-driven flows are brittle by nature.** They break when the target app
  redesigns. Prefer `viewId` over `text`, and prefer deep links over tapping.
- **Always `waitFor` before interacting.** Without it every step races the target
  app's rendering, which is why a macro works once and then fails.
- **Aggressive OEM power management** (Samsung, Xiaomi, Oppo, Realme, OnePlus)
  may kill the runtime regardless of the battery exemption. Those devices need
  the app added to a vendor-specific "protected apps" allowlist too.
