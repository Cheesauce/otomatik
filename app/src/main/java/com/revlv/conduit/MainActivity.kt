package com.revlv.conduit

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.revlv.conduit.core.model.Capability
import com.revlv.conduit.core.model.Flow
import com.revlv.conduit.ui.Capabilities
import com.revlv.conduit.ui.ConduitViewModel
import com.revlv.conduit.ui.FlowEditorScreen
import com.revlv.conduit.ui.FlowListScreen
import com.revlv.conduit.ui.InspectorScreen
import com.revlv.conduit.ui.RunsScreen
import com.revlv.conduit.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ConduitViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The one grant that does use a normal prompt. Everything else lives on
        // its own settings screen and is handled in the Setup tab.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { ConduitTheme { AppRoot(viewModel) } }
    }
}

@Composable
private fun ConduitTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF7DD3FC),
            onPrimary = Color(0xFF00344A),
            surfaceVariant = Color(0xFF232A31),
            onSurfaceVariant = Color(0xFFB4BFC9),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0369A1),
            surfaceVariant = Color(0xFFEDF2F7),
            onSurfaceVariant = Color(0xFF4A5661),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab(val label: String) {
    FLOWS("Flows"),
    RUNS("Runs"),
    INSPECT("Inspect"),
    SETUP("Setup"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(viewModel: ConduitViewModel) {
    val context = LocalContext.current

    var tab by remember { mutableStateOf(Tab.FLOWS) }
    var editing by remember { mutableStateOf<Flow?>(null) }
    var editingJson by remember { mutableStateOf("") }
    var isNew by remember { mutableStateOf(false) }
    var inspectorLines by remember { mutableStateOf(listOf<String>()) }
    var capabilityRefresh by remember { mutableStateOf(0) }

    val flows by viewModel.flows.collectAsStateWithLifecycle()
    val activeIds by viewModel.activeFlowIds.collectAsStateWithLifecycle()
    val broken by viewModel.brokenFiles.collectAsStateWithLifecycle()
    val runs by viewModel.runs.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }

    // Recomputed whenever the user returns from a settings screen.
    val statuses = remember(capabilityRefresh) { Capabilities.all(context) }
    val missingRequired = statuses.filter { it.required && !it.granted }.map { it.title }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(editing?.let { if (isNew) "New flow" else it.name } ?: tab.label)
                },
            )
        },
        bottomBar = {
            if (editing == null) {
                NavigationBar {
                    for (entry in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == entry,
                            onClick = {
                                tab = entry
                                if (entry == Tab.SETUP) capabilityRefresh++
                            },
                            icon = { Text(entry.label.take(2)) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (editing == null && tab == Tab.FLOWS) {
                FloatingActionButton(onClick = {
                    val fresh = viewModel.newFlow()
                    editing = fresh
                    editingJson = viewModel.toJson(fresh)
                    isNew = true
                }) { Text("+") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Top,
        ) {
            val current = editing
            if (current != null) {
                FlowEditorScreen(
                    initialJson = editingJson,
                    onSave = { json ->
                        viewModel.saveJson(json) { ok -> if (ok) editing = null }
                    },
                    onDelete = if (isNew) {
                        null
                    } else {
                        { viewModel.delete(current); editing = null }
                    },
                    onRun = { viewModel.runNow(current) },
                )
            } else {
                when (tab) {
                    Tab.FLOWS -> FlowListScreen(
                        flows = flows,
                        activeIds = activeIds,
                        broken = broken,
                        missingRequired = missingRequired,
                        onRun = viewModel::runNow,
                        onCancel = viewModel::cancel,
                        onToggle = viewModel::setEnabled,
                        onOpen = { flow ->
                            editing = flow
                            editingJson = viewModel.toJson(flow)
                            isNew = false
                        },
                    )

                    Tab.RUNS -> RunsScreen(runs)

                    Tab.INSPECT -> InspectorScreen(
                        lines = inspectorLines,
                        onRefresh = { inspectorLines = viewModel.inspectScreen() },
                    )

                    Tab.SETUP -> SetupScreen(
                        statuses = statuses,
                        onGrant = { capability: Capability ->
                            context.startActivity(Capabilities.intentFor(context, capability))
                        },
                        onRefresh = { capabilityRefresh++ },
                    )
                }
            }
        }
    }
}
