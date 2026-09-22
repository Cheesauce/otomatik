package com.revlv.conduit

import com.revlv.conduit.core.engine.FlowEngine
import com.revlv.conduit.core.engine.MiniMath
import com.revlv.conduit.core.engine.RunOutcome
import com.revlv.conduit.core.engine.VariableStore
import com.revlv.conduit.core.model.Action
import com.revlv.conduit.core.model.Comparison
import com.revlv.conduit.core.model.Condition
import com.revlv.conduit.core.model.Flow
import com.revlv.conduit.core.model.FlowJson
import com.revlv.conduit.core.model.Selector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowEngineTest {

    private fun flow(vararg actions: Action, timeout: Int = 30) = Flow(
        id = "test",
        name = "Test",
        actions = actions.toList(),
        timeoutSeconds = timeout,
    )

    @Test
    fun `runs actions in order`() = runTest {
        val ui = FakeUi(mutableSetOf("A", "B"))
        val device = FakeDevice()
        val result = FlowEngine(ui, device).run(
            flow(
                Action.Click(Selector(text = "A")),
                Action.Click(Selector(text = "B")),
            ),
        )
        assertEquals(RunOutcome.Success, result.outcome)
        assertEquals(listOf("A", "B"), ui.clicks)
    }

    @Test
    fun `failing action aborts the run and names the selector`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        val result = FlowEngine(ui, FakeDevice()).run(
            flow(
                Action.Click(Selector(text = "A")),
                Action.Click(Selector(text = "missing")),
                Action.Click(Selector(text = "A")),
            ),
        )
        assertTrue(result.outcome is RunOutcome.Failed)
        assertEquals(listOf("A"), ui.clicks)
        assertTrue(result.log.any { it.message.contains("missing") })
    }

    @Test
    fun `try swallows a failure and runs the recovery branch`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        val device = FakeDevice()
        val result = FlowEngine(ui, device).run(
            flow(
                Action.Try(
                    actions = listOf(Action.Click(Selector(text = "nope"))),
                    onError = listOf(Action.Log("recovered")),
                ),
                Action.Click(Selector(text = "A")),
            ),
        )
        assertEquals(RunOutcome.Success, result.outcome)
        assertEquals(listOf("A"), ui.clicks)
        assertTrue(result.log.any { it.message == "recovered" })
    }

    @Test
    fun `repeat runs the body the requested number of times`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        FlowEngine(ui, FakeDevice()).run(
            flow(Action.Repeat(times = 5, actions = listOf(Action.Click(Selector(text = "A"))))),
        )
        assertEquals(5, ui.clicks.size)
    }

    @Test
    fun `break exits the enclosing repeat rather than skipping one iteration`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        FlowEngine(ui, FakeDevice()).run(
            flow(
                Action.Repeat(
                    times = 10,
                    actions = listOf(
                        Action.Click(Selector(text = "A")),
                        Action.If(
                            condition = Condition.Variable("index", Comparison.GTE, "2"),
                            then = listOf(Action.Break),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(3, ui.clicks.size)
    }

    @Test
    fun `while stops once its condition goes false`() = runTest {
        val ui = FakeUi(mutableSetOf("Load more", "A"))
        // The third click clears the button, which should end the loop.
        ui.removeAfterClick[3] = "Load more"
        FlowEngine(ui, FakeDevice()).run(
            flow(
                Action.While(
                    condition = Condition.OnScreen(Selector(text = "Load more")),
                    actions = listOf(Action.Click(Selector(text = "Load more"))),
                ),
            ),
        )
        assertEquals(3, ui.clicks.size)
    }

    @Test
    fun `while respects its iteration safety limit`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        val result = FlowEngine(ui, FakeDevice()).run(
            flow(
                Action.While(
                    condition = Condition.OnScreen(Selector(text = "A")),
                    actions = listOf(Action.Click(Selector(text = "A"))),
                    maxIterations = 7,
                ),
            ),
        )
        assertEquals(7, ui.clicks.size)
        assertTrue(result.log.any { it.message.contains("safety limit") })
    }

    @Test
    fun `retry logs every attempt before giving up`() = runTest {
        val ui = FakeUi(mutableSetOf())
        val engine = FlowEngine(ui, FakeDevice())
        val result = engine.run(
            flow(
                Action.Retry(
                    actions = listOf(Action.Click(Selector(text = "Later"))),
                    attempts = 3,
                    delayMs = 0,
                ),
            ),
        )
        assertTrue(result.outcome is RunOutcome.Failed)
        assertTrue(result.log.count { it.message.contains("Attempt") } == 3)
    }

    @Test
    fun `flow conditions gate the run`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        val device = FakeDevice(battery = 10)
        val result = FlowEngine(ui, device).run(
            flow(Action.Click(Selector(text = "A"))).copy(
                conditions = listOf(Condition.Battery(Comparison.GT, 50)),
            ),
        )
        assertEquals(RunOutcome.SkippedByConditions, result.outcome)
        assertTrue(ui.clicks.isEmpty())
    }

    @Test
    fun `variables interpolate into text and selectors`() = runTest {
        val ui = FakeUi(mutableSetOf("field"))
        FlowEngine(ui, FakeDevice()).run(
            flow(
                Action.SetVariable("who", "world"),
                Action.SetText(Selector(text = "field"), "hello {{who}}"),
            ),
        )
        assertEquals(listOf("field" to "hello world"), ui.typed)
    }

    @Test
    fun `math accumulates across loop iterations`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        val result = FlowEngine(ui, FakeDevice()).run(
            flow(
                Action.SetVariable("count", "0"),
                Action.Repeat(
                    times = 4,
                    actions = listOf(Action.Math("count", "{{count}} + 2")),
                ),
            ),
        )
        assertEquals("8", result.variables["count"])
    }

    @Test
    fun `stop ends the flow without failing it`() = runTest {
        val ui = FakeUi(mutableSetOf("A"))
        val result = FlowEngine(ui, FakeDevice()).run(
            flow(
                Action.Stop("done early"),
                Action.Click(Selector(text = "A")),
            ),
        )
        assertTrue(result.outcome is RunOutcome.Stopped)
        assertTrue(ui.clicks.isEmpty())
    }

    @Test
    fun `ui actions fail clearly when the accessibility service is off`() = runTest {
        val ui = FakeUi(mutableSetOf("A"), isReady = false)
        val result = FlowEngine(ui, FakeDevice()).run(flow(Action.Click(Selector(text = "A"))))
        assertTrue(result.outcome is RunOutcome.Failed)
        assertTrue(result.log.any { it.message.contains("Accessibility service") })
    }
}

class VariableStoreTest {

    @Test
    fun `unknown variables resolve to empty rather than throwing`() {
        val vars = VariableStore(mapOf("a" to "1"))
        assertEquals("1 and ", vars.interpolate("{{a}} and {{missing}}"))
    }

    @Test
    fun `text without tokens is returned untouched`() {
        val vars = VariableStore()
        assertEquals("plain text", vars.interpolate("plain text"))
    }
}

class MiniMathTest {

    @Test
    fun `respects operator precedence`() {
        assertEquals(7L, MiniMath.eval("1 + 2 * 3"))
        assertEquals(9L, MiniMath.eval("3 * 3"))
        assertEquals(2L, MiniMath.eval("10 % 4"))
        assertEquals(5L, MiniMath.eval("10 / 2"))
    }

    @Test
    fun `division by zero yields zero instead of crashing a flow`() {
        assertEquals(0L, MiniMath.eval("10 / 0"))
    }

    @Test
    fun `handles a leading negative`() {
        assertEquals(-3L, MiniMath.eval("-5 + 2"))
    }
}

class FlowSerializationTest {

    @Test
    fun `a flow survives a round trip through JSON`() {
        val original = Flow(
            id = "x",
            name = "X",
            actions = listOf(
                Action.Click(Selector(viewId = "a:id/b")),
                Action.Repeat(times = 2, actions = listOf(Action.Delay(100))),
            ),
        )
        val json = FlowJson.encodeToString(Flow.serializer(), original)
        val back = FlowJson.decodeFromString(Flow.serializer(), json)
        assertEquals(original, back)
    }

    @Test
    fun `unknown keys are ignored so hand-edited flows still load`() {
        val json = """
            {
              "id": "x",
              "name": "X",
              "note": "a comment I left for myself",
              "actions": [{ "type": "delay", "ms": 5 }]
            }
        """.trimIndent()
        val flow = FlowJson.decodeFromString(Flow.serializer(), json)
        assertEquals(1, flow.actions.size)
    }
}
