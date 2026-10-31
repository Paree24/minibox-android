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
    if (tab >= ed.sections.size && tab != FX_TAB) tab = 0

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
        // one section per tab
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ed.sections.forEachIndexed { i, s ->
                Pill(s.title, on = i == tab, color = color, height = 34.dp) { Sound.tab.intValue = i }
            }
            Pill("Effects", on = tab == FX_TAB, color = color, height = 34.dp) { Sound.tab.intValue = FX_TAB }   // the 3 effect slots
        }
        if (tab == FX_TAB) {
            TrackFxPanel(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                .background(C.surface).padding(12.dp))
        } else {
        val sec = ed.sections.getOrNull(tab)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                .background(C.surface).padding(12.dp),
        ) {
            if (sec != null) {
                // the visual takes what the controls leave: nothing scrolls
                Box(Modifier.weight(1f).fillMaxWidth()) { SectionVisual(sec.visual, color, Modifier.fillMaxSize()) }
                sec.choice?.let { c ->
                    Spacer(Modifier.height(12.dp))
                    if (c.options.size <= 5) Segmented(c.options, c.selected, color, { Sound.set(c.index, if (c.options.size > 1) it / (c.options.size - 1f) else 0f) },
                        Modifier.fillMaxWidth())
                    else Pill(c.options.getOrElse(c.selected) { "" }, Modifier.fillMaxWidth(), caret = true, height = 34.dp) {
                        val next = (c.selected + 1) % c.options.size
                        Sound.set(c.index, next / (c.options.size - 1f))
                    }
                }
                sec.toggle?.let { t ->
                    Spacer(Modifier.height(10.dp))
                    ToggleCard(t.label, t.value > 0.5f, Modifier.fillMaxWidth()) { Sound.set(t.index, if (it) 1f else 0f) }
                }
                Spacer(Modifier.height(12.dp))
                for (row in sec.knobs.chunked(3)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        row.forEach { k ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Knob(k.value, color, k.label, k.text, { Sound.set(k.index, it) }, size = 46.dp, default = k.default, bipolar = k.bipolar)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
