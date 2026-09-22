package com.revlv.conduit.core.model

import kotlinx.serialization.Serializable

/**
 * Describes how to find a node in the on-screen accessibility tree.
 *
 * Every non-null field narrows the match, and all supplied fields must hold.
 * Prefer [viewId] where the target app exposes one: it survives translation and
 * copy changes, while [text] matching breaks the moment a label is reworded.
 */
@Serializable
data class Selector(
    /** Exact, case-sensitive match on the node's visible text. */
    val text: String? = null,
    /** Substring match on visible text. Case-insensitive. */
    val textContains: String? = null,
    /** Exact match on contentDescription — often the only handle on icon buttons. */
    val contentDesc: String? = null,
    /** Substring match on contentDescription. Case-insensitive. */
    val contentDescContains: String? = null,
    /**
     * Fully-qualified view id, e.g. "com.example.app:id/send_button".
     * A bare name like "send_button" is resolved against the foreground package.
     */
    val viewId: String? = null,
    /** Widget class, e.g. "android.widget.EditText". */
    val className: String? = null,
    /** Restrict the search to nodes owned by this package. */
    val packageName: String? = null,
    val clickable: Boolean? = null,
    val scrollable: Boolean? = null,
    val editable: Boolean? = null,
    val checked: Boolean? = null,
    val enabled: Boolean? = null,
    /**
     * Which match to take when several nodes qualify, in depth-first order.
     * Negative values count from the end: -1 is the last match.
     */
    val index: Int = 0,
) {
    /** True when no criterion is set, which would match the entire tree. */
    val isEmpty: Boolean
        get() = text == null && textContains == null && contentDesc == null &&
            contentDescContains == null && viewId == null && className == null &&
            packageName == null && clickable == null && scrollable == null &&
            editable == null && checked == null && enabled == null

    /** Short human-readable form, used in run logs and error messages. */
    fun describe(): String = buildList {
        text?.let { add("text=\"$it\"") }
        textContains?.let { add("text~\"$it\"") }
        contentDesc?.let { add("desc=\"$it\"") }
        contentDescContains?.let { add("desc~\"$it\"") }
        viewId?.let { add("id=$it") }
        className?.let { add("class=${it.substringAfterLast('.')}") }
        packageName?.let { add("pkg=$it") }
        clickable?.let { add("clickable=$it") }
        scrollable?.let { add("scrollable=$it") }
        editable?.let { add("editable=$it") }
        checked?.let { add("checked=$it") }
        enabled?.let { add("enabled=$it") }
        if (index != 0) add("#$index")
    }.joinToString(", ").ifEmpty { "<any node>" }
}
