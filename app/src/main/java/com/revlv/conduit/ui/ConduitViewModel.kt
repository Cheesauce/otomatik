package com.revlv.conduit.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.revlv.conduit.core.engine.FlowRuntime
import com.revlv.conduit.core.model.Flow
import com.revlv.conduit.core.model.FlowJson
import com.revlv.conduit.service.ConduitAccessibilityService
import com.revlv.conduit.service.ConduitForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConduitViewModel(app: Application) : AndroidViewModel(app) {

    private val runtime = FlowRuntime.get(app)

    val flows: StateFlow<List<Flow>> = runtime.store.flows
    val brokenFiles: StateFlow<Map<String, String>> = runtime.store.brokenFiles
    val activeFlowIds: StateFlow<Set<String>> = runtime.activeFlowIds
    val runs = runtime.history.runs

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { runtime.initialize() }
        ConduitForegroundService.start(app)
    }

    fun runNow(flow: Flow) {
        runtime.runNow(flow)
    }

    fun cancel(flowId: String) = runtime.cancel(flowId)

    fun setEnabled(flow: Flow, enabled: Boolean) {
        viewModelScope.launch { runtime.store.save(flow.copy(enabled = enabled)) }
    }

    fun delete(flow: Flow) {
        viewModelScope.launch { runtime.store.delete(flow.id) }
    }

    /** Saves an edited flow from its JSON form, reporting parse errors verbatim. */
    fun saveJson(json: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val parsed = runCatching { FlowJson.decodeFromString(Flow.serializer(), json) }
            parsed.fold(
                onSuccess = {
                    runtime.store.save(it)
                    _message.value = "Saved \"${it.name}\""
                    onDone(true)
                },
                onFailure = {
                    // The serializer's message names the offending field, which
                    // is far more useful than a generic "invalid JSON".
                    _message.value = it.message ?: "Could not parse that JSON"
                    onDone(false)
                },
            )
        }
    }

    fun newFlow(): Flow = Flow(
        id = "flow-${System.currentTimeMillis()}",
        name = "New flow",
        enabled = false,
    )

    fun toJson(flow: Flow): String = FlowJson.encodeToString(Flow.serializer(), flow)

    fun exportAll(onReady: (String) -> Unit) {
        viewModelScope.launch { onReady(runtime.store.exportAll()) }
    }

    fun import(json: String) {
        viewModelScope.launch {
            runtime.store.import(json).fold(
                onSuccess = { _message.value = "Imported $it flow(s)" },
                onFailure = { _message.value = it.message ?: "Import failed" },
            )
        }
    }

    /** Current screen contents, for authoring selectors against a real app. */
    fun inspectScreen(): List<String> {
        val service = ConduitAccessibilityService.instance
            ?: return listOf("Accessibility service is not running — enable it in Setup.")
        return service.dumpScreen().ifEmpty {
            listOf("No window content. Open the app you want to inspect, then come back.")
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
