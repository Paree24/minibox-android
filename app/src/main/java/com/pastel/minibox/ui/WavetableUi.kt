package com.pastel.minibox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.state.Sound
import com.pastel.minibox.state.Visual
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.roundToInt

/** The Wavetable's modulation matrix (mockup png/wavetable_matrix.png): a row per route in use, source → destination
    and its amount (drag the bar; a long press clears the row), then "+ Add" while a slot is free. */
@Composable
fun MatrixPanel(v: Visual, color: Color, modifier: Modifier = Modifier) {
    val e = v.extra ?: return
    val slots = e.optJSONArray("slots") ?: return
    val sources = e.getJSONArray("sources").strings()
    val dests = e.getJSONArray("dests").strings()
    val base = e.getInt("base")
    fun param(slot: Int, field: Int) = base + slot * 3 + field
    fun setChoice(slot: Int, field: Int, i: Int, n: Int) = Sound.set(param(slot, field), i / (n - 1f))
    val used = (0 until slots.length()).filter { s -> slots.getJSONObject(s).let { it.getInt("src") > 0 || it.getInt("dst") > 0 } }
    val free = (0 until slots.length()).firstOrNull { it !in used }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        used.forEach { s ->
            val o = slots.getJSONObject(s)
            MatrixRow(sources, dests, o.getInt("src"), o.getInt("dst"), o.getDouble("amt").toFloat(), color,
                onSrc = { setChoice(s, 0, it, sources.size) }, onDst = { setChoice(s, 1, it, dests.size) },
                onAmt = { Sound.set(param(s, 2), (it + 1f) / 2f) },
                onClear = { for (f in 0 until 3) Sound.set(param(s, f), if (f == 2) 0.5f else 0f) })
        }
        if (free != null) {
            // + Add: the source, then the destination, at half the amount
            var step by remember { mutableStateOf(0) }   // 0 closed, 1 choosing the source, 2 the destination
            Box {
                Box(Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).border(1.2.dp, C.faint, CircleShape).clickable { step = 1 },
                    contentAlignment = Alignment.Center) { Text("+ Add", color = C.faint, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                DropdownMenu(step == 1, { step = 0 }, containerColor = C.surface) {
                    sources.drop(1).forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n, color = C.text, fontWeight = FontWeight.Bold) },
                        onClick = { setChoice(free, 0, i + 1, sources.size); step = 2 }) }
                }
                DropdownMenu(step == 2, { step = 0 }, containerColor = C.surface) {
                    dests.drop(1).forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n, color = C.text, fontWeight = FontWeight.Bold) },
                        onClick = { setChoice(free, 1, i + 1, dests.size); Sound.set(param(free, 2), 0.75f); step = 0 }) }
                }
            }
        }
    }
}

@Composable
private fun MatrixRow(sources: List<String>, dests: List<String>, src: Int, dst: Int, amt: Float, color: Color,
                      onSrc: (Int) -> Unit, onDst: (Int) -> Unit, onAmt: (Float) -> Unit, onClear: () -> Unit) {
    var srcMenu by remember { mutableStateOf(false) }
    var dstMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.control)
        .pointerInput(Unit) { detectTapGestures(onLongPress = { onClear() }) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                Text(sources.getOrElse(src) { "" }, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.clickable { srcMenu = true })
                DropdownMenu(srcMenu, { srcMenu = false }, containerColor = C.surface) {
                    sources.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n, color = if (i == src) color else C.text, fontWeight = FontWeight.Bold) },
                        onClick = { srcMenu = false; onSrc(i) }) }
                }
            }
            Text("  →  ", color = C.faint, fontSize = 12.sp, fontWeight = FontWeight.Black)
            Box(Modifier.weight(1f)) {
                Text(dests.getOrElse(dst) { "" }, color = color, fontSize = 12.sp, fontWeight = FontWeight.Black, modifier = Modifier.clickable { dstMenu = true })
                DropdownMenu(dstMenu, { dstMenu = false }, containerColor = C.surface) {
                    dests.forEachIndexed { i, n -> DropdownMenuItem(text = { Text(n, color = if (i == dst) color else C.text, fontWeight = FontWeight.Bold) },
                        onClick = { dstMenu = false; onDst(i) }) }
                }
            }
            Text((if (amt >= 0) "+" else "−") + "${(abs(amt) * 100).roundToInt()}%", color = C.quiet, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(6.dp))
        // the amount: a bipolar bar from the middle; drag along it
        var w by remember { mutableStateOf(1) }
        val density = LocalDensity.current
        Box(Modifier.fillMaxWidth().height(18.dp).onSizeChanged { w = it.width }
            .pointerInput(Unit) {
                detectTapGestures { onAmt(((it.x / w) * 2f - 1f).coerceIn(-1f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onAmt(((change.position.x / w) * 2f - 1f).coerceIn(-1f, 1f)) }
            }, contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(C.divider))
            val half = with(density) { (w / 2f).toDp() }
            val len = half * abs(amt)
            Box(Modifier.offset(x = if (amt >= 0) half else half - len).width(len).height(4.dp).clip(CircleShape).background(color))
            Box(Modifier.offset(x = half - 1.dp).width(2.dp).fillMaxHeight().background(C.faint))
        }
    }
}

private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
