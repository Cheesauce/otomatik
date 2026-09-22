package com.revlv.conduit.service

import android.view.accessibility.AccessibilityNodeInfo
import com.revlv.conduit.core.model.Selector

/**
 * Selector matching against the live accessibility tree.
 *
 * Kept separate from the service so the matching rules — the part most likely
 * to need tuning as real apps misbehave — can be read and changed on their own.
 */
object NodeMatching {

    /**
     * Guards against pathological trees. Real screens rarely exceed ~25 levels;
     * a runaway WebView can otherwise stall the whole engine.
     */
    private const val MAX_DEPTH = 60

    /** Depth-first search, returning every node that satisfies [selector]. */
    fun findAll(
        root: AccessibilityNodeInfo?,
        selector: Selector,
        limit: Int = 200,
    ): List<AccessibilityNodeInfo> {
        if (root == null || selector.isEmpty) return emptyList()
        val out = mutableListOf<AccessibilityNodeInfo>()
        walk(root, selector, out, depth = 0, limit = limit)
        return out
    }

    /**
     * Returns the node the selector's [Selector.index] points at, or null.
     * A negative index counts back from the last match.
     */
    fun findOne(root: AccessibilityNodeInfo?, selector: Selector): AccessibilityNodeInfo? {
        val matches = findAll(root, selector)
        if (matches.isEmpty()) return null
        val i = if (selector.index < 0) matches.size + selector.index else selector.index
        return matches.getOrNull(i)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        selector: Selector,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        limit: Int,
    ) {
        if (depth > MAX_DEPTH || out.size >= limit) return
        if (matches(node, selector)) out += node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, selector, out, depth + 1, limit)
            if (out.size >= limit) return
        }
    }

    fun matches(node: AccessibilityNodeInfo, selector: Selector): Boolean {
        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()

        selector.text?.let { if (nodeText != it) return false }
        selector.textContains?.let {
            if (nodeText?.contains(it, ignoreCase = true) != true) return false
        }
        selector.contentDesc?.let { if (nodeDesc != it) return false }
        selector.contentDescContains?.let {
            if (nodeDesc?.contains(it, ignoreCase = true) != true) return false
        }
        selector.viewId?.let { wanted ->
            val actual = node.viewIdResourceName ?: return false
            // Accept both the fully-qualified id and the bare name, so a flow
            // written as "send_button" keeps working across package renames.
            val matchesId = actual == wanted || actual.substringAfterLast('/') == wanted
            if (!matchesId) return false
        }
        selector.className?.let {
            val actual = node.className?.toString() ?: return false
            if (actual != it && actual.substringAfterLast('.') != it.substringAfterLast('.')) {
                return false
            }
        }
        selector.packageName?.let { if (node.packageName?.toString() != it) return false }
        selector.clickable?.let { if (node.isClickable != it) return false }
        selector.scrollable?.let { if (node.isScrollable != it) return false }
        selector.editable?.let { if (node.isEditable != it) return false }
        selector.checked?.let { if (node.isChecked != it) return false }
        selector.enabled?.let { if (node.isEnabled != it) return false }
        return true
    }

    /**
     * Walks up to the nearest ancestor that will accept a click.
     *
     * Most apps put the label in a non-clickable TextView inside a clickable
     * row, so matching the text you can see and clicking it directly fails.
     * This is the single most common cause of "the selector found it but
     * nothing happened".
     */
    fun clickableAncestor(node: AccessibilityNodeInfo, maxHops: Int = 8): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < maxHops) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    /** Same idea for long-press targets. */
    fun longClickableAncestor(
        node: AccessibilityNodeInfo,
        maxHops: Int = 8,
    ): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < maxHops) {
            if (current.isLongClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    /** First scrollable node anywhere in the tree. */
    fun firstScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val found = firstScrollable(root.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    /**
     * Best-effort readable text for a node: its own text, its description, or
     * the concatenated text of its children when the node is just a container.
     */
    fun textOf(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            child.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts += it }
        }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** Compact one-line dump of a node, for the on-device screen inspector. */
    fun describe(node: AccessibilityNodeInfo): String = buildString {
        append(node.className?.toString()?.substringAfterLast('.') ?: "?")
        node.viewIdResourceName?.let { append(" #").append(it.substringAfterLast('/')) }
        node.text?.takeIf { it.isNotBlank() }?.let { append(" \"").append(it).append('"') }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let {
            append(" [").append(it).append(']')
        }
        if (node.isClickable) append(" ·clickable")
        if (node.isEditable) append(" ·editable")
        if (node.isScrollable) append(" ·scrollable")
        if (!node.isEnabled) append(" ·disabled")
    }
}
