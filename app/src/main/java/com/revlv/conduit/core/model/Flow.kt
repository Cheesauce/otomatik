package com.revlv.conduit.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A complete automation: what starts it, what has to be true, and what it does.
 *
 * This is the app's file format. Flows are plain JSON on disk, so they can be
 * hand-written, generated, version-controlled, and shared between devices — the
 * visual builder is a second editor over the same structure, never a separate one.
 */
@Serializable
data class Flow(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val triggers: List<Trigger> = emptyList(),
    /** Gate for the whole flow. All must pass or the run is skipped. */
    val conditions: List<Condition> = emptyList(),
    val actions: List<Action> = emptyList(),
    /** Seed values for the variable store, available as `{{name}}`. */
    val variables: Map<String, String> = emptyMap(),
    /** Hard stop for runaway flows. Zero disables the limit. */
    val timeoutSeconds: Int = 300,
    /** What to do when this flow is triggered while an instance is already running. */
    val onConcurrent: ConcurrencyPolicy = ConcurrencyPolicy.SKIP,
    /** Minimum gap between runs; a second trigger inside the window is ignored. */
    val cooldownSeconds: Int = 0,
    /** Free-form labels, used only for grouping in the UI. */
    val tags: List<String> = emptyList(),
) {
    /** Capabilities this flow needs, computed so the UI can prompt for them up front. */
    fun requiredCapabilities(): Set<Capability> = buildSet {
        if (triggers.any { it is Trigger.Notification }) add(Capability.NOTIFICATION_ACCESS)
        if (triggers.any { it is Trigger.AppOpened || it is Trigger.AppClosed }) {
            add(Capability.ACCESSIBILITY)
        }
        if (triggers.any { it is Trigger.Geofence }) add(Capability.BACKGROUND_LOCATION)
        if (triggers.any { it is Trigger.Time }) add(Capability.EXACT_ALARM)
        addAll(capabilitiesOf(actions))
        if (conditions.any { it.needsAccessibility() }) add(Capability.ACCESSIBILITY)
    }

    private fun capabilitiesOf(list: List<Action>): Set<Capability> = buildSet {
        for (action in list) {
            when (action) {
                is Action.Click, is Action.LongClick, is Action.TapXY, is Action.SetText,
                is Action.Scroll, is Action.Swipe, is Action.Global, is Action.WaitFor,
                is Action.WaitUntilGone, is Action.ReadText,
                -> add(Capability.ACCESSIBILITY)

                is Action.SetDnd -> add(Capability.NOTIFICATION_POLICY)
                is Action.SetRingerMode ->
                    if (action.mode == RingerMode.SILENT) add(Capability.NOTIFICATION_POLICY)

                is Action.SetBrightness -> add(Capability.WRITE_SETTINGS)
                is Action.Notify -> add(Capability.POST_NOTIFICATIONS)

                // Recurse into nested bodies so a Click inside a Repeat still counts.
                is Action.Repeat -> addAll(capabilitiesOf(action.actions))
                is Action.While -> addAll(capabilitiesOf(action.actions))
                is Action.If -> {
                    addAll(capabilitiesOf(action.then))
                    addAll(capabilitiesOf(action.otherwise))
                }
                is Action.Try -> {
                    addAll(capabilitiesOf(action.actions))
                    addAll(capabilitiesOf(action.onError))
                }
                is Action.Retry -> addAll(capabilitiesOf(action.actions))
                else -> Unit
            }
        }
    }

    private fun Condition.needsAccessibility(): Boolean = when (this) {
        is Condition.OnScreen, is Condition.ForegroundApp -> true
        is Condition.Not -> condition.needsAccessibility()
        is Condition.AllOf -> conditions.any { it.needsAccessibility() }
        is Condition.AnyOf -> conditions.any { it.needsAccessibility() }
        else -> false
    }
}

@Serializable
enum class ConcurrencyPolicy {
    /** Ignore the new trigger while a run is in flight. */
    SKIP,

    /** Cancel the running instance and start over. */
    RESTART,

    /** Wait for the running instance, then run. */
    QUEUE,
}

/** A permission or system grant the user must hand over manually. */
enum class Capability {
    ACCESSIBILITY,
    NOTIFICATION_ACCESS,
    NOTIFICATION_POLICY,
    WRITE_SETTINGS,
    POST_NOTIFICATIONS,
    BACKGROUND_LOCATION,
    EXACT_ALARM,
    BATTERY_UNRESTRICTED,
}

/** Shared JSON codec. Lenient on read so a hand-edited flow survives small slips. */
@OptIn(ExperimentalSerializationApi::class)
val FlowJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
}
