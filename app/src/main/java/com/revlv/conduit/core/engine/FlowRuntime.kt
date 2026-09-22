package com.revlv.conduit.core.engine

import android.content.Context
import com.revlv.conduit.core.model.ConcurrencyPolicy
import com.revlv.conduit.core.model.Flow
import com.revlv.conduit.core.model.Trigger
import com.revlv.conduit.core.store.FlowStore
import com.revlv.conduit.core.store.RunHistory
import com.revlv.conduit.service.AndroidDeviceController
import com.revlv.conduit.service.ConduitAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers

/**
 * The app's single orchestrator: owns the flow store, decides what runs when,
 * and keeps runs from tripping over each other.
 *
 * One instance per process, created by the Application. Everything that can
 * start a flow — a tile, an alarm, a notification, another flow — comes through
 * [trigger] or [runNow], so concurrency and cooldown rules are enforced once
 * rather than at each call site.
 */
class FlowRuntime(context: Context) {

    private val appContext = context.applicationContext

    val store = FlowStore(appContext)
    val history = RunHistory()

    private val device = AndroidDeviceController(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Flow id → the job currently running it. */
    private val running = mutableMapOf<String, Job>()

    /** Flow id → when it last started, for cooldown enforcement. */
    private val lastStarted = mutableMapOf<String, Long>()

    private val _activeFlowIds = MutableStateFlow<Set<String>>(emptySet())
    val activeFlowIds: StateFlow<Set<String>> = _activeFlowIds.asStateFlow()

    /**
     * Built fresh per run so it always sees the current accessibility service —
     * the system may have destroyed and recreated it since the last run.
     */
    private fun engineFor(): FlowEngine {
        val ui = ConduitAccessibilityService.instance ?: DisconnectedUiController
        return FlowEngine(
            ui = ui,
            device = device,
            conditions = ConditionEvaluator(ui, device),
            flowLookup = { id -> store.get(id) },
        )
    }

    suspend fun initialize() {
        store.load()
    }

    /** Starts a flow by hand, ignoring its cooldown. The user asked for it. */
    fun runNow(flow: Flow): Job = start(flow, respectCooldown = false)

    /**
     * Delivers an event to every enabled flow listening for it.
     * The [matcher] decides which of a flow's triggers count as a match.
     */
    fun trigger(matcher: (Trigger) -> Boolean) {
        val candidates = store.flows.value.filter { flow ->
            flow.enabled && flow.triggers.any(matcher)
        }
        for (flow in candidates) start(flow, respectCooldown = true)
    }

    private fun start(flow: Flow, respectCooldown: Boolean): Job = scope.launch {
        val now = System.currentTimeMillis()

        val proceed = mutex.withLock {
            if (respectCooldown && flow.cooldownSeconds > 0) {
                val previous = lastStarted[flow.id]
                if (previous != null && now - previous < flow.cooldownSeconds * 1000L) {
                    return@withLock false
                }
            }

            val current = running[flow.id]
            if (current != null && current.isActive) {
                when (flow.onConcurrent) {
                    ConcurrencyPolicy.SKIP -> return@withLock false
                    ConcurrencyPolicy.RESTART -> current.cancel()
                    // QUEUE waits below, outside the lock, so the mutex is not
                    // held for the length of another flow's run.
                    ConcurrencyPolicy.QUEUE -> Unit
                }
            }
            true
        }
        if (!proceed) return@launch

        if (flow.onConcurrent == ConcurrencyPolicy.QUEUE) {
            mutex.withLock { running[flow.id] }?.join()
        }

        val job = scope.launch {
            val result = engineFor().run(flow)
            history.record(result)
        }

        mutex.withLock {
            running[flow.id] = job
            lastStarted[flow.id] = now
            _activeFlowIds.value = _activeFlowIds.value + flow.id
        }

        job.join()

        mutex.withLock {
            if (running[flow.id] === job) running.remove(flow.id)
            _activeFlowIds.value = _activeFlowIds.value - flow.id
        }
    }

    fun cancel(flowId: String) {
        scope.launch {
            mutex.withLock { running[flowId] }?.cancel()
        }
    }

    fun cancelAll() {
        scope.launch {
            mutex.withLock { running.values.toList() }.forEach { it.cancel() }
        }
    }

    companion object {
        @Volatile
        private var shared: FlowRuntime? = null

        fun get(context: Context): FlowRuntime =
            shared ?: synchronized(this) {
                shared ?: FlowRuntime(context).also { shared = it }
            }
    }
}

/**
 * Stand-in used when the accessibility service is off.
 *
 * Every UI call fails rather than pretending to succeed, so the run log says
 * "accessibility service is not running" instead of leaving the user staring at
 * a flow that reported success and did nothing.
 */
private object DisconnectedUiController : UiController {
    override val isReady = false
    override fun foregroundPackage(): String? = null
    override fun exists(selector: com.revlv.conduit.core.model.Selector) = false
    override suspend fun waitFor(
        selector: com.revlv.conduit.core.model.Selector,
        timeoutMs: Long,
    ) = false

    override suspend fun waitUntilGone(
        selector: com.revlv.conduit.core.model.Selector,
        timeoutMs: Long,
    ) = false

    override fun click(
        selector: com.revlv.conduit.core.model.Selector,
        climbToClickable: Boolean,
    ) = false

    override fun longClick(
        selector: com.revlv.conduit.core.model.Selector,
        climbToClickable: Boolean,
    ) = false

    override suspend fun tapXY(x: Int, y: Int) = false
    override fun setText(
        selector: com.revlv.conduit.core.model.Selector,
        text: String,
        append: Boolean,
    ) = false

    override fun scroll(
        direction: com.revlv.conduit.core.model.ScrollDirection,
        selector: com.revlv.conduit.core.model.Selector?,
    ) = false

    override suspend fun swipe(
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Long,
    ) = false

    override fun readText(selector: com.revlv.conduit.core.model.Selector): String? = null
    override fun performGlobal(action: com.revlv.conduit.core.model.GlobalActionKind) = false
}
