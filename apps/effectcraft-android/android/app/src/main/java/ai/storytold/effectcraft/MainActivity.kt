package ai.storytold.effectcraft

import android.graphics.Bitmap
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.Image
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import ai.storytold.effectcraft.bridge.MobileSession
import ai.storytold.effectcraft.bridge.PreviewFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var session: MobileSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configDir = File(filesDir, "effectcraft").apply { mkdirs() }
        session = MobileSession(configDir.absolutePath)
        val startupError = session.startupError()
        setContent {
            MaterialTheme {
                EffectCraftScreen(session, startupError)
            }
        }
    }

    override fun onStop() {
        if (::session.isInitialized) {
            lifecycleScope.launch(Dispatchers.IO) { session.saveRecovery() }
        }
        super.onStop()
    }
}

private data class LayerRow(val id: Long, val name: String, val selected: Boolean)
private data class CompRow(
    val id: Long,
    val name: String,
    val width: Int,
    val height: Int,
    val duration: Float,
    val currentTime: Float,
    val layers: List<LayerRow>,
)
private data class EditorOverview(val activeComposition: Long?, val compositions: List<CompRow>)
private data class EngineCommand(val id: String, val label: String, val menu: String, val params: String, val enabled: Boolean)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun EffectCraftScreen(session: MobileSession, startupError: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commandLock = remember { Mutex() }
    var overviewJson by remember { mutableStateOf(session.overviewJson()) }
    var commandsJson by remember { mutableStateOf(session.commandsJson()) }
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf(startupError.ifBlank { "Ready" }) }
    var busy by remember { mutableStateOf(false) }
    var showNewComposition by remember { mutableStateOf(false) }
    var showCommandPalette by remember { mutableStateOf(false) }

    val overview = remember(overviewJson) { parseOverview(overviewJson) }
    val commands = remember(commandsJson) { parseCommands(commandsJson) }
    val activeComposition = overview.compositions.firstOrNull { it.id == overview.activeComposition }
        ?: overview.compositions.firstOrNull()
    val duration = activeComposition?.duration?.coerceAtLeast(0.001f) ?: 1f
    var timelineTime by remember(activeComposition?.id, activeComposition?.currentTime) {
        mutableStateOf((activeComposition?.currentTime ?: 0f).coerceIn(0f, duration))
    }

    LaunchedEffect(session) {
        val loaded = withContext(Dispatchers.IO) {
            Triple(session.overviewJson(), session.commandsJson(), toPreviewBitmap(session.renderPreview()))
        }
        overviewJson = loaded.first
        commandsJson = loaded.second
        previewBitmap = loaded.third
    }

    fun dispatch(command: String, params: String = "{}") {
        scope.launch {
            commandLock.withLock {
                busy = true
                try {
                    val (reply, nextOverview, nextCommands, recovery, preview) = withContext(Dispatchers.IO) {
                        val result = JSONObject(session.execute(command, params))
                        val recovery = if (result.optBoolean("ok")) JSONObject(session.saveRecovery()) else JSONObject()
                        val preview = if (result.optBoolean("ok")) toPreviewBitmap(session.renderPreview()) else null
                        Quintuple(result, session.overviewJson(), session.commandsJson(), recovery, preview)
                    }
                    overviewJson = nextOverview
                    commandsJson = nextCommands
                    if (reply.optBoolean("ok")) previewBitmap = preview
                    status = when {
                        !reply.optBoolean("ok") -> reply.optString("error", "Command failed")
                        !recovery.optBoolean("ok", true) -> recovery.optString("error", "Recovery save failed")
                        reply.has("warning") -> reply.optString("warning")
                        else -> "${command}: complete"
                    }
                } catch (error: Exception) {
                    status = error.message ?: "Command failed"
                } finally {
                    busy = false
                }
            }
        }
    }

    val openProject = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) { copyUri(context, uri) }
                if (copied.isSuccess) {
                    val file = copied.getOrThrow()
                    dispatch("file.open", JSONObject().put("path", file.absolutePath).toString())
                } else {
                    status = copied.exceptionOrNull()?.message ?: "Could not open project"
                }
            }
        }
    }
    val importMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> copyUri(context, uri).getOrNull() }
                }
                if (copied.isNotEmpty()) {
                    val paths = JSONArray()
                    copied.forEach { paths.put(it.absolutePath) }
                    dispatch("file.importMultiple", JSONObject().put("paths", paths).toString())
                } else {
                    status = "No files could be imported"
                }
            }
        }
    }
    val saveProject = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        val privateCopy = File(context.filesDir, "projects/EffectCraft.ecproj")
                        val privateDir = privateCopy.parentFile ?: error("Invalid project storage path")
                        check(privateDir.mkdirs() || privateDir.isDirectory) { "Could not create project storage" }
                        val engineSave = JSONObject(
                            session.execute("file.saveAs", JSONObject().put("path", privateCopy.absolutePath).toString()),
                        )
                        check(engineSave.optBoolean("ok")) { engineSave.optString("error", "Could not save project") }
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(session.projectJson().toByteArray(Charsets.UTF_8))
                        } ?: error("Could not open the selected destination")
                        val recovery = JSONObject(session.saveRecovery())
                        check(recovery.optBoolean("ok")) { recovery.optString("error", "Could not save recovery project") }
                    }
                }
                status = if (saved.isSuccess) "Project saved" else saved.exceptionOrNull()?.message ?: "Could not save project"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("EffectCraft", fontWeight = FontWeight.SemiBold)
                        Text(activeComposition?.name ?: "Untitled Project", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = { dispatch("edit.undo") }, enabled = !busy) { Text("Undo") }
                    TextButton(onClick = { dispatch("edit.redo") }, enabled = !busy) { Text("Redo") }
                },
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openProject.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Open") }
                OutlinedButton(onClick = { importMedia.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Import") }
                OutlinedButton(onClick = { saveProject.launch("Untitled.ecproj") }, enabled = !busy) { Text("Save As") }
                OutlinedButton(onClick = { showCommandPalette = true }, enabled = !busy) { Text("Commands") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showNewComposition = true }, enabled = !busy) { Text("New Composition") }
                OutlinedButton(
                    onClick = { dispatch("layer.newSolid", """{"name":"Solid","color":"#e0368f"}""") },
                    enabled = !busy && activeComposition != null,
                ) { Text("Add Solid") }
                OutlinedButton(
                    onClick = { dispatch("layer.newText", """{"text":"Text","name":"Text"}""") },
                    enabled = !busy && activeComposition != null,
                ) { Text("Add Text") }
            }

            if (busy) CircularProgressIndicator()
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Composition Viewer", style = MaterialTheme.typography.titleMedium)
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = activeComposition?.name ?: "Composition preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        )
                    } else {
                        Text("Create or open a composition to preview it.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Timeline", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = timelineTime.coerceIn(0f, duration),
                        onValueChange = { timelineTime = it },
                        valueRange = 0f..duration,
                        enabled = !busy && activeComposition != null,
                        onValueChangeFinished = {
                            dispatch("time.set", JSONObject().put("time", timelineTime.toDouble()).toString())
                        },
                    )
                    Text("${"%.2f".format(timelineTime)} s / ${"%.2f".format(duration)} s", style = MaterialTheme.typography.labelMedium)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Compositions", style = MaterialTheme.typography.titleMedium)
                    if (overview.compositions.isEmpty()) {
                        Text("Create a composition to begin.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        overview.compositions.forEach { comp ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { dispatch("comp.open", """{"comp":${comp.id}}""") }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(comp.name, fontWeight = if (comp.id == overview.activeComposition) FontWeight.SemiBold else FontWeight.Normal)
                                Text("${comp.width} × ${comp.height} · ${comp.layers.size} layers", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Layers", style = MaterialTheme.typography.titleMedium)
                    if (activeComposition == null || activeComposition.layers.isEmpty()) {
                        Text("No layers", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        activeComposition.layers.forEach { layer ->
                            TextButton(
                                onClick = { dispatch("layer.select", """{"layers":[${layer.id}]}""") },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (layer.selected) "●  ${layer.name}" else "○  ${layer.name}", modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }

            Text(status, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }

    if (showNewComposition) {
        NewCompositionDialog(
            onDismiss = { showNewComposition = false },
            onCreate = { name, width, height ->
                showNewComposition = false
                dispatch("comp.new", JSONObject().put("name", name).put("width", width).put("height", height).put("duration", 10).toString())
            },
        )
    }
    if (showCommandPalette) {
        CommandPalette(
            commands = commands,
            onDismiss = { showCommandPalette = false },
            onRun = { command, params ->
                showCommandPalette = false
                dispatch(command, params)
            },
        )
    }
}

@Composable
private fun NewCompositionDialog(onDismiss: () -> Unit, onCreate: (String, Int, Int) -> Unit) {
    var name by remember { mutableStateOf("Comp 1") }
    var width by remember { mutableStateOf("1920") }
    var height by remember { mutableStateOf("1080") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Composition") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(width, { width = it }, label = { Text("Width") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(height, { height = it }, label = { Text("Height") }, singleLine = true, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = width.toIntOrNull()
                val h = height.toIntOrNull()
                if (name.isNotBlank() && w != null && h != null && w > 0 && h > 0) onCreate(name, w, h)
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CommandPalette(commands: List<EngineCommand>, onDismiss: () -> Unit, onRun: (String, String) -> Unit) {
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<EngineCommand?>(null) }
    var params by remember { mutableStateOf("{}") }
    val filtered = remember(search, commands) {
        commands.filter { it.label.contains(search, ignoreCase = true) || it.id.contains(search, ignoreCase = true) }
            .take(120)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Engine Commands") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 520.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (selected == null) {
                    LazyColumn {
                        items(filtered, key = { it.id }) { command ->
                            TextButton(onClick = { selected = command }, enabled = command.enabled, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(command.label)
                                    Text("${command.menu} · ${command.id}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                } else {
                    Text("${selected!!.menu} · ${selected!!.id}", style = MaterialTheme.typography.labelMedium)
                    Text(selected!!.params, style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(params, { params = it }, label = { Text("Parameters (JSON)") }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { selected = null; params = "{}" }) { Text("Choose another command") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let { onRun(it.id, params) } }, enabled = selected != null) { Text("Run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun parseOverview(raw: String): EditorOverview = runCatching {
    val root = JSONObject(raw)
    val active = if (root.isNull("activeComposition")) null else root.getLong("activeComposition")
    val compositions = root.optJSONArray("compositions") ?: JSONArray()
    val rows = (0 until compositions.length()).mapNotNull { index ->
        val comp = compositions.optJSONObject(index) ?: return@mapNotNull null
        val layers = comp.optJSONArray("layers") ?: JSONArray()
        CompRow(
            id = comp.optLong("id"),
            name = comp.optString("name", "Composition"),
            width = comp.optInt("width"),
            height = comp.optInt("height"),
            duration = comp.optDouble("duration").toFloat(),
            currentTime = comp.optDouble("currentTime").toFloat(),
            layers = (0 until layers.length()).mapNotNull { layerIndex ->
                val layer = layers.optJSONObject(layerIndex) ?: return@mapNotNull null
                LayerRow(layer.optLong("id"), layer.optString("name", "Layer"), layer.optBoolean("selected"))
            },
        )
    }
    EditorOverview(active, rows)
}.getOrDefault(EditorOverview(null, emptyList()))

private fun parseCommands(raw: String): List<EngineCommand> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { index ->
        val value = array.optJSONObject(index) ?: return@mapNotNull null
        EngineCommand(
            id = value.optString("id"),
            label = value.optString("label"),
            menu = value.optJSONArray("menu")?.let { menu ->
                (0 until menu.length()).joinToString(" › ") { menu.optString(it) }
            }.orEmpty(),
            params = value.optString("params", "{}"),
            enabled = value.optBoolean("enabled"),
        )
    }
}.getOrDefault(emptyList())

private fun copyUri(context: Context, uri: Uri): Result<File> = runCatching {
    val directory = File(context.filesDir, "imports")
    check(directory.mkdirs() || directory.isDirectory) { "Could not create the import folder" }
    val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment.orEmpty()
    val extension = displayName.substringAfterLast('.', "").lowercase().filter(Char::isLetterOrDigit).take(12)
    val suffix = if (extension.isEmpty()) ".bin" else ".$extension"
    val destination = File(directory, "${UUID.randomUUID()}$suffix")
    val source = context.contentResolver.openInputStream(uri) ?: error("Could not read the selected file")
    try {
        source.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    } catch (error: Exception) {
        destination.delete()
        throw error
    }
    destination
}

private data class Quintuple<A, B, C, D, E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E)

private fun toPreviewBitmap(frame: PreviewFrame?): Bitmap? {
    frame ?: return null
    val width = frame.width.toInt()
    val height = frame.height.toInt()
    val rgba = frame.rgba
    val pixelCount = width.toLong() * height.toLong()
    if (width <= 0 || height <= 0 || pixelCount > 512L * 512L || pixelCount * 4L != rgba.size.toLong()) return null
    val pixels = IntArray(pixelCount.toInt())
    for (index in pixels.indices) {
        val offset = index * 4
        val red = rgba[offset].toInt() and 0xff
        val green = rgba[offset + 1].toInt() and 0xff
        val blue = rgba[offset + 2].toInt() and 0xff
        val alpha = rgba[offset + 3].toInt() and 0xff
        pixels[index] = android.graphics.Color.argb(alpha, red, green, blue)
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}