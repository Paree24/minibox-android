package com.pastel.minibox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/** The Minibox knob (mockups/SPEC.md): a 270° arc from 135°, a value arc in the accent, a text-colour pointer.
    Drag up/down anywhere on it; a double-tap goes back to `default` when one is given. */
@Composable
fun Knob(value: Float, color: Color, label: String, text: String, onChange: (Float) -> Unit,
         modifier: Modifier = Modifier, size: Dp = 46.dp, default: Float? = null, bipolar: Boolean = false,
         onEnd: (() -> Unit)? = null) {
    var drag by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    val current by rememberUpdatedState(value)
    val shown = if (dragging) drag else value
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            Modifier.size(size + 12.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = current; dragging = true },
                        onDragEnd = { dragging = false; end?.invoke() },
                        onDragCancel = { dragging = false; end?.invoke() },
                    ) { ch, dy ->
                        ch.consume()
                        drag = (drag - dy / (size.toPx() * 3.2f)).coerceIn(0f, 1f)
                        change(drag)
                    }
                }
                .pointerInput(default) { if (default != null) detectTapGestures(onDoubleTap = { change(default) }) },
        ) {
            val r = this.size.minDimension / 2f - 4.dp.toPx()
            val c = center
            val arc = Size(r * 2, r * 2)
            val tl = Offset(c.x - r, c.y - r)
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            drawArc(C.divider, 135f, 270f, false, tl, arc, style = stroke)
            if (bipolar) {
                val mid = 270f
                val a = 135f + 270f * shown
                drawArc(color, minOf(mid, a), kotlin.math.abs(a - mid), false, tl, arc, style = stroke)
            } else if (shown > 0.005f) drawArc(color, 135f, 270f * shown, false, tl, arc, style = stroke)
            val body = r - 5.dp.toPx()
            drawCircle(C.control, body, c)
            drawCircle(Color(0xFF2E323A), body, c, style = Stroke(1.dp.toPx()))
            val ang = Math.toRadians((135.0 + 270.0 * shown))
            val p0 = Offset(c.x + (body * 0.3f * cos(ang)).toFloat(), c.y + (body * 0.3f * sin(ang)).toFloat())
            val p1 = Offset(c.x + (body * 0.78f * cos(ang)).toFloat(), c.y + (body * 0.78f * sin(ang)).toFloat())
            drawLine(C.text, p0, p1, 2.4.dp.toPx(), StrokeCap.Round)
        }
        Text(label, color = C.quiet, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text, color = C.faint, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A whole-width drag bar: the fill is the value (velocity, gate, swing, volume …). */
@Composable
fun BarSlider(value: Float, color: Color, name: String, text: String, onChange: (Float) -> Unit,
              modifier: Modifier = Modifier, height: Dp = 46.dp, onEnd: (() -> Unit)? = null) {
    val change by rememberUpdatedState(onChange)
    val end by rememberUpdatedState(onEnd)
    var drag by remember { mutableFloatStateOf(-1f) }   // the finger's value while it moves (the model catches up)
    val shown = if (drag >= 0f) drag else value
    Box(
        modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(12.dp)).background(C.control)
            .pointerInput(Unit) {
                detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)); end?.invoke() }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { drag = -1f; end?.invoke() },
                    onDragCancel = { drag = -1f; end?.invoke() },
                ) { ch, _ ->
                    ch.consume()
                    drag = (ch.position.x / size.width).coerceIn(0f, 1f)
                    change(drag)
                }
            },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceIn(0f, 1f)).background(color.copy(alpha = 0.35f)))
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = C.text, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
            Text(text, color = C.quiet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A choice pill (mockups: `segmented`): the selected option is filled with the accent. */
@Composable
fun Segmented(options: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit,
              modifier: Modifier = Modifier, height: Dp = 34.dp) {
    Row(modifier.height(height).clip(CircleShape).background(C.control).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if (on) color else Color.Transparent)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(o, color = if (on) C.bg else C.quiet, fontSize = 12.sp,
                    fontWeight = if (on) FontWeight.Black else FontWeight.Bold, maxLines = 1)
            }
        }
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, on: Boolean = false, color: Color = C.text, caret: Boolean = false,
         height: Dp = 30.dp, padding: Dp = 12.dp, onClick: () -> Unit = {}) {
    Box(
        modifier.height(height).clip(CircleShape).background(if (on) color else C.control).clickable(onClick = onClick)
            .padding(horizontal = padding),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (caret) "$text  ▾" else text, color = if (on) C.bg else C.text, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** A row of equal choice pads (chance, ratchet, time signature, quantize …). */
@Composable
fun ChoicePads(options: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit, height: Dp = 44.dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(12.dp)).background(if (on) color else C.control)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) C.bg else C.quiet, fontSize = 14.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center) }
        }
    }
}

/** A pad that does something while it is held (FX pads): down → true, up → false. Each pad tracks its own finger. */
@Composable
fun HoldPad(name: String, sub: String, held: Boolean, color: Color, onHold: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val hold by rememberUpdatedState(onHold)
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(if (held) color else C.control)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    hold(true)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                    hold(false)
                }
            },
    ) {
        Text(name, color = if (held) C.bg else C.text, fontSize = 13.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.padding(12.dp))
        Text(sub, color = if (held) C.bg else C.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = C.faint, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp, modifier = modifier)
}

@Composable
fun Toggle(on: Boolean, color: Color = C.text, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(42.dp, 24.dp).clip(CircleShape).background(if (on) color else C.control).clickable { onChange(!on) }
            .border(0.dp, Color.Transparent, CircleShape),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.padding(3.dp).size(18.dp).clip(CircleShape).background(if (on) C.bg else C.quiet)) }
}
