package com.pastel.minibox.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pastel.minibox.state.Visual
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** VisualKind (pastel-minibox Source/ui/VisualSpec.h), in order. */
private object VK {
    const val Filter = 1; const val Hpf = 2; const val Adsr = 3; const val Decay = 4; const val Rate4 = 5; const val MacroEnv = 6
    const val PitchEnv = 7; const val Wave = 8; const val Lfo = 9; const val Ring = 17; const val Delay = 19; const val Drive = 21
    const val Reverb = 25
}

private val DRAWN = setOf(VK.Filter, VK.Hpf, VK.Adsr, VK.Decay, VK.Rate4, VK.MacroEnv, VK.PitchEnv, VK.Wave, VK.Lfo,
    VK.Ring, VK.Delay, VK.Drive, VK.Reverb)

/** A section's visual: a drawing of its parameter values (never computed from audio), as the desktop's. Sections
    whose visual is not drawn on the phone show just their knobs. */
@Composable
fun SectionVisual(v: Visual, color: Color, height: Dp = 150.dp) {
    if (v.kind !in DRAWN) return
    Canvas(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(10.dp)).background(C.bg)) {
        val x = v.values
        fun at(i: Int, d: Float = 0f) = x.getOrElse(i) { d }
        val pad = 10.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad
        for (i in 1..3) drawLine(C.divider, Offset(pad + w * i / 4, pad), Offset(pad + w * i / 4, pad + h), 1f)
        when (v.kind) {
            VK.Filter -> filter(color, pad, w, h, at(0, 0.5f), if (v.variant == 3) 0f else at(1), lowpass = true)
            VK.Hpf -> filter(color, pad, w, h, at(0, 0.2f) * 0.6f, 0.1f, lowpass = false)
            VK.Adsr -> adsr(color, pad, w, h, at(0), at(1), at(2), at(3))
            VK.Decay, VK.MacroEnv -> adsr(color, pad, w, h, if (v.variant == 1) 0.02f else at(0) * 0.5f, at(1).coerceAtLeast(0.05f), 0f, 0f)
            VK.Rate4 -> adsr(color, pad, w, h, at(0), at(1), at(2, 0.6f), at(3))
            VK.PitchEnv -> curve(color, pad, w, h) { t -> exp(-t * (2f + 12f * (1f - at(1, 0.3f)))) * (0.3f + 0.7f * at(0, 0.6f)) }
            VK.Wave -> curve(color, pad, w, h, fill = false) { t ->
                val ph = (t * 2f) % 1f
                val saw = 2 * ph - 1
                val pulse = if (ph < 0.15f + 0.7f * at(1, 0.5f)) 1f else -1f
                0.5f + 0.4f * (saw * (1 - at(0, 0.4f)) + pulse * at(0, 0.4f))
            }
            VK.Lfo -> curve(color, pad, w, h, fill = false) { t ->
                val ph = (t * 2f) % 1f
                0.5f + 0.4f * when ((at(0) * 5).toInt()) {
                    0 -> sin(2 * PI.toFloat() * ph)
                    1 -> 1 - 4 * kotlin.math.abs(ph - 0.5f)
                    2 -> 2 * ph - 1
                    3 -> if (ph < 0.5f) 1f else -1f
                    else -> listOf(0.3f, -0.6f, 0.8f, -0.2f)[(ph * 4).toInt().coerceIn(0, 3)]
                }
            }
            VK.Ring -> curve(color, pad, w, h, fill = false) { t -> 0.5f + 0.42f * sin(2 * PI.toFloat() * t * (4 + 10 * at(0))) * exp(-t * (6 - 5 * at(1))) }
            VK.Delay -> for (k in 0 until 6) {
                val a = at(0, 0.5f).pow(k)
                val bx = pad + w * (0.08f + k * 0.16f)
                drawLine(color.copy(alpha = 0.3f + 0.7f * a), Offset(bx, pad + h), Offset(bx, pad + h * (1 - a * 0.9f)), 4.dp.toPx())
            }
            VK.Drive -> curve(color, pad, w, h, fill = false) { t ->
                val k = 1f + 12f * at(0, 0.4f)
                0.5f + 0.42f * (tanh(k * (2 * t - 1)) / tanh(k))
            }
            VK.Reverb -> curve(color, pad, w, h) { t ->
                val pre = at(3) * 0.2f
                if (t < pre) 0f else (0.25f + 0.75f * at(0, 0.3f)) * exp(-(t - pre) * (8f - 7f * at(1, 0.5f))) * (0.8f + 0.2f * sin(t * 90))
            }
            else -> {   // not ported yet: the values as bars
                val n = x.size.coerceAtLeast(1)
                x.forEachIndexed { i, value ->
                    val bw = w / n
                    drawRoundRect(color.copy(alpha = 0.8f), Offset(pad + i * bw + bw * 0.2f, pad + h * (1 - value)),
                        androidx.compose.ui.geometry.Size(bw * 0.6f, h * value), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                }
            }
        }
    }
}

private fun DrawScope.curve(color: Color, pad: Float, w: Float, h: Float, fill: Boolean = true, f: (Float) -> Float) {
    val path = Path()
    val n = 120
    for (i in 0..n) {
        val t = i / n.toFloat()
        val px = pad + w * t
        val py = pad + h * (1 - f(t).coerceIn(0f, 1f))
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    if (fill) {
        val area = Path().apply { addPath(path); lineTo(pad + w, pad + h); lineTo(pad, pad + h); close() }
        drawPath(area, color.copy(alpha = 0.14f))
    }
    drawPath(path, color, style = Stroke(2.dp.toPx()))
}

private fun DrawScope.filter(color: Color, pad: Float, w: Float, h: Float, cut: Float, res: Float, lowpass: Boolean) {
    val q = 0.7f + res * 7f
    curve(color, pad, w, h) { t ->
        val f = 10f.pow((t - cut) * 3f)   // frequency relative to the cutoff, log axis
        val r = if (lowpass) f else 1f / f
        val mag = 1f / sqrt((1 - r * r).pow(2) + (r / q).pow(2))
        (0.42f * mag.coerceAtMost(3f) / 1.2f).coerceIn(0f, 1f)
    }
}

private fun DrawScope.adsr(color: Color, pad: Float, w: Float, h: Float, a: Float, d: Float, s: Float, r: Float) {
    val aw = 0.05f + a * 0.25f
    val dw = 0.05f + d * 0.3f
    val rw = 0.05f + r * 0.25f
    val sw = (1f - aw - dw - rw).coerceAtLeast(0.05f)
    val pts = listOf(0f to 0f, aw to 1f, aw + dw to s, aw + dw + sw to s, (aw + dw + sw + rw).coerceAtMost(1f) to 0f)
    val path = Path()
    pts.forEachIndexed { i, (t, v) -> val px = pad + w * t; val py = pad + h * (1 - v); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
    drawPath(Path().apply { addPath(path); lineTo(pad, pad + h); close() }, color.copy(alpha = 0.14f))
    drawPath(path, color, style = Stroke(2.dp.toPx()))
    pts.drop(1).dropLast(1).forEach { (t, v) -> drawCircle(color, 4.dp.toPx(), Offset(pad + w * t, pad + h * (1 - v))) }
}
