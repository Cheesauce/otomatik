package com.revlv.conduit.core.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Mutable key/value store for one flow run, plus `{{name}}` interpolation.
 *
 * Interpolation is deliberately dumb: no nesting, no expressions, no control
 * flow. Anything more capable belongs in an explicit action, where it is
 * visible in the flow and in the run log.
 */
class VariableStore(initial: Map<String, String> = emptyMap()) {

    private val values = LinkedHashMap<String, String>(initial)

    operator fun get(name: String): String? = values[name]

    operator fun set(name: String, value: String) {
        values[name] = value
    }

    fun snapshot(): Map<String, String> = LinkedHashMap(values)

    /**
     * Replaces every `{{name}}` with its value. Unknown names resolve to the
     * empty string rather than throwing, so a missing variable degrades to a
     * blank instead of killing a long batch run.
     */
    fun interpolate(input: String): String {
        if (!input.contains("{{")) return input
        return TOKEN.replace(input) { match ->
            val name = match.groupValues[1].trim()
            values[name] ?: builtin(name) ?: ""
        }
    }

    /** Values available without ever being set, resolved fresh on each read. */
    private fun builtin(name: String): String? = when (name) {
        "now" -> SimpleDateFormat("HH:mm", Locale.US).format(Date())
        "now.seconds" -> SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        "date" -> SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        "timestamp" -> System.currentTimeMillis().toString()
        "random" -> Random.nextInt(0, 100).toString()
        "uuid" -> java.util.UUID.randomUUID().toString()
        "newline" -> "\n"
        else -> null
    }

    private companion object {
        val TOKEN = Regex("""\{\{([^}]+)}}""")
    }
}

/**
 * Evaluates the small integer expressions accepted by `Action.Math`.
 *
 * Supports + - * / % over whole numbers with left-to-right precedence for
 * * / % over + -. Intentionally not a general expression language: flows that
 * need real computation should compute the value elsewhere and pass it in.
 */
object MiniMath {

    fun eval(expression: String): Long {
        val tokens = tokenize(expression)
        require(tokens.isNotEmpty()) { "empty expression" }
        // First pass: collapse the high-precedence operators.
        val collapsed = ArrayDeque<String>()
        var i = 0
        collapsed.addLast(tokens[0])
        while (i + 2 < tokens.size) {
            val op = tokens[i + 1]
            val rhs = tokens[i + 2]
            if (op == "*" || op == "/" || op == "%") {
                val lhs = collapsed.removeLast().toLong()
                val r = rhs.toLong()
                val result = when (op) {
                    "*" -> lhs * r
                    "/" -> if (r == 0L) 0L else lhs / r
                    else -> if (r == 0L) 0L else lhs % r
                }
                collapsed.addLast(result.toString())
            } else {
                collapsed.addLast(op)
                collapsed.addLast(rhs)
            }
            i += 2
        }
        // Second pass: left-to-right over what is left.
        val rest = collapsed.toList()
        var acc = rest[0].toLong()
        var j = 1
        while (j + 1 < rest.size) {
            val op = rest[j]
            val value = rest[j + 1].toLong()
            acc = when (op) {
                "+" -> acc + value
                "-" -> acc - value
                else -> throw IllegalArgumentException("unexpected operator '$op'")
            }
            j += 2
        }
        return acc
    }

    private fun tokenize(expression: String): List<String> {
        val out = mutableListOf<String>()
        val number = StringBuilder()
        for (ch in expression.trim()) {
            when {
                ch.isDigit() -> number.append(ch)
                ch == '-' && number.isEmpty() && (out.isEmpty() || out.last() in OPERATORS) ->
                    number.append(ch) // unary minus
                ch.isWhitespace() -> flush(number, out)
                ch.toString() in OPERATORS -> {
                    flush(number, out)
                    out += ch.toString()
                }
                else -> throw IllegalArgumentException("unexpected character '$ch'")
            }
        }
        flush(number, out)
        return out
    }

    private fun flush(buffer: StringBuilder, out: MutableList<String>) {
        if (buffer.isNotEmpty()) {
            out += buffer.toString()
            buffer.clear()
        }
    }

    private val OPERATORS = setOf("+", "-", "*", "/", "%")
}
