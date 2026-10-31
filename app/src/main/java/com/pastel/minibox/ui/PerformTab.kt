package com.pastel.minibox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pastel.minibox.engine.Native
import com.pastel.minibox.state.App

/** Perform (mockup 10): A–H pattern pads, switch quantize, the 8 hold-to-play effects, master volume. */
@Composable
fun PerformTab(onPatternMenu: (Int) -> Unit) {
    val fast by App.fast
    val patterns by App.patterns
    val project by App.project
    var xyLeft by remember { mutableIntStateOf(5) }    // Filter
    var xyRight by remember { mutableIntStateOf(0) }   // Stutter
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Patterns", Modifier.weight(1f))
            Pill(listOf("Bar", "Pattern end", "Immediate")[project.switchQuantize], caret = true) {
                App.cmd("set", "key" to "switchQuantize", "value" to (project.switchQuantize + 1) % 3)
            }
        }
        Spacer(Modifier.height(10.dp))
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until 4) {
                    val i = row * 4 + c
                    PatternPad(i, patterns[i].any, patterns[i].bars, patterns[i].used,
                        current = fast.activePattern == i, queued = fast.queuedPattern == i,
                        progress = if (fast.activePattern == i) fast.progress else 0f,
                        modifier = Modifier.weight(1f).aspectRatio(0.95f),
                        onTap = { App.cmd("queuePattern", "pattern" to i) }, onLong = { onPatternMenu(i) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(10.dp))
        SectionLabel("FX")
        Spacer(Modifier.height(10.dp))
        // two XY pads, left and right thumbs: each plays the effect picked above it (X and Y are its two
        // main controls; touch engages it, lifting lets it go)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            XyPad(xyLeft, { xyLeft = it }, Modifier.weight(1f).fillMaxSize())
            XyPad(xyRight, { xyRight = it }, Modifier.weight(1f).fillMaxSize())
        }
        Spacer(Modifier.height(10.dp))
        BarSlider(project.master.volume, C.pattern, "Volume", "${(project.master.volume * 100).toInt()}%",
            { App.cmd("set", "key" to "volume", "value" to it) })
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PatternPad(i: Int, any: Boolean, bars: Int, used: List<Boolean>, current: Boolean, queued: Boolean, progress: Float,
                       modifier: Modifier, onTap: () -> Unit, onLong: () -> Unit) {
    val letter = "ABCDEFGH"[i].toString()
    val shape = RoundedCornerShape(14.dp)
    var m = modifier.clip(shape)
    m = when {
        current -> m.background(C.pattern.copy(alpha = 0.22f)).border(2.dp, C.pattern, shape)
        queued -> m.background(C.control).border(2.dp, C.pattern.copy(alpha = 0.7f), shape)
        !any -> m.border(1.5.dp, C.faint, shape)
        else -> m.background(C.control)
    }
    Box(m.combinedClickable(onClick = onTap, onLongClick = onLong).padding(12.dp)) {
        Text(letter, color = if (current || queued) C.pattern else if (any) C.text else C.faint, fontSize = 26.sp, fontWeight = FontWeight.Black)
        if (current) {
            Canvas(Modifier.size(18.dp).align(Alignment.TopEnd)) {
                drawArc(C.pattern, -90f, 360f * progress, false, style = Stroke(2.5.dp.toPx()))
            }
        }
        if (any) {
            Column(Modifier.align(Alignment.BottomStart)) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    used.forEachIndexed { t, u -> Box(Modifier.size(5.dp).clip(CircleShape).background(if (u) C.track(t) else C.faint)) }
                }
                Spacer(Modifier.height(4.dp))
                Text(if (bars == 1) "1 bar" else "$bars bars", color = C.faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private data class XyEffect(val name: String, val xName: String, val yName: String)

/** An XY pad: the dropdown picks one of the 8 performance effects; a finger on the pad engages it and moves its two
    main controls (X across, Y up). The dot stays where the finger left it. */
@Composable
private fun XyPad(pad: Int, onPick: (Int) -> Unit, modifier: Modifier) {
    val effects = remember {
        org.json.JSONArray(App.call("performXY")).let { a ->
            List(a.length()) { a.getJSONObject(it).let { o -> XyEffect(o.getString("name"), o.getString("xName"), o.getString("yName")) } }
        }
    }
    var menu by remember { mutableStateOf(false) }
    var down by remember { mutableStateOf(false) }
    // one state for the pad's life: the touch handler (started once) writes the same state the drawing reads; a
    // state keyed on the effect left the handler writing a stale one after a change (the dot stopped moving)
    var pos by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    LaunchedEffect(pad) { pos = Offset(0.5f, 0.5f) }
    val current by rememberUpdatedState(pad)
    val fx = effects.getOrNull(pad)
    Column(modifier) {
        Box {
            Pill(fx?.name ?: "", Modifier.fillMaxWidth(), caret = true, height = 36.dp) { menu = true }
            DropdownMenu(menu, { menu = false }, containerColor = C.surface) {
                effects.forEachIndexed { i, e ->
                    DropdownMenuItem(text = { Text(e.name, color = if (i == pad) C.pattern else C.text, fontWeight = FontWeight.Bold) },
                        onClick = { menu = false; onPick(i) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Canvas(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (down) C.pattern.copy(alpha = 0.25f) else C.control)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val d = awaitFirstDown()
                        d.consume()
                        fun set(o: Offset) {
                            pos = Offset((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
                            App.call("setPerformXY", "pad" to current, "x" to pos.x, "y" to 1f - pos.y)
                        }
                        set(d.position)
                        val p = current
                        Native.fxHold(p, true)
                        down = true
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == d.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            set(c.position)
                        }
                        Native.fxHold(p, false)
                        down = false
                    }
                },
        ) {
            val stroke = 1.dp.toPx()
            for (k in 1..3) {
                drawLine(C.divider, Offset(size.width * k / 4, 0f), Offset(size.width * k / 4, size.height), stroke)
                drawLine(C.divider, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), stroke)
            }
            val c = Offset(pos.x * size.width, pos.y * size.height)
            drawLine(C.pattern.copy(alpha = 0.5f), Offset(c.x, 0f), Offset(c.x, size.height), stroke)
            drawLine(C.pattern.copy(alpha = 0.5f), Offset(0f, c.y), Offset(size.width, c.y), stroke)
            drawCircle(if (down) C.pattern else C.quiet, if (down) 14.dp.toPx() else 9.dp.toPx(), c)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = Tokens.FAINT; textSize = 11.dp.toPx(); typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            fx?.let {
                drawContext.canvas.nativeCanvas.drawText("${it.xName} →", 10.dp.toPx(), size.height - 10.dp.toPx(), paint)
                drawContext.canvas.nativeCanvas.drawText("↑ ${it.yName}", 10.dp.toPx(), 18.dp.toPx(), paint)
            }
        }
    }
}
