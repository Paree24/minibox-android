package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.pastel.minibox.state.App
import com.pastel.minibox.state.Sound

/** Sound (mockups 07–08): the preset bar, part / operator chips, one section per tab (its visual, choice and up to
    six knobs), the pads in a drawer. */
@Composable
fun SoundTab(openPresets: () -> Unit) {
    val track = App.focusTrack.intValue
    val fast by App.fast
    val rack by App.rack
    val ed by Sound.editor
    val selection = Sound.selection.intValue
    var tab = Sound.tab.intValue
    var drawer by remember { mutableStateOf(false) }
    val color = C.track(track)

    LaunchedEffect(track, fast.focusPattern, rack[track].type, selection) { Sound.refresh() }
    // a sample still loading (a project just opened): read the editor again until its waveform is there
    var waits by remember(track, selection) { mutableStateOf(0) }
    LaunchedEffect(ed) {
        val loading = ed.sections.any { sec -> sec.visual.extra?.let { it.optString("name").isNotEmpty() && (it.optJSONArray("peaks")?.length() ?: 0) == 0 } == true }
        if (loading && waits < 20) { kotlinx.coroutines.delay(300); waits++; Sound.refresh() }
    }
    if (rack[track].empty) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("+", color = C.faint, fontSize = 40.sp) }
        return
    }
    // the Wavetable's macro band is a strip above the tabs, not a tab
    val band = ed.sections.firstOrNull { it.band }
    val sections = ed.sections.filter { !it.band }
    if (tab >= sections.size && tab != FX_TAB) tab = 0

    Column(Modifier.fillMaxSize()) {
        // preset: ‹ name › and the browser
        var loadSample: ((Boolean) -> Unit)? = null
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(40.dp).clip(CircleShape).background(C.control), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clickable { Sound.step(-1) }, contentAlignment = Alignment.Center) { Text("‹", color = C.quiet, fontSize = 20.sp) }
                Text(ed.preset, color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clickable(onClick = openPresets), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Box(Modifier.size(40.dp).clickable { Sound.step(1) }, contentAlignment = Alignment.Center) { Text("›", color = C.quiet, fontSize = 20.sp) }
            }
            val type = rack[track].type
            if (type == "sampler" || type == "drumsampler") {
                val context = androidx.compose.ui.platform.LocalContext.current
                var slice by remember { mutableStateOf(false) }
                val pick = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        // samples live in app storage, so a project keeps working after the original file moves
                        val tmp = copyIn(context, uri, "sample.wav")
                        val dst = java.io.File(context.filesDir, "Samples/${tmp.name}").apply { parentFile?.mkdirs() }
                        tmp.copyTo(dst, overwrite = true)
                        App.call("loadSample", "track" to track, "path" to dst.absolutePath, "pad" to selection, "slice" to slice)
                        Sound.refresh(); App.refresh()   // the pads are named after their samples
                    }
                }
                loadSample = { sl -> slice = sl; pick.launch(arrayOf("audio/*")) }
            }
            if (type == "wavetable") {
                // Randomize: a tap, in the scope chosen with a long press (All, Oscillators, Filter & Env …)
                Spacer(Modifier.width(8.dp))
                var scope by remember { mutableStateOf(0) }
                var scopeMenu by remember { mutableStateOf(false) }
                Box {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(C.control)
                        .pointerInput(Unit) { detectTapGestures(onTap = { Sound.randomize(scope) }, onLongPress = { scopeMenu = true }) },
                        contentAlignment = Alignment.Center) { Dice(color) }
                    androidx.compose.material3.DropdownMenu(scopeMenu, { scopeMenu = false }, containerColor = C.surface) {
                        RANDOM_SCOPES.forEachIndexed { i, n ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(n, color = if (i == scope) color else C.text, fontWeight = FontWeight.Bold) },
                                onClick = { scope = i; scopeMenu = false; Sound.randomize(i) })
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(40.dp).clip(CircleShape).background(C.control).clickable(onClick = openPresets), contentAlignment = Alignment.Center) {
                Text("⋯", color = C.quiet, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }
        }
        // samplers: loading a sample is the first thing to do, so it gets its own full-width buttons
        loadSample?.let { load ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val drums = rack[track].type == "drumsampler"
                Pill(if (drums) "Load sample on ${ed.selectionNames.getOrElse(selection) { "pad" }}" else "Load sample",
                    Modifier.weight(1f), on = true, color = color, height = 44.dp) { load(false) }
                if (drums) Pill("Slice a loop", Modifier.weight(1f), on = true, color = color, height = 44.dp) { load(true) }
            }
        }
        // the VA's two engines: DCO (crystal-locked, one oscillator a voice) or VCO (paraphonic, three a voice)
        if (ed.engine >= 0) {
            Segmented(listOf("DCO", "VCO"), ed.engine, color, {
                App.call("setParamId", "track" to track, "id" to "engine", "value" to it)
                Sound.tab.intValue = 0
                Sound.refresh(); App.refresh()
            }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
        // drum part / FM operator: which one the editor shows
        // (with the pads open on a drum device, the pads pick the part instead)
        val drumPads = drawer && rack[track].lanes.isNotEmpty()
        if (ed.selectionNames.isNotEmpty() && !drumPads) {
            val names = ed.selectionNames
            for (row in names.chunked(4)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { n ->
                        val i = names.indexOf(n)
                        Pill(n, Modifier.weight(1f), on = i == selection, color = color, height = 34.dp) { Sound.selection.intValue = i }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        // the macro band (Wobble, Movement, Dirt, Bite, Width): always in reach, above the tabs
        band?.let { b ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(C.surface)
                .padding(vertical = 8.dp)) {
                b.knobs.forEach { k ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Knob(k.value, color, k.label, k.text, { Sound.set(k, it) }, size = 36.dp, default = k.default)
                    }
                }
            }
        }
        // one section per tab
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sections.forEachIndexed { i, s ->
                Pill(s.title, on = i == tab, color = color, height = 34.dp) { Sound.tab.intValue = i }
            }
            Pill("Effects", on = tab == FX_TAB, color = color, height = 34.dp) { Sound.tab.intValue = FX_TAB }   // the 3 effect slots
        }
        if (tab == FX_TAB) {
            TrackFxPanel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                .background(C.surface).padding(12.dp))
        } else {
        val sec = sections.getOrNull(tab)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                .background(C.surface).padding(12.dp),
        ) {
            if (sec != null) {
                // a wavetable: its menu (the tables by family) above the drawing
                sec.menu?.let { m ->
                    TableMenu(m, color)
                    Spacer(Modifier.height(8.dp))
                }
                // the visual takes what the controls leave: nothing scrolls
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (sec.visual.kind == VK_MATRIX) MatrixPanel(sec.visual, color, Modifier.fillMaxSize())
                    else SectionVisual(sec.visual, color, Modifier.fillMaxSize().then(wavetableDrag(sec.visual)))
                }
                sec.choice?.let { c ->
                    Spacer(Modifier.height(12.dp))
                    if (c.options.size <= 5) Segmented(c.options, c.selected, color, { Sound.choose(c, it) }, Modifier.fillMaxWidth())
                    else Pill(c.options.getOrElse(c.selected) { "" }, Modifier.fillMaxWidth(), caret = true, height = 34.dp) {
                        val next = (c.selected + 1) % c.options.size
                        Sound.set(c.index, next / (c.options.size - 1f))
                    }
                }
                Spacer(Modifier.height(12.dp))
                // the section's switch (Reverse, Loop …) sits in the knob grid as one more cell: a full-width card
                // took the room the visual (a waveform) needs
                val cells = sec.knobs + listOfNotNull(sec.toggle)
                val cols = if (cells.size > 6) 4 else 3   // a seven-knob card (Osc B) in two rows, the drawing keeps its room
                for (row in cells.chunked(cols)) {
                    // bottoms aligned: the switch's name sits on the knobs' names
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Bottom) {
                        row.forEach { cell ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (cell === sec.toggle) {
                                    val t = cell
                                    val on = t.value > 0.5f
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Pill(if (on) "On" else "Off", on = on, color = color, height = 34.dp) { Sound.set(t.index, if (on) 0f else 1f) }
                                        Spacer(Modifier.height(6.dp))
                                        Text(t.label, color = C.quiet, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text(" ", fontSize = 10.sp, maxLines = 1)   // the knobs' value line
                                    }
                                } else {
                                    val k = cell
                                    // long-press: clear what was recorded on this parameter
                                    var menu by remember { mutableStateOf(false) }
                                    val context = androidx.compose.ui.platform.LocalContext.current
                                    val tr = App.focusTrack.intValue
                                    Box {
                                        Knob(k.value, color, k.label, k.text, { Sound.set(k, it) }, size = if (cols == 4) 40.dp else 46.dp, default = k.default, bipolar = k.bipolar,
                                            onLongPress = {
                                                if (hasAutomation(tr, "param", k.index)) menu = true
                                                else android.widget.Toast.makeText(context, "No automation on ${k.label}", android.widget.Toast.LENGTH_SHORT).show()
                                            })
                                        AutomationMenu(menu, k.label, { menu = false }) { clearAutomation(tr, "param", k.index) }
                                    }
                                }
                            }
                        }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        }
        Spacer(Modifier.height(6.dp))
        if (drawer) {
            Box(Modifier.fillMaxWidth().clickable { drawer = false }.padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(40.dp, 4.dp).clip(CircleShape).background(C.faint))
            }
            // two rows (the drums' hits, a synth's lowest notes): the editor above keeps its room
            Pads(rows = 2, aspect = 0.6f) { note, down ->
                // a drum part's pad selects it in the editor
                val lane = rack[track].lanes.indexOfFirst { it.note == note }
                if (down && lane >= 0 && lane < ed.selectionNames.size) Sound.selection.intValue = lane
            }
        } else PadsHandle(false) { drawer = true }
        Spacer(Modifier.height(12.dp))
    }
}

/** The Sound tab index of the track's effect slots (after the device's own sections). */
const val FX_TAB = 99

private val RANDOM_SCOPES = listOf("All", "Oscillators", "Filter & Env", "Modulation", "Dirt & Width")

/** A die (the Randomize button). */
@Composable
private fun Dice(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val s = size.width
        drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
        for ((dx, dy) in listOf(0.3f to 0.3f, 0.7f to 0.7f, 0.5f to 0.5f, 0.7f to 0.3f, 0.3f to 0.7f))
            drawCircle(color, 1.6.dp.toPx(), androidx.compose.ui.geometry.Offset(s * dx, s * dy))
    }
}

/** A wavetable's menu pill: the table's name; the menu lists the tables by family. */
@Composable
private fun TableMenu(m: com.pastel.minibox.state.Choice, color: androidx.compose.ui.graphics.Color) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill(m.options.getOrElse(m.selected) { "" }, caret = true, height = 32.dp, color = color) { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = C.surface) {
            m.options.forEachIndexed { i, n ->
                val fam = m.families.getOrElse(i) { "" }
                if (i == 0 || fam != m.families.getOrElse(i - 1) { "" })
                    Text(fam.uppercase(), color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp))
                androidx.compose.material3.DropdownMenuItem(text = { Text(n, color = if (i == m.selected) color else C.text, fontWeight = FontWeight.Bold) },
                    onClick = { open = false; Sound.set(m.index, i / (m.options.size - 1f)) })
            }
        }
    }
}

/** Dragging across a wavetable's drawing scans it (its Position). */
@Composable
private fun wavetableDrag(v: com.pastel.minibox.state.Visual): Modifier {
    val idx = v.extra?.optInt("posIndex", -1) ?: -1
    if (v.kind != VK_WAVETABLE || idx < 0) return Modifier
    return Modifier.pointerInput(idx) {
        detectHorizontalDragGestures { change, _ ->
            Sound.set(idx, ((change.position.x - 14.dp.toPx()) / (size.width - 28.dp.toPx())).coerceIn(0f, 1f))
        }
    }
}

/** The preset browser: families, the list (tap loads), and saving the current sound as a user preset. */
@Composable
fun PresetSheet(done: () -> Unit) {
    val ed by Sound.editor
    val all = remember { Sound.presets() }
    val families = remember(all) { listOf("All") + all.map { if (it.factory) it.family else "User" }.distinct() }
    var family by remember { mutableStateOf("All") }
    var name by remember { mutableStateOf(ed.preset) }
    Column {
        SheetTitle("Presets", ed.preset)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            families.forEach { f -> Pill(f, on = f == family) { family = f } }
        }
        Spacer(Modifier.height(10.dp))
        val shown = all.withIndex().filter { (_, p) -> family == "All" || (if (p.factory) p.family else "User") == family }
        Paged(shown, columns = 2, rows = 6, startAt = shown.indexOfFirst { it.value.name == ed.preset }, key = family) { _, (i, p) ->
            val on = p.name == ed.preset
            Box(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp)).background(if (on) C.text else C.control)
                .clickable { Sound.applyPreset(i) }.padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
                Text(p.name, color = if (on) C.bg else C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.text.BasicTextField(
                name, { name = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = C.text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(C.text),
                modifier = Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(C.control).padding(12.dp),
            )
            Spacer(Modifier.width(8.dp))
            Pill("Save", on = true, height = 44.dp) { if (Sound.save(name)) { Sound.refresh(); done() } }
        }
    }
}
