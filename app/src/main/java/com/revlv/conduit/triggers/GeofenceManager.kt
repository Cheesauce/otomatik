package com.revlv.conduit.triggers

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.revlv.conduit.core.engine.FlowRuntime
import com.revlv.conduit.core.model.GeofenceEdge
import com.revlv.conduit.core.model.Trigger

/**
 * Registers every flow's geofences with Play Services.
 *
 * Geofences need background location, which Android grants only through a
 * two-step prompt, so this whole subsystem stays dormant until the user has
 * actually created a location-triggered flow.
 */
class GeofenceManager(private val context: Context) {

    private val client by lazy { LocationServices.getGeofencingClient(context) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun rescheduleAll(runtime: FlowRuntime) {
        val wanted = runtime.store.flows.value
            .filter { it.enabled }
            .flatMap { flow ->
                flow.triggers.filterIsInstance<Trigger.Geofence>()
                    .mapIndexed { index, trigger -> buildGeofence(flow.id, index, trigger) }
            }

        client.removeGeofences(pendingIntent())
        if (wanted.isEmpty() || !hasPermission()) return

        val request = GeofencingRequest.Builder()
            // Fires on registration if the device is already inside the fence,
            // so a flow created at home does not wait for the user to leave.
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(wanted)
            .build()
        client.addGeofences(request, pendingIntent())
    }

    private fun buildGeofence(flowId: String, index: Int, trigger: Trigger.Geofence): Geofence {
        val transitions = when (trigger.on) {
            GeofenceEdge.ENTER -> Geofence.GEOFENCE_TRANSITION_ENTER
            GeofenceEdge.EXIT -> Geofence.GEOFENCE_TRANSITION_EXIT
            GeofenceEdge.DWELL -> Geofence.GEOFENCE_TRANSITION_DWELL
        }
        return Geofence.Builder()
            .setRequestId("$flowId#$index")
            .setCircularRegion(trigger.latitude, trigger.longitude, trigger.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitions)
            .apply {
                if (trigger.on == GeofenceEdge.DWELL) setLoiteringDelay(DWELL_DELAY_MS)
            }
            .build()
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, GeofenceReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private companion object {
        const val DWELL_DELAY_MS = 60_000
    }
}

/** Matches a fired geofence back to the flow that registered it. */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val edge = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceEdge.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceEdge.EXIT
            Geofence.GEOFENCE_TRANSITION_DWELL -> GeofenceEdge.DWELL
            else -> return
        }

        val firedIds = event.triggeringGeofences?.map { it.requestId }?.toSet() ?: return
        val runtime = FlowRuntime.get(context)

        for (flow in runtime.store.flows.value) {
            if (!flow.enabled) continue
            flow.triggers.forEachIndexed { index, trigger ->
                if (trigger is Trigger.Geofence &&
                    trigger.on == edge &&
                    "${flow.id}#$index" in firedIds
                ) {
                    runtime.runNow(flow)
                }
            }
        }
    }
}
