package com.pastel.minibox.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pastel.minibox.engine.AudioStats
import com.pastel.minibox.engine.Midi
import com.pastel.minibox.engine.Native
import com.pastel.minibox.engine.Prefs
import com.pastel.minibox.state.App
import com.pastel.minibox.state.objects
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** ⋯ → Project (mockup 14): the project's file actions, the projects on the phone, latency, MIDI. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProjectSheet(done: () -> Unit) {
    val context = LocalContext.current
    val project by App.project
    var name by remember { mutableStateOf(project.name) }
    var projects by remember { mutableStateOf(listProjects()) }
    var exporting by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val f = copyIn(context, uri, "import.minibox")
            val r = JSONObject(App.call("openFile", "path" to f.absolutePath))
            toast(context, if (r.optBoolean("ok")) "Opened" else r.optString("error", "Could not open"))
            App.refresh(); done()
        }
    }
    Column {
        BasicTextField(name, { name = it }, singleLine = true,
            textStyle = TextStyle(color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Black), cursorBrush = SolidColor(C.text),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionPad("New", Modifier.weight(1f)) {
                App.autosave(context)
                App.call("newProject"); App.refresh(); Prefs.setLastProject(context, null); done()
            }
            ActionPad("Save", Modifier.weight(1f)) {
                val r = JSONObject(App.call("saveProject", "name" to name.trim()))
                if (r.optBoolean("ok")) Prefs.setLastProject(context, name.trim())
                toast(context, if (r.optBoolean("ok")) "Saved" else r.optString("error"))
                App.refresh(); projects = listProjects()
            }
            ActionPad("Export", Modifier.weight(1f)) { showExport = !showExport }
            ActionPad("Open file", Modifier.weight(1f)) { import.launch(arrayOf("*/*")) }
        }
        if (showExport) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("WAV" to "wav", "MIDI" to "mid", "DAWproject" to "dawproject", "Project" to "minibox").forEach { (label, kind) ->
                    Pill(if (exporting) "…" else label, Modifier.weight(1f), height = 40.dp) {
                        if (!exporting) { exporting = true; exportAndShare(context, kind, name.trim().ifEmpty { "Minibox" }) { exporting = false } }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionLabel("Projects")
        Spacer(Modifier.height(6.dp))
        Paged(projects, columns = 2, rows = 2, key = projects.size) { _, p ->
            Box(
                Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (p == project.name) C.text else C.control)
                    .combinedClickable(
                        onClick = {
                            App.autosave(context)
                            val r = JSONObject(App.call("openProject", "name" to p))
                            if (r.optBoolean("ok")) Prefs.setLastProject(context, p)
                            App.refresh(); done()
                        },
                        onLongClick = { App.call("deleteProject", "name" to p); projects = listProjects() },
                    ).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) { Text(p, color = if (p == project.name) C.bg else C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
        Spacer(Modifier.height(14.dp))
        LatencyCard()
        Spacer(Modifier.height(10.dp))
        QualityRow()
        Spacer(Modifier.height(10.dp))
        val midi by Midi.devices
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.control).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("MIDI", Modifier.width(56.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (midi.isEmpty()) C.faint else C.track(4)))
            Spacer(Modifier.width(10.dp))
            Text(midi.joinToString().ifEmpty { "No controller" }, color = if (midi.isEmpty()) C.faint else C.text,
                fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1)
        }
    }
}

private val LATENCY = listOf("Low", "Medium", "High", "Very high")
private val LATENCY_HINT = listOf("Minimum", "≤ 30 ms", "≤ 50 ms", "≤ 80 ms")

/** Latency: Low is the least the phone sustains; the others trade delay for headroom (older phones, big projects). */
@Composable
fun LatencyCard() {
    val context = LocalContext.current
    var mode by remember { mutableIntStateOf(Prefs.latencyMode(context)) }
    var s by remember { mutableStateOf(AudioStats()) }
    LaunchedEffect(Unit) {
        val buf = FloatArray(11)
        while (true) { Native.readStats(buf); s = AudioStats.from(buf); delay(250) }
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(C.control).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Latency", Modifier.weight(1f))
            val low = s.mmap && s.exclusive
            Text(if (!s.running) "Stopped" else if (low) "Fast path" else "Standard path",
                color = if (low) C.track(4) else C.quiet, fontSize = 11.sp, fontWeight = FontWeight.Black)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (s.latencyMs >= 0) "%.1f".format(s.latencyMs) else "–", color = C.text, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(6.dp))
            Text("ms", color = C.quiet, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
        }
        Text("${if (s.mmap) "MMAP" else if (s.exclusive) "exclusive" else "shared"} · ${s.sampleRate / 1000f} kHz · ${s.bufferFrames} frames · xruns ${s.xruns} · cpu ${(s.cpu * 100).toInt()}%",
            color = C.quiet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (com.pastel.minibox.engine.Device.bluetooth.value)
            Text("Bluetooth output adds 150–250 ms the app cannot remove: use wired or USB audio to play live.",
                color = C.record, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LATENCY.forEachIndexed { i, label ->
                val on = i == mode
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (on) C.text else C.surface)
                        .clickable { mode = i; Prefs.setLatencyMode(context, i); Native.setLatencyMode(i, Prefs.lowBursts(context)) }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(label, color = if (on) C.bg else C.text, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Text(LATENCY_HINT[i], color = if (on) C.bg else C.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** The master effects chain (Mix → Effects): 8 slots in series, from Pastel EFX Grid. */
@Composable
fun MasterFxSheet() {
    var state by remember { mutableStateOf(JSONObject(App.call("masterFx"))) }
    var picking by remember { mutableIntStateOf(-1) }
    val choices = remember { state.getJSONArray("choices").let { a -> List(a.length()) { a.getString(it) } } }
    fun reload() { state = JSONObject(App.call("masterFx")) }
    Column {
        SheetTitle("Master effects")
        val slots = state.getJSONArray("slots").objects()
        val used = slots.indexOfFirst { it.getString("effect").isEmpty() }.let { if (it < 0) 8 else it }
        for (i in 0 until minOf(8, used + 1)) {
            val s = slots[i]
            val fx = s.getString("effect")
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(fx.ifEmpty { "+ Add" }, Modifier.weight(1f), caret = fx.isNotEmpty(), height = 40.dp) { picking = if (picking == i) -1 else i }
                if (fx.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Pill(if (s.getBoolean("bypass")) "Off" else "On", on = !s.getBoolean("bypass"), color = C.pattern, height = 40.dp) {
                        App.call("setMasterFxValue", "index" to i, "key" to "bypass", "value" to !s.getBoolean("bypass")); reload()
                    }
                    Spacer(Modifier.width(6.dp))
                    Pill("✕", height = 40.dp) { App.call("removeMasterFx", "index" to i); reload() }
                }
            }
            if (picking == i) {
                Paged(choices, columns = 3, rows = 4, rowHeight = 40.dp, key = i) { _, c ->
                    Box(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(C.control)
                        .clickable { App.call("setMasterFx", "index" to i, "effect" to c); picking = -1; reload() }
                        .padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                        Text(c, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionPad(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.height(64.dp).clip(RoundedCornerShape(14.dp)).background(C.control).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(label, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Black) }
}

private fun listProjects(): List<String> = JSONArray(App.call("projects")).objects().map { it.getString("name") }

private fun toast(c: Context, text: String) = Toast.makeText(c, text, Toast.LENGTH_SHORT).show()

/** Copies a document the user picked into the app's cache (the engine reads plain files). */
fun copyIn(context: Context, uri: Uri, fallbackName: String): File {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (c.moveToFirst() && i >= 0) c.getString(i) else null
    } ?: fallbackName
    val f = File(context.cacheDir, "imports/$name").apply { parentFile?.mkdirs() }
    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
    return f
}

/** Renders or writes the export off the main thread, then hands it to the share sheet. */
private fun exportAndShare(context: Context, kind: String, name: String, finished: () -> Unit) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "$name.$kind")
    Thread {
        val r = JSONObject(App.call("export", "kind" to kind, "path" to file.absolutePath))
        Handler(Looper.getMainLooper()).post {
            finished()
            if (!r.optBoolean("ok") || !file.isFile) { toast(context, r.optString("error", "Export failed")); return@post }
            val uri = FileProvider.getUriForFile(context, "com.pastel.minibox.files", file)
            val mime = when (kind) { "wav" -> "audio/wav"; "mid" -> "audio/midi"; else -> "application/octet-stream" }
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share $name"))
        }
    }.start()
}

/** Quality: Auto (Eco on a low-end phone or while it runs hot), Eco (half the voices, one shared reverb, shorter
    pattern tails, a lighter screen), Full (the instruments as designed). */
@Composable
fun QualityRow() {
    val context = LocalContext.current
    var q by remember { mutableIntStateOf(Prefs.quality(context)) }
    val eco by com.pastel.minibox.engine.Device.eco
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.control).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(76.dp)) {
            SectionLabel("Quality")
            Text(if (eco) "Eco now" else "Full now", color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Segmented(listOf("Auto", "Eco", "Full"), q, C.text, {
            q = it; Prefs.setQuality(context, it); com.pastel.minibox.engine.Device.applyQuality(context)
        }, Modifier.weight(1f))
    }
}
