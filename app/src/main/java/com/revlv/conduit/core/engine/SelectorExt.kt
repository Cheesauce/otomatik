package com.revlv.conduit.core.engine

import com.revlv.conduit.core.model.Selector

/**
 * Resolves `{{variable}}` tokens inside a selector's text fields, so a flow can
 * search for something it computed earlier — "open the chat with {{contact}}".
 */
fun Selector.interpolate(vars: VariableStore): Selector = copy(
    text = text?.let(vars::interpolate),
    textContains = textContains?.let(vars::interpolate),
    contentDesc = contentDesc?.let(vars::interpolate),
    contentDescContains = contentDescContains?.let(vars::interpolate),
    viewId = viewId?.let(vars::interpolate),
    packageName = packageName?.let(vars::interpolate),
)
