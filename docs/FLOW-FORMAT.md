# Flow format reference

A flow is one JSON object. The `type` field is the discriminator on every
trigger, condition and action. Unknown keys are ignored on load, so you can keep
notes inline.

## Top level

| Field | Type | Default | Meaning |
|---|---|---|---|
| `id` | string | — | Unique; also the filename on disk |
| `name` | string | — | Shown in the list |
| `description` | string | `""` | Shown under the name |
| `enabled` | bool | `true` | Disabled flows ignore triggers but can still be run by hand |
| `triggers` | array | `[]` | Any one firing starts the flow |
| `conditions` | array | `[]` | **All** must pass or the run is skipped |
| `actions` | array | `[]` | Run in order |
| `variables` | object | `{}` | Seed values, readable as `{{name}}` |
| `timeoutSeconds` | int | `300` | Hard stop; `0` disables |
| `onConcurrent` | enum | `SKIP` | `SKIP` / `RESTART` / `QUEUE` |
| `cooldownSeconds` | int | `0` | Ignore re-triggers inside this window |
| `tags` | array | `[]` | Grouping only |

## Variables

Any string field supports `{{name}}`. Unknown names resolve to `""` rather than
failing, so a missing value degrades to a blank instead of killing a long run.

Built-ins, resolved fresh on each read: `{{now}}`, `{{now.seconds}}`, `{{date}}`,
`{{timestamp}}`, `{{random}}`, `{{uuid}}`, `{{newline}}`.

Set by the engine: `{{index}}` inside loops, `{{found}}` after `waitFor`,
`{{error}}` inside a `try`'s `onError`.

## Selectors

How a node on screen is found. Every field you set must match.

```json
{ "viewId": "com.example:id/send", "clickable": true, "index": 0 }
```

| Field | Meaning |
|---|---|
| `text` | Exact visible text |
| `textContains` | Substring, case-insensitive |
| `contentDesc` / `contentDescContains` | Same for contentDescription — often the only handle on icon buttons |
| `viewId` | `pkg:id/name`, or just `name` |
| `className` | e.g. `android.widget.EditText` |
| `packageName` | Restrict to one app |
| `clickable` `scrollable` `editable` `checked` `enabled` | Boolean filters |
| `index` | Which match, depth-first. Negative counts from the end: `-1` is last |

**Prefer `viewId`.** It survives translations and copy changes; `text` does not.

Use the app's **Inspect** tab to read the current screen's ids and texts.

## Triggers

| `type` | Fields |
|---|---|
| `manual` | — |
| `time` | `at` (`"HH:mm"`), `days` (`["MON",…]`, empty = daily) |
| `interval` | `everyMinutes` |
| `notification` | `packages`, `titleContains`, `textContains`, `ignoreOngoing` |
| `appOpened` / `appClosed` | `packageName` |
| `geofence` | `latitude`, `longitude`, `radiusMeters`, `on` (`ENTER`/`EXIT`/`DWELL`), `label` |
| `boot` | — |
| `power` | `on`: `CONNECTED` / `DISCONNECTED` |
| `screen` | `on`: `ON` / `OFF` / `UNLOCKED` |
| `broadcast` | `name` — fires from `adb shell am broadcast` |

## Conditions

| `type` | Fields |
|---|---|
| `battery` | `op`, `percent` |
| `charging` | `value` |
| `timeRange` | `from`, `to` — wraps midnight correctly |
| `weekday` | `days` |
| `foregroundApp` | `packageName` |
| `onScreen` | `selector` |
| `screenOn` | `value` |
| `connected` | `value` |
| `variable` | `name`, `op`, `value` |
| `appInstalled` | `packageName` |
| `not` | `condition` |
| `allOf` / `anyOf` | `conditions` |

`op` is one of `==` `!=` `<` `<=` `>` `>=` `contains` `matches`. When both sides
parse as numbers, ordering comparisons are numeric, so `"10" > "9"` is true.

## Actions

### Launching
| `type` | Fields |
|---|---|
| `launchApp` | `packageName`, `activity?` |
| `openUrl` | `url` |
| `sendIntent` | `action`, `data?`, `packageName?`, `component?`, `extras?`, `kind` (`ACTIVITY`/`BROADCAST`/`SERVICE`) |
| `share` | `text`, `packageName?` |

### Screen (all need the accessibility service)
| `type` | Fields |
|---|---|
| `click` / `longClick` | `selector`, `climbToClickable` (default `true`) |
| `tapXY` | `x`, `y` |
| `setText` | `selector`, `text`, `append` |
| `scroll` | `direction`, `selector?`, `times` |
| `swipe` | `fromX`, `fromY`, `toX`, `toY`, `durationMs` |
| `global` | `action`: `BACK` `HOME` `RECENTS` `NOTIFICATIONS` `QUICK_SETTINGS` `LOCK_SCREEN` `POWER_DIALOG` `SPLIT_SCREEN` |
| `waitFor` | `selector`, `timeoutMs`, `optional` |
| `waitGone` | `selector`, `timeoutMs`, `optional` |
| `readText` | `selector`, `into`, `default` |

`climbToClickable` walks up to the nearest clickable ancestor. Most apps put the
label in a non-clickable `TextView` inside a clickable row, so matching the text
you can see and clicking it directly fails — this is the usual explanation for
"the selector found it but nothing happened".

### Device
| `type` | Fields | Needs |
|---|---|---|
| `ringerMode` | `mode`: `SILENT`/`VIBRATE`/`NORMAL` | DND access for `SILENT` |
| `dnd` | `enabled` | DND access |
| `volume` | `stream`, `percent` | — |
| `brightness` | `percent`, `auto` | Modify system settings |
| `media` | `command` | — |
| `openSettings` | `panel` | — |

`openSettings` is the honest fallback for WiFi, Bluetooth, mobile data and
airplane mode, which Android no longer lets third-party apps change.

### Feedback
`notify` (`title`, `text`, `id`), `toast` (`text`), `vibrate` (`durationMs`),
`log` (`message`).

Reusing a `notify` id replaces the previous notification instead of stacking
hundreds of them during a batch run.

### Control flow
| `type` | Fields |
|---|---|
| `delay` | `ms` |
| `repeat` | `times`, `actions`, `indexVar` |
| `while` | `condition`, `actions`, `maxIterations` (default 500), `indexVar` |
| `if` | `condition`, `then`, `otherwise` |
| `break` / `continue` / `stop` | `stop` takes `message` |
| `try` | `actions`, `onError` |
| `retry` | `actions`, `attempts`, `delayMs` |
| `setVar` | `name`, `value` |
| `math` | `name`, `expression` — integers with `+ - * / %` |
| `runFlow` | `flowId`, `await` — shares the caller's variables, like a subroutine |

## Writing flows that keep working

1. **`waitFor` before every interaction.** Without it each step races the target
   app's rendering.
2. **`viewId` over `text`.** Ids survive copy changes and translations.
3. **Wrap batch iterations in `try`.** Over hundreds of iterations something
   will eventually be missing; the default abort would throw away all the
   completed work.
4. **Deep links over tapping.** `openUrl` with an app's own scheme is far more
   stable than navigating its UI.
5. **Set a real `timeoutSeconds`** on long jobs — the 300 s default will cut off
   a 500-iteration batch.
