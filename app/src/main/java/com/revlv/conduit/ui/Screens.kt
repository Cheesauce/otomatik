package com.revlv.conduit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.revlv.conduit.core.engine.RunResult
import com.revlv.conduit.core.model.Capability
import com.revlv.conduit.core.model.Flow

@Composable
fun FlowListScreen(
    flows: List<Flow>,
    activeIds: Set<String>,
    broken: Map<String, String>,
    missingRequired: List<String>,
    onRun: (Flow) -> Unit,
    onCancel: (String) -> Unit,
    onToggle: (Flow, Boolean) -> Unit,
    onOpen: (Flow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (missingRequired.isNotEmpty()) {
            item {
                WarningCard(
                    title = "Setup incomplete",
                    body = "Flows will not run reliably until you grant: " +
                        missingRequired.joinToString(", ") + ".",
                )
            }
        }

        for ((file, error) in broken) {
            item { WarningCard(title = "Could not load $file", body = error) }
        }

        if (flows.isEmpty()) {
            item {
                Text(
                    "No flows yet. Tap + to create one.",
                    modifier = Modifier.padding(vertical = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(flows, key = { it.id }) { flow ->
            val running = flow.id in activeIds
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                flow.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (flow.description.isNotBlank()) {
                                Text(
                                    flow.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Switch(
                            checked = flow.enabled,
                            onCheckedChange = { onToggle(flow, it) },
                        )
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        summarize(flow),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running) {
                            Button(onClick = { onCancel(flow.id) }) { Text("Stop") }
                        } else {
                            Button(onClick = { onRun(flow) }) { Text("Run") }
                        }
                        OutlinedButton(onClick = { onOpen(flow) }) { Text("Edit") }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

private fun summarize(flow: Flow): String {
    val triggers = flow.triggers.size
    val steps = flow.actions.size
    val caps = flow.requiredCapabilities()
    val needs = if (Capability.ACCESSIBILITY in caps) " · needs accessibility" else ""
    return "$triggers trigger(s) · $steps step(s)$needs"
}

@Composable
private fun WarningCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
fun FlowEditorScreen(
    initialJson: String,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onRun: () -> Unit,
) {
    var text by remember { mutableStateOf(initialJson) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Flow definition",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Edit the JSON directly. Unknown fields are ignored, so you can " +
                "leave notes as extra keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(text) }) { Text("Save") }
            OutlinedButton(onClick = onRun) { Text("Run") }
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
fun SetupScreen(
    statuses: List<Capabilities.Status>,
    onGrant: (Capability) -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Android grants each of these on a different screen. Nothing " +
                    "here can be granted from inside the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        items(statuses, key = { it.capability.name }) { status ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (status.granted) "✓" else if (status.required) "✕" else "—",
                            fontWeight = FontWeight.Bold,
                            color = if (status.granted) {
                                Color(0xFF2E7D32)
                            } else if (status.required) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(0.dp))
                        Text(
                            "  " + status.title + if (status.required) "" else "  (optional)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        status.why,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!status.granted) {
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onGrant(status.capability) }) { Text("Open settings") }
                    }
                }
            }
        }

        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.padding(bottom = 60.dp)) {
                Text("Re-check")
            }
        }
    }
}

@Composable
fun InspectorScreen(lines: List<String>, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Screen inspector",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Open the app you want to automate, switch back here, and refresh. " +
                "Use the #ids and \"text\" below to write selectors.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRefresh) { Text("Capture current screen") }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(8.dp),
                )
                .padding(8.dp),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
fun RunsScreen(runs: List<RunResult>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (runs.isEmpty()) {
            item {
                Text(
                    "No runs yet.",
                    modifier = Modifier.padding(vertical = 32.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(runs) { run ->
            var expanded by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        run.flowName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "${run.outcome::class.simpleName} · ${run.durationMs} ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (run.succeeded) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide log" else "Show log (${run.log.size})")
                    }
                    if (expanded) {
                        HorizontalDivider()
                        Spacer(Modifier.height(6.dp))
                        for (entry in run.log) {
                            Text(
                                "  ".repeat(entry.depth) + "[${entry.level}] " + entry.message,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
