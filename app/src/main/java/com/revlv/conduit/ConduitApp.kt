package com.revlv.conduit

import android.app.Application
import com.revlv.conduit.core.engine.FlowRuntime
import com.revlv.conduit.core.store.SampleFlows
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ConduitApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val runtime = FlowRuntime.get(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runtime.initialize()
            // A brand-new install with an empty list gives the user nothing to
            // look at and nothing to copy, so seed it once with worked examples.
            if (runtime.store.flows.value.isEmpty()) {
                SampleFlows.all().forEach { runtime.store.save(it) }
            }
        }
    }
}
