package com.revlv.conduit.core.store

import android.content.Context
import com.revlv.conduit.core.engine.RunResult
import com.revlv.conduit.core.model.Flow
import com.revlv.conduit.core.model.FlowJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Flows on disk, one JSON file each.
 *
 * Plain files rather than a database, deliberately: a flow is a document the
 * user owns. They can be exported, diffed, kept in a repo, dropped into a
 * backup, or written by hand — none of which survives being locked inside a
 * schema-versioned SQLite table.
 */
class FlowStore(context: Context) {

    private val directory = File(context.filesDir, "flows").apply { mkdirs() }

    private val _flows = MutableStateFlow<List<Flow>>(emptyList())
    val flows: StateFlow<List<Flow>> = _flows.asStateFlow()

    /** Files that failed to parse, surfaced in the UI rather than swallowed. */
    private val _brokenFiles = MutableStateFlow<Map<String, String>>(emptyMap())
    val brokenFiles: StateFlow<Map<String, String>> = _brokenFiles.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        val parsed = mutableListOf<Flow>()
        val broken = mutableMapOf<String, String>()
        directory.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                try {
                    parsed += FlowJson.decodeFromString(Flow.serializer(), file.readText())
                } catch (e: Exception) {
                    broken[file.name] = e.message ?: "Could not parse"
                }
            }
        _flows.value = parsed
        _brokenFiles.value = broken
    }

    suspend fun save(flow: Flow) = withContext(Dispatchers.IO) {
        val text = FlowJson.encodeToString(Flow.serializer(), flow)
        // Write to a temp file and rename, so a crash mid-write cannot leave a
        // truncated flow behind.
        val target = fileFor(flow.id)
        val temp = File(directory, "${flow.id}.json.tmp")
        temp.writeText(text)
        temp.renameTo(target)
        load()
    }

    suspend fun delete(flowId: String) = withContext(Dispatchers.IO) {
        fileFor(flowId).delete()
        load()
    }

    fun get(flowId: String): Flow? = _flows.value.firstOrNull { it.id == flowId }

    suspend fun exportAll(): String = withContext(Dispatchers.IO) {
        FlowJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(Flow.serializer()),
            _flows.value,
        )
    }

    /** Imports one flow or an array of them, returning how many landed. */
    suspend fun import(json: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = json.trim()
            val incoming = if (trimmed.startsWith("[")) {
                FlowJson.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(Flow.serializer()),
                    trimmed,
                )
            } else {
                listOf(FlowJson.decodeFromString(Flow.serializer(), trimmed))
            }
            for (flow in incoming) save(flow)
            incoming.size
        }
    }

    private fun fileFor(id: String): File = File(directory, "${id.sanitized()}.json")

    /** Ids reach the filesystem, so anything path-like has to go. */
    private fun String.sanitized(): String =
        replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "flow" }
}

/**
 * Recent run results, kept in memory and capped.
 *
 * Runs are debugging output, not user data: losing them on reboot costs
 * nothing, and writing every step of a 500-iteration batch to disk would cost
 * a great deal.
 */
class RunHistory(private val capacity: Int = 50) {

    private val _runs = MutableStateFlow<List<RunResult>>(emptyList())
    val runs: StateFlow<List<RunResult>> = _runs.asStateFlow()

    fun record(result: RunResult) {
        _runs.value = (listOf(result) + _runs.value).take(capacity)
    }

    fun clear() {
        _runs.value = emptyList()
    }

    fun lastRunOf(flowId: String): RunResult? = _runs.value.firstOrNull { it.flowId == flowId }
}
