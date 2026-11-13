package com.pastel.minibox.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.pastel.minibox.state.Visual
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** VisualKind (pastel-minibox Source/ui/VisualSpec.h), in order. */
private object VK {
    const val Filter = 1; const val Hpf = 2; const val Adsr = 3; const val Decay = 4; const val Rate4 = 5; const val MacroEnv = 6
    const val PitchEnv = 7; const val Wave = 8; const val Lfo = 9; const val Algo = 10; const val Ops = 11; const val Sample = 12
    const val Slices = 13; const val Pads = 14; const val Lane = 15; const val Accent = 16; const val Ring = 17; const val Chorus = 18
    const val Delay = 19; const val Slide = 20; const val Drive = 21; const val Voices = 22; const val Arp = 23; const val Bits = 24
    const val Reverb = 25; const val Wavetable = 26; const val Matrix = 27; const val Unison = 28
}
/** The Wavetable's visuals, for the Sound tab (the matrix is a panel of its own, not a drawing). */
const val VK_WAVETABLE = 26
const val VK_MATRIX = 27

/** A section's visual: a drawing of its parameter values (never computed from audio, never touched), ported from the
    desktop's Visuals.cpp so both draw the same thing. The values are the parameters' own (Hz, seconds, choices). */
@Composable
fun SectionVisual(v: Visual, color: Color, modifier: Modifier = Modifier.fillMaxWidth().height(150.dp)) {
    if (v.kind == 0 || v.kind == VK.Pads || v.kind == VK.Lane) return
    val text = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    Canvas(modifier.clip(RoundedCornerShape(10.dp)).background(C.bg)) {
        val x = v.values
        fun f(i: Int) = x.getOrElse(i) { 0f }
        fun iv(i: Int) = f(i).roundToInt()
        text.textSize = 10.dp.toPx()
        val e = v.extra
        when (v.kind) {
            VK.Filter -> if (v.variant == 4) {   // the Wavetable's modes: LP 24, LP 12, HP, BP, Notch
                val m = iv(2).coerceIn(0, 4)
                filterCurve(color, intArrayOf(0, 0, 2, 1, 4)[m], f(0), f(1), if (m == 0) 4 else 2)
            } else {
                val mode = if (v.variant == 2) iv(2) else if (v.variant == 3) 3 else 0
                filterCurve(color, mode, f(0), if (v.variant == 3) 0f else f(1), if (v.variant == 0) 4 else 2)
            }
            VK.Hpf -> hpf(color, iv(0))
            VK.Adsr -> adsr(color, f(0), f(1), f(2), f(3))
            VK.Decay -> {
                if (v.variant == 1 && iv(0) == 0) adsr(color, 0.002f, 0.05f, 0.9f, 0.01f)
                else {
                    val atk = if (v.variant == 1) 0f else f(0)
                    val dec = f(1)
                    val x0 = 10.dp.toPx(); val y0 = size.height - 12.dp.toPx(); val w = size.width - 20.dp.toPx(); val h = size.height - 24.dp.toPx()
                    val ax = w * (atk / 1f).coerceIn(0f, 0.4f)
                    val p = mutableListOf(Offset(x0, y0), Offset(x0 + ax + 1, y0 - h))
                    for (i in 1..60) { val t = i / 60f; p += Offset(x0 + ax + (w - ax) * t, y0 - h * exp(-t * 2.5f / max(0.02f, dec))) }
                    strokeFill(p, y0, color)
                }
            }
            VK.Rate4, VK.MacroEnv -> {
                val rates = FloatArray(4); val lv = FloatArray(4)
                if (v.kind == VK.Rate4) for (k in 0 until 4) { rates[k] = f(k); lv[k] = f(4 + k) }
                else {
                    val a = f(0); val d = f(1); val l = f(2)
                    rates[0] = 0.85f - a * 0.35f; rates[1] = 0.55f - d * 0.3f; rates[2] = 0.5f - d * 0.3f; rates[3] = 0.6f
                    lv[0] = (0.85f + l * 0.25f).coerceIn(0.1f, 1f); lv[1] = lv[0] * 0.7f; lv[2] = lv[0] * 0.55f; lv[3] = 0f
                }
                val x0 = 10.dp.toPx(); val y0 = size.height - 12.dp.toPx(); val w = size.width - 20.dp.toPx(); val h = size.height - 24.dp.toPx()
                val widths = FloatArray(4) { 0.12f + (1f - rates[it]) * 0.9f }
                val tot = widths.sum()
                val p = mutableListOf(Offset(x0, y0))
                var cx = x0
                for (k in 0 until 4) { cx += w * widths[k] / tot; p += Offset(cx, y0 - h * lv[k]) }
                strokeFill(p, y0, color)
                p.drop(1).forEach { dot(it, color) }
            }
            VK.PitchEnv -> {
                val x0 = 10.dp.toPx(); val y0 = size.height - 12.dp.toPx(); val w = size.width - 20.dp.toPx(); val h = size.height - 24.dp.toPx()
                val p = (0..80).map { i ->
                    val t = i / 80f
                    val y = when (v.variant) {
                        0 -> 0.18f + 0.82f * f(0) * exp(-t * 1.2f / max(0.004f, f(1)))          // drums: bend, bend time
                        1 -> { val atk = f(1); val dec = f(2)
                            val env = if (t * 2f < atk) t * 2f / max(atk, 1e-3f) else exp(-(t * 2f - atk) / max(0.01f, dec)); 0.5f + 0.45f * f(0) * env }
                        2 -> 0.5f + 0.45f * f(0) * exp(-t * 2f / max(0.005f, f(1)))           // drum sampler PEG
                        else -> {                                                                // FM pitch EG: three rate / level segments
                            val seg = t * 3f; val k = min(2, seg.toInt())
                            val from = if (k == 0) 0f else f(3 + k - 1); val to = f(3 + k)
                            0.5f + 0.45f * (from + (to - from) * min(1f, (seg - k) * (0.5f + f(k))))
                        }
                    }
                    Offset(x0 + w * t, y0 - h * y.coerceIn(0f, 1f))
                }
                strokeFill(p, y0, color)
            }
            VK.Wave -> {
                var overlay: ((Float) -> Float)? = null
                val fn: (Float) -> Float = when (v.variant) {
                    0 -> {   // Bassline: pulse, saw, sub, noise mix
                        val mp = f(0); val ms = f(1); val msub = f(2); val pw = f(4)
                        val norm = 1f / max(0.5f, mp + ms + msub * 0.8f);
                        { ph -> (mp * waveValue(1, ph, pw) + ms * waveValue(0, ph) + msub * 0.8f * waveValue(4, ph * 0.5f)) * norm }
                    }
                    1 -> {   // VA DCO: 0 pulse, 1 saw, 2 both
                        val w = iv(0); val pw = f(1)
                        if (f(2) > 0.01f) overlay = { ph -> 0.55f * waveValue(4, ph * 0.5f) }
                        { ph -> if (w == 0) waveValue(1, ph, pw) else if (w == 1) waveValue(0, ph) else 0.6f * (waveValue(0, ph) + waveValue(1, ph, pw)) }
                    }
                    2 -> {   // VA VCO: saw, pulse, triangle
                        val w = iv(0); val pw = f(1)
                        if (f(2) > 0.01f) overlay = { ph -> 0.55f * waveValue(4, ph * 0.5f) }
                        { ph -> waveValue(if (w == 0) 0 else if (w == 1) 1 else 2, ph, pw) }
                    }
                    4 -> {   // Bassline pulse: the width, the PWM sweep as a lighter overlay
                        val pw = f(0); val depth = f(1)
                        if (depth > 0.01f) overlay = { ph -> 0.55f * waveValue(1, ph, (pw + 0.35f * depth).coerceIn(0.05f, 0.95f)) }
                        { ph -> waveValue(1, ph, pw) }
                    }
                    else -> {   // Acid: saw ↔ square morph
                        val m = f(0); val pw = f(1);
                        { ph -> (1f - m) * waveValue(0, ph) + m * waveValue(1, ph, 0.5f + (pw - 0.5f) * m) }
                    }
                }
                wave(color, fn, 2f, overlay)
            }
            VK.Lfo -> {
                val maps = arrayOf(intArrayOf(2, 4, 6, 7, 0, 0), intArrayOf(2, 0, 4, 6, 0, 0), intArrayOf(3, 2, 0, 5, 4, 6), intArrayOf(3, 2, 0, 4, 6, 7))
                val kind = maps[v.variant.coerceIn(0, 3)][iv(0).coerceIn(0, 5)]
                wave(color, { ph ->
                    when (kind) {
                        6 -> sh(ph)
                        7 -> { val a = sh(ph); val b = sh(ph + 0.25f); val fr = ph * 4f - floor(ph * 4f); a + (b - a) * fr * fr * (3 - 2 * fr) }
                        else -> waveValue(kind, ph)
                    }
                }, 2f, null)
            }
            VK.Algo -> e?.let { algo(color, it, text) }
            VK.Ops -> e?.let { ops(color, it, text) }
            VK.Sample -> e?.let { sample(color, it, text) }
            VK.Slices -> e?.let { slices(color, it, text) }
            VK.Accent -> {
                val acc = f(0); val sweep = f(1)
                val pattern8 = intArrayOf(1, 0, 1, 1, 1, 0, 0, 1)
                var charge = 0f
                val bw = (size.width - 20.dp.toPx()) / 8f; val base = size.height - 14.dp.toPx(); val top = 24.dp.toPx()
                for (i in 0 until 8) {
                    val a = pattern8[i] == 1
                    charge = if (a) min(1f, charge + 0.15f + sweep * 0.3f) else charge * 0.35f
                    val lvl = if (a) 0.25f + 0.65f * charge * (0.4f + acc * 0.6f) else 0.18f
                    val bx = 10.dp.toPx() + i * bw
                    val p = mutableListOf(Offset(bx + 3, base), Offset(bx + 5, base - (base - top) * lvl))
                    for (k in 1..12) p += Offset(bx + 5 + (bw - 10) * k / 12f, base - (base - top) * lvl * exp(-k / 4f))
                    strokeFill(p, base, if (a) color else C.faint, if (a) 0.14f else 0.1f)
                    if (a) {
                        drawRoundRect(color, Offset(bx + bw / 2 - 7.dp.toPx(), 6.dp.toPx()), Size(14.dp.toPx(), 13.dp.toPx()), CornerRadius(4.dp.toPx()))
                        label("A", bx + bw / 2, 16.dp.toPx(), C.bg, text, center = true)
                    }
                }
            }
            VK.Reverb -> {
                val mix = f(0); val sz = f(1); val tone = f(2); val pre = f(3) / 0.1f
                val base = size.height - 12.dp.toPx(); val top = 12.dp.toPx(); val x0 = 12.dp.toPx()
                drawLine(C.quiet, Offset(x0, base), Offset(x0, top + 4), 2.2f.dp.toPx())
                val gap = 6.dp.toPx() + pre * (size.width - 30.dp.toPx()) * 0.45f
                val span = size.width - 24.dp.toPx() - gap; val rt = 0.25f + 0.75f * sz
                val step = 3.dp.toPx()
                val bars = (span / step).toInt()
                var seed = 7L
                val env = mutableListOf<Offset>()
                for (i in 0 until bars) {
                    seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
                    val jit = 0.55f + 0.45f * (seed.toFloat() / 0x7fffffff) * (0.4f + 0.6f * tone)
                    val t = i.toFloat() / max(1, bars - 1)
                    val en = exp(-t * 3.2f / rt) * min(1f, i / 4f + 0.25f)
                    val hgt = (base - top) * (0.3f + 0.7f * mix) * en
                    val bx = x0 + gap + i * step
                    drawLine(color, Offset(bx, base), Offset(bx, base - hgt * jit), 1.6f)
                    env += Offset(bx, base - hgt)
                }
                strokeFill(env, base, color, 0.12f, 1.4f)
            }
            VK.Ring -> ring(color, 0.15f + f(1) * 0.5f, 4f + log2(max(40f, f(0)) / 40f) * 4f)
            VK.Chorus -> chorus(color, if (v.variant == 1) iv(0) + 1 else iv(0))
            VK.Delay -> {
                val base = size.height - 10.dp.toPx()
                for (i in 0 until 6) {
                    val cx = 16.dp.toPx() + i * (size.width - 32.dp.toPx()) / 5.5f
                    val lvl = max(0.05f, f(0)).pow(i)
                    val h = (size.height - 24.dp.toPx()) * lvl
                    drawRoundRect(color.copy(alpha = if (i == 0) 1f else 0.5f + 0.4f * lvl), Offset(cx - 4.dp.toPx(), base - h),
                        Size(8.dp.toPx(), h), CornerRadius(4.dp.toPx()))
                }
            }
            VK.Slide -> {
                val steps = intArrayOf(3, 3, 7, 5, 5, 10, 8, 8)
                val bw = (size.width - 20.dp.toPx()) / 8f
                val time = (f(0) * 2f).coerceIn(0.05f, 0.95f)
                fun py(s: Int) = size.height - 16.dp.toPx() - (s - 3) / 7f * (size.height - 34.dp.toPx())
                val slides = v.variant == 0 || iv(1) > 0
                val sw = 2.2f.dp.toPx()
                for (i in 0 until 8) {
                    val bx = 10.dp.toPx() + i * bw
                    val glide = slides && (i == 1 || i == 4)
                    if (glide && i + 1 < 8) {
                        val split = bw * (1f - time * 0.8f)
                        drawLine(color, Offset(bx + 2, py(steps[i])), Offset(bx + split, py(steps[i])), sw)
                        val path = Path().apply {
                            moveTo(bx + split, py(steps[i]))
                            cubicTo(bx + split + (bw - split) * 0.5f, py(steps[i]), bx + bw - 4, py(steps[i + 1]), bx + bw + 2, py(steps[i + 1]))
                        }
                        drawPath(path, color, style = Stroke(sw))
                    } else drawLine(color, Offset(bx + 2, py(steps[i])), Offset(bx + bw - 4, py(steps[i])), sw)
                    drawCircle(color, 2.5f.dp.toPx(), Offset(bx + 2.5f.dp.toPx(), py(steps[i])))
                }
            }
            VK.Drive -> {
                val cx = size.width / 2; val cy = size.height / 2; val sz = min(size.width, size.height) / 2 - 12.dp.toPx()
                drawLine(C.divider, Offset(cx - sz, cy), Offset(cx + sz, cy)); drawLine(C.divider, Offset(cx, cy - sz), Offset(cx, cy + sz))
                drawLine(C.faint, Offset(cx - sz, cy + sz), Offset(cx + sz, cy - sz), 1f)
                val gain = 1f + f(0) * 5f
                val type = iv(1)
                val p = (0..80).map { i ->
                    val t = -1f + 2f * i / 80f
                    val y = if (v.variant == 1) {   // the pedals: overdrive, distortion, fuzz
                        val u = t * gain * 2f
                        when (type) {
                            2 -> 0.9f * tanh(tanh(t * gain * 3f) * 3f)
                            1 -> 0.85f * u / (1f + abs(u).pow(4f)).pow(0.25f)
                            else -> 0.3f * t + 0.6f * tanh(t * gain * 1.5f)
                        }
                    } else if (v.variant == 2) when (type) {   // the Wavetable's distortion: soft, hard, tube
                        1 -> (t * gain).coerceIn(-1f, 1f)
                        2 -> tanh(t * gain + 0.35f) - tanh(0.35f)
                        else -> tanh(t * gain)
                    } else when (type) {
                        2 -> sin(t * gain * 1.2f) * 0.9f
                        1 -> tanh(t * gain + 0.25f) - tanh(0.25f) * 0.8f
                        else -> tanh(t * gain)
                    }
                    Offset(cx + t * sz, cy - y.coerceIn(-1f, 1f) * sz)
                }
                stroke(p, color, 2.2f)
            }
            VK.Wavetable -> if (e != null) {
                // the table as a stack of frames, back to front, the frame at the position lit; a bar for the position
                val frames = e.optJSONArray("frames")
                val lit = e.floats("lit")
                val pos = e.optDouble("pos", 0.0).toFloat()
                val n = frames?.length() ?: 0
                val dx = size.width * 0.2f / max(1, n); val dy = size.height * 0.4f / max(1, n)
                val fw = size.width * 0.72f; val fh = size.height * 0.36f
                val x0 = 18.dp.toPx(); val y0 = size.height - fh / 2 - 22.dp.toPx()
                fun path(pts: FloatArray, kx: Float, ky: Float) = pts.indices.map { i -> Offset(kx + fw * i / (pts.size - 1f), ky - pts[i] * fh * 0.5f) }
                for (k in n - 1 downTo 0) {
                    val a = frames!!.getJSONArray(k)
                    val pts = FloatArray(a.length()) { a.getDouble(it).toFloat() }
                    stroke(path(pts, x0 + k * dx, y0 - k * dy), color.copy(alpha = 0.14f + 0.2f * (1f - abs(k / max(1f, n - 1f) - pos))), 1.2f)
                }
                if (lit.isNotEmpty()) {
                    val kf = pos * (n - 1)
                    strokeFill(path(lit, x0 + kf * dx, y0 - kf * dy), y0 - kf * dy, color, 0.16f, 2.4f)
                }
                val ty = size.height - 10.dp.toPx(); val tx0 = 14.dp.toPx(); val tw = size.width - 28.dp.toPx()
                drawLine(C.divider, Offset(tx0, ty), Offset(tx0 + tw, ty), 3.dp.toPx())
                drawLine(color, Offset(tx0, ty), Offset(tx0 + tw * pos, ty), 3.dp.toPx())
                drawCircle(color, 5.dp.toPx(), Offset(tx0 + tw * pos, ty))
            }
            VK.Unison -> {
                // the stack across the field; the low end held in the middle (Mono Bass)
                val n = iv(0).coerceIn(1, 16); val det = f(1); val width = f(2); val cx = size.width / 2; val half = size.width / 2 - 26.dp.toPx()
                for (k in 0 until n) {
                    val sp = if (n == 1) 0f else 2f * k / (n - 1) - 1f
                    val x = cx + sp * width * half
                    val h = (size.height - 24.dp.toPx()) * (0.55f + 0.45f * (1f - abs(sp) * det))
                    drawLine(color.copy(alpha = 0.5f), Offset(x, size.height / 2 - h / 2), Offset(x, size.height / 2 + h / 2), 3.dp.toPx())
                }
                if (f(3) >= 20f) {
                    drawRoundRect(color, Offset(cx - 10.dp.toPx(), 8.dp.toPx()), Size(20.dp.toPx(), size.height - 16.dp.toPx()), CornerRadius(6.dp.toPx()))
                    label("S", cx, size.height / 2 + 4.dp.toPx(), C.bg, text, center = true)
                }
                label("L", 8.dp.toPx(), size.height - 7.dp.toPx(), C.faint, text)
                label("R", size.width - 14.dp.toPx(), size.height - 7.dp.toPx(), C.faint, text)
            }
            VK.Voices -> {
                val voices = max(1, v.variant)
                val gap = 6.dp.toPx(); val vw = (size.width - 20.dp.toPx() - gap * (voices - 1)) / voices
                val stackLabels = arrayOf(arrayOf("-c", "0", "+c"), arrayOf("0", "+7", "+12"), arrayOf("-12", "0", "+12"))
                for (i in 0 until voices) {
                    val vx = 10.dp.toPx() + i * (vw + gap)
                    drawRoundRect(C.control, Offset(vx, 10.dp.toPx()), Size(vw, size.height - 20.dp.toPx()), CornerRadius(6.dp.toPx()))
                    if (voices == 3 && e != null && e.has("stackMode")) {
                        val m = e.optInt("stackMode").coerceIn(0, 2)
                        val on = e.optBoolean("stacked") || i == 1
                        if (on) drawRoundRect(color.copy(alpha = 0.35f), Offset(vx, 10.dp.toPx() + (size.height - 20.dp.toPx()) * 0.25f),
                            Size(vw, (size.height - 20.dp.toPx()) * 0.75f), CornerRadius(6.dp.toPx()))
                        label(stackLabels[m][i], vx + vw / 2, 26.dp.toPx(), if (on) C.text else C.faint, text, center = true)
                    }
                }
            }
            VK.Arp -> {
                val mode = iv(0); val range = max(1, iv(1))
                val bw = (size.width - 20.dp.toPx()) / 8f
                for (i in 0 until 8) {
                    var p = i % (3 * range)
                    if (mode == 2) p = 3 * range - 1 - p
                    else if (mode == 3) p = if (i < 4) i else 7 - i
                    else if (mode == 4) p = (i * 5 + 3) % (3 * range)
                    val top = (3 * range - 1).toFloat()
                    val by = size.height - 18.dp.toPx() - (if (mode == 0) 0.5f else p / max(1f, top)) * (size.height - 36.dp.toPx())
                    drawRoundRect(if (mode == 0) C.faint else color, Offset(10.dp.toPx() + i * bw + 2, by - 4.dp.toPx()),
                        Size((bw - 4) * 0.6f, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
                }
            }
            VK.Bits -> {
                val levels = 2f.pow((f(0) - 1f).coerceIn(2f, 8f) * 0.75f)
                val stepsN = (f(1) * 1.2f).coerceIn(6f, 60f)
                val cy = size.height / 2; val amp = size.height * 0.32f; val x0 = 10.dp.toPx(); val w = size.width - 20.dp.toPx()
                stroke((0..200).map { i -> val t = i / 200f; Offset(x0 + w * t, cy - sin(2 * PI.toFloat() * 1.5f * t) * amp) }, C.faint, 1.2f)
                val vintage = if (v.variant == 0) iv(2) == 1 else true
                if (vintage) {
                    val p = mutableListOf<Offset>()
                    for (k in 0 until stepsN.toInt()) {
                        val t0 = k / stepsN; val t1 = (k + 1) / stepsN
                        val vv = (sin(2 * PI.toFloat() * 1.5f * t0) * levels / 2).roundToInt() / (levels / 2)
                        p += Offset(x0 + w * t0, cy - vv * amp); p += Offset(x0 + w * t1, cy - vv * amp)
                    }
                    stroke(p, color, 2f)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- drawing helpers

private fun waveValue(kind: Int, phIn: Float, pw: Float = 0.5f): Float {   // 0 saw, 1 pulse, 2 tri, 3 sine, 4 square, 5 ramp up
    val ph = phIn - floor(phIn)
    return when (kind) {
        0 -> 1f - 2f * ph
        1 -> if (ph < pw) 1f else -1f
        2 -> if (ph < 0.5f) 4f * ph - 1f else 3f - 4f * ph
        3 -> sin(2 * PI.toFloat() * ph)
        4 -> if (ph < 0.5f) 1f else -1f
        5 -> 2f * ph - 1f
        else -> 0f
    }
}

private val SH = floatArrayOf(0.6f, -0.3f, 0.9f, -0.8f, 0.1f, 0.5f, -0.6f, 0.3f, -0.1f, 0.8f, -0.9f, 0.2f, 0.7f, -0.4f, 0f, -0.7f)
private fun sh(ph: Float) = SH[floor(ph * 4f).toInt() and 15]

private fun DrawScope.stroke(pts: List<Offset>, c: Color, sw: Float = 2f) {
    if (pts.size < 2) return
    val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y) }
    drawPath(path, c, style = Stroke(sw.dp.toPx()))
}

private fun DrawScope.strokeFill(pts: List<Offset>, baseY: Float, c: Color, fill: Float = 0.14f, sw: Float = 2f) {
    if (pts.size < 2) return
    val area = Path().apply {
        moveTo(pts[0].x, baseY); for (p in pts) lineTo(p.x, p.y); lineTo(pts.last().x, baseY); close()
    }
    drawPath(area, c.copy(alpha = fill))
    stroke(pts, c, sw)
}

private fun DrawScope.dot(p: Offset, c: Color) {
    drawCircle(C.bg, 3.5f.dp.toPx(), p)
    drawCircle(c, 3.5f.dp.toPx(), p, style = Stroke(1.8f.dp.toPx()))
}

private fun DrawScope.label(s: String, x: Float, y: Float, c: Color, paint: Paint, center: Boolean = false) {
    paint.color = c.toArgb()
    paint.textAlign = if (center) Paint.Align.CENTER else Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(s, x, y, paint)
}

private fun DrawScope.filterCurve(c: Color, mode: Int, cutHz: Float, res: Float, poles: Int) {
    for (i in 1..3) drawLine(C.divider, Offset(size.width * i / 4f, 8.dp.toPx()), Offset(size.width * i / 4f, size.height - 8.dp.toPx()), 1f)
    drawLine(C.divider, Offset(8.dp.toPx(), size.height * 0.42f), Offset(size.width - 8.dp.toPx(), size.height * 0.42f), 1f)
    val q = 0.7f + res * 7f; val pad = 10.dp.toPx(); val n = 120
    val pts = ArrayList<Offset>(n + 1)
    var minY = Float.MAX_VALUE; var cutX = 0f
    for (i in 0..n) {
        val fr = 20f * 1000f.pow(i.toFloat() / n)
        val xx = fr / max(20f, cutHz)
        val den = sqrt((1 - xx * xx) * (1 - xx * xx) + (xx / q) * (xx / q))
        var mag = when (mode) { 1 -> (xx / q) / den * q * 0.6f; 2 -> xx * xx / den; 3 -> 1f / (1f + xx * xx); 4 -> abs(1 - xx * xx) / den; else -> 1f / den }
        if (poles == 4 && mode == 0) mag *= 1f / sqrt((1 - xx * xx) * (1 - xx * xx) + (xx / 0.7f) * (xx / 0.7f))
        val db = (20f * log10(max(mag, 1e-6f))).coerceIn(-48f, 18f)
        val px = 6.dp.toPx() + (size.width - 12.dp.toPx()) * i / n
        val py = pad + (18f - db) / 66f * (size.height - pad * 2)
        pts += Offset(px, py)
        if (fr <= cutHz) cutX = px
        minY = min(minY, py)
    }
    strokeFill(pts, size.height - 4.dp.toPx(), c)
    var cy = minY
    for (p in pts) if (abs(p.x - cutX) < 3f) cy = p.y
    drawCircle(c, 4.5f.dp.toPx(), Offset(cutX, if (mode == 0) min(cy, minY + 6.dp.toPx()) else cy))
}

private fun DrawScope.hpf(c: Color, sel: Int) {
    val cuts = floatArrayOf(0f, 0.18f, 0.3f, 0.42f)
    for (i in 0 until 4) {
        val pts = (0..60).map { k ->
            val t = k / 60f
            val db = if (i == 0) (if (t < 0.12f) 4f else max(0f, 4f - (t - 0.12f) * 40f))
            else { val xx = (t + 0.001f) / cuts[i]; 20f * log10(xx * xx / sqrt((1 - xx * xx) * (1 - xx * xx) + (xx / 0.7f) * (xx / 0.7f)) + 1e-6f) }
            Offset(8.dp.toPx() + (size.width - 16.dp.toPx()) * t, 14.dp.toPx() + (8f - db.coerceIn(-36f, 8f)) / 44f * (size.height - 28.dp.toPx()))
        }
        if (i == sel) strokeFill(pts, size.height - 4.dp.toPx(), c) else stroke(pts, C.faint, 1.2f)
    }
}

private fun DrawScope.adsr(c: Color, a: Float, d: Float, s: Float, rel: Float) {
    val x0 = 10.dp.toPx(); val y0 = size.height - 12.dp.toPx(); val w = size.width - 20.dp.toPx(); val h = size.height - 24.dp.toPx()
    fun t(sec: Float) = 0.08f + log2(1f + sec * 40f) / 9f   // long times compressed
    val ta = t(a); val td = t(d); val tr = t(rel); val hold = 0.25f
    val sc = w / (ta + td + hold + tr)
    val p = listOf(Offset(x0, y0), Offset(x0 + ta * sc, y0 - h), Offset(x0 + (ta + td) * sc, y0 - h * s),
        Offset(x0 + (ta + td + hold) * sc, y0 - h * s), Offset(x0 + w, y0))
    strokeFill(p, y0, c)
    for (i in 1..3) dot(p[i], c)
}

private fun DrawScope.wave(c: Color, fn: (Float) -> Float, cycles: Float, overlay: ((Float) -> Float)?) {
    drawLine(C.divider, Offset(8.dp.toPx(), size.height / 2), Offset(size.width - 8.dp.toPx(), size.height / 2), 1f)
    val amp = size.height * 0.32f
    fun trace(f: (Float) -> Float) = (0..240).map { i ->
        val t = i / 240f
        Offset(12.dp.toPx() + (size.width - 24.dp.toPx()) * t, size.height / 2 - f(t * cycles).coerceIn(-1.3f, 1.3f) * amp)
    }
    overlay?.let { stroke(trace(it), C.quiet.copy(alpha = 0.6f), 1.5f) }
    stroke(trace(fn), c, 2.2f)
}

private fun DrawScope.ring(c: Color, decay: Float, cycles: Float) {
    drawLine(C.divider, Offset(8.dp.toPx(), size.height / 2), Offset(size.width - 8.dp.toPx(), size.height / 2), 1f)
    stroke((0..260).map { i ->
        val t = i / 260f
        Offset(10.dp.toPx() + (size.width - 20.dp.toPx()) * t,
            size.height / 2 - sin(2 * PI.toFloat() * cycles * t) * exp(-t / max(0.03f, decay)) * size.height * 0.38f)
    }, c, 1.8f)
}

private fun DrawScope.chorus(c: Color, mode: Int) {
    for (k in 0 until 2) {
        if (k == 1 && mode <= 1) continue   // off: a flat line; mode I: one tap
        val depth = if (mode == 0) 0f else if (mode == 3) 0.12f else 0.28f
        val rate = if (mode == 3) 6f else 2f
        stroke((0..120).map { i ->
            val t = i / 120f
            Offset(10.dp.toPx() + (size.width - 20.dp.toPx()) * t, size.height / 2 - sin(2 * PI.toFloat() * (t * rate + k * 0.5f)) * size.height * depth)
        }, c.copy(alpha = if (k == 0) 1f else 0.55f), 2f)
    }
}

private fun JSONObject.floats(key: String): FloatArray = optJSONArray(key)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } ?: FloatArray(0)

/** A sample's min / max peaks between `from` and `to`; outside [dimBefore, dimAfter] faint (before Start, after End). */
private fun DrawScope.waveform(c: Color, peaks: FloatArray, left: Float, top: Float, w: Float, h: Float,
                               dimBefore: Float = 0f, dimAfter: Float = 1f) {
    if (peaks.size < 4) return
    val bins = peaks.size / 2
    val step = 3.dp.toPx()
    val cols = max(1, (w / step).toInt())
    val cy = top + h / 2
    for (i in 0 until cols) {
        val t = i.toFloat() / cols
        val b = (t * bins).toInt().coerceIn(0, bins - 1)
        val lo = peaks[b * 2]; val hi = peaks[b * 2 + 1]
        val xx = left + i * step + 1f
        drawLine(if (t < dimBefore || t > dimAfter) C.faint else c, Offset(xx, cy - hi * h * 0.48f), Offset(xx, cy - lo * h * 0.48f), 1.8f.dp.toPx())
    }
}

private fun DrawScope.sample(c: Color, e: JSONObject, paint: Paint) {
    val peaks = e.floats("peaks")
    val zones = e.optJSONArray("zones")
    val zh = if (zones != null) 30.dp.toPx() else 0f
    val l = 12.dp.toPx(); val t = 30.dp.toPx(); val w = size.width - 24.dp.toPx(); val h = size.height - 40.dp.toPx() - zh
    label(e.optString("name").ifEmpty { "No sample" }, l, 18.dp.toPx(), if (peaks.isEmpty()) C.faint else C.quiet, paint)
    if (peaks.isNotEmpty()) {
        val s = e.optDouble("s", 0.0).toFloat(); val en = e.optDouble("e", 1.0).toFloat()
        val ls = e.optDouble("ls", -1.0).toFloat(); val le = e.optDouble("le", -1.0).toFloat()
        if (ls >= 0) drawRect(c.copy(alpha = 0.1f), Offset(l + w * ls, t), Size(w * (le - ls), h))
        waveform(c, peaks, l, t, w, h, s, en)
        fun marker(lab: String, pos: Float) {
            val mx = l + w * pos
            drawLine(C.text, Offset(mx, t), Offset(mx, t + h), 1.5f.dp.toPx())
            drawRoundRect(C.text, Offset(mx - 9.dp.toPx(), t), Size(18.dp.toPx(), 16.dp.toPx()), CornerRadius(4.dp.toPx()))
            label(lab, mx, t + 12.dp.toPx(), C.bg, paint, center = true)
        }
        marker("S", s)
        if (ls >= 0) marker("L", ls)
        marker("E", en)
    }
    if (zones != null) {   // key zones: lo / hi with their root, the selected one highlighted
        val zy = size.height - zh - 4.dp.toPx()
        val kw = w / 128f
        val black = setOf(1, 3, 6, 8, 10)
        for (k in 0 until 128) drawRect(if (k % 12 in black) C.divider else C.control, Offset(l + k * kw, zy + 4.dp.toPx()), Size(kw - 0.5f, zh - 8.dp.toPx()))
        for (i in 0 until zones.length()) {
            val z = zones.getJSONObject(i)
            val lo = z.getDouble("lo").toFloat(); val hi = z.getDouble("hi").toFloat(); val root = z.getDouble("root").toFloat()
            val sel = z.optBoolean("sel")
            drawRoundRect((if (sel) c else C.faint).copy(alpha = if (sel) 0.35f else 0.25f), Offset(l + lo * kw, zy), Size((hi - lo + 1) * kw - 1, zh),
                CornerRadius(5.dp.toPx()))
            drawCircle(if (sel) c else C.quiet, 4.dp.toPx(), Offset(l + (root + 0.5f) * kw, zy + zh / 2))
        }
    }
}

private fun DrawScope.slices(c: Color, e: JSONObject, paint: Paint) {
    val peaks = e.floats("peaks")
    val l = 10.dp.toPx(); val t = 30.dp.toPx(); val w = size.width - 20.dp.toPx(); val h = size.height - 38.dp.toPx()
    label(e.optString("name").ifEmpty { "No loop" }, l + 2, 18.dp.toPx(), if (peaks.isEmpty()) C.faint else C.quiet, paint)
    if (peaks.isEmpty()) return
    waveform(c.copy(alpha = 0.85f), peaks, l, t, w, h)
    val starts = e.floats("starts"); val ends = e.floats("ends")
    val sel = e.optInt("selected")
    for (p in 0 until min(8, starts.size)) {
        val xx = l + w * starts[p]
        if (p == sel) drawRect(c.copy(alpha = 0.14f), Offset(xx, t), Size(l + w * ends.getOrElse(p) { 1f } - xx, h))
        drawLine((if (p == 0) C.quiet else C.text).copy(alpha = 0.8f), Offset(xx, t), Offset(xx, t + h), 1f.dp.toPx())
        label("${p + 1}", xx + 5.dp.toPx(), t + 10.dp.toPx(), C.quiet, paint)
    }
}

private fun DrawScope.algo(c: Color, e: JSONObject, paint: Paint) {
    val stacks = e.optJSONArray("stacks") ?: return
    val mods = e.optJSONArray("mods") ?: return
    val carriers = e.optInt("carriers"); val fbOp = e.optInt("feedbackOp"); val selected = e.optInt("selected", -1)
    val numStacks = max(1, stacks.length())
    var rows = 1
    for (s in 0 until stacks.length()) rows = max(rows, stacks.getJSONArray(s).length())
    val bw = min(34.dp.toPx(), (size.width - 30.dp.toPx()) / numStacks - 12.dp.toPx())
    val bh = min(24.dp.toPx(), (size.height - 40.dp.toPx()) / rows - 10.dp.toPx())
    val busY = size.height - 16.dp.toPx()
    val pos = HashMap<Int, Offset>()
    for (s in 0 until stacks.length()) {
        val st = stacks.getJSONArray(s)
        val cx = size.width * (s + 0.5f) / numStacks
        val off = rows - st.length()
        for (k in 0 until st.length()) pos[st.getInt(k)] = Offset(cx, 16.dp.toPx() + (k + off) * (size.height - 44.dp.toPx()) / rows + bh / 2)
    }
    val lw = 1.5f.dp.toPx()
    for (to in 0 until 6) for (from in 0 until 6)
        if ((mods.getInt(to) shr from) and 1 != 0) {
            val a = pos[from] ?: continue; val b = pos[to] ?: continue
            drawLine(C.quiet, Offset(a.x, a.y + bh / 2), Offset(b.x, b.y - bh / 2), lw)
        }
    var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
    paint.textSize = 12.dp.toPx()
    for (op in 0 until 6) {
        val p = pos[op] ?: continue
        val car = (carriers shr op) and 1 != 0
        if (car) { drawLine(C.quiet, Offset(p.x, p.y + bh / 2), Offset(p.x, busY), lw); minX = min(minX, p.x); maxX = max(maxX, p.x) }
        val tl = Offset(p.x - bw / 2, p.y - bh / 2)
        drawRoundRect(if (car) c else C.control, tl, Size(bw, bh), CornerRadius(6.dp.toPx()))
        if (!car) drawRoundRect(c, tl, Size(bw, bh), CornerRadius(6.dp.toPx()), style = Stroke(1f.dp.toPx()))
        label("${op + 1}", p.x, p.y + 4.5f.dp.toPx(), if (car) C.bg else C.text, paint, center = true)
        if (op == fbOp) {
            val fb = Path().apply {
                moveTo(p.x + bw / 2, p.y); lineTo(p.x + bw / 2 + 8.dp.toPx(), p.y); lineTo(p.x + bw / 2 + 8.dp.toPx(), p.y - bh * 0.9f)
                lineTo(p.x, p.y - bh * 0.9f); lineTo(p.x, p.y - bh / 2)
            }
            drawPath(fb, C.quiet, style = Stroke(lw))
        }
        if (op == selected) drawRoundRect(C.text, Offset(tl.x - 4.dp.toPx(), tl.y - 4.dp.toPx()), Size(bw + 8.dp.toPx(), bh + 8.dp.toPx()),
            CornerRadius(8.dp.toPx()), style = Stroke(lw))
    }
    if (minX <= maxX) drawLine(C.quiet, Offset(minX, busY), Offset(maxX, busY), lw)
}

private fun DrawScope.ops(c: Color, e: JSONObject, paint: Paint) {
    val ops = e.optJSONArray("ops") ?: return
    val sel = e.optInt("selected", -1)
    val waves = arrayOf("Sine", "Half", "Sq", "Saw")
    val cw = (size.width - 16.dp.toPx()) / 6f
    for (o in 0 until min(6, ops.length())) {
        val x = ops.getJSONObject(o)
        val cx = 8.dp.toPx() + cw * o
        val car = x.optBoolean("carrier"); val muted = x.optBoolean("muted")
        if (o == sel) {
            drawRoundRect(C.surface, Offset(cx + 3, 6.dp.toPx()), Size(cw - 6, size.height - 12.dp.toPx()), CornerRadius(8.dp.toPx()))
            drawRoundRect(c, Offset(cx + 3, 6.dp.toPx()), Size(cw - 6, size.height - 12.dp.toPx()), CornerRadius(8.dp.toPx()), style = Stroke(1.5f.dp.toPx()))
        }
        paint.textSize = 13.dp.toPx(); label("${o + 1}", cx + cw / 2, 24.dp.toPx(), C.text, paint, center = true)
        drawRoundRect(if (car) c else C.control, Offset(cx + cw / 2 - 11.dp.toPx(), 30.dp.toPx()), Size(22.dp.toPx(), 16.dp.toPx()), CornerRadius(8.dp.toPx()))
        paint.textSize = 10.dp.toPx(); label(if (car) "C" else "M", cx + cw / 2, 42.dp.toPx(), if (car) C.bg else C.quiet, paint, center = true)
        val ratio = if (x.optBoolean("fixed")) "${x.optDouble("freq").roundToInt()}Hz" else "%.2f".format(x.optDouble("ratio"))
        label(ratio, cx + cw / 2, 60.dp.toPx(), C.quiet, paint, center = true)
        val barTop = 68.dp.toPx(); val barH = max(10f, size.height - 120.dp.toPx())
        val lvl = x.optDouble("level").toFloat().coerceIn(0f, 1f)
        drawRoundRect(C.control, Offset(cx + cw / 2 - 5.dp.toPx(), barTop), Size(10.dp.toPx(), barH), CornerRadius(5.dp.toPx()))
        drawRoundRect(if (muted) C.faint else c, Offset(cx + cw / 2 - 5.dp.toPx(), barTop + barH * (1 - lvl)), Size(10.dp.toPx(), barH * lvl), CornerRadius(5.dp.toPx()))
        label(waves[x.optInt("wave").coerceIn(0, 3)], cx + cw / 2, size.height - 40.dp.toPx(), C.faint, paint, center = true)
        val ey = size.height - 34.dp.toPx(); val ew = cw - 16.dp.toPx(); val eh = 20.dp.toPx()
        val l1 = x.optDouble("l1").toFloat(); val l3 = x.optDouble("l3").toFloat(); val r1 = x.optDouble("r1").toFloat()
        stroke(listOf(Offset(cx + 8.dp.toPx(), ey + eh), Offset(cx + 8.dp.toPx() + ew * 0.15f * (1f - r1) + 1, ey + eh * (1 - l1)),
            Offset(cx + 8.dp.toPx() + ew * 0.55f, ey + eh * (1 - l3)), Offset(cx + 8.dp.toPx() + ew * 0.8f, ey + eh * (1 - l3)),
            Offset(cx + 8.dp.toPx() + ew, ey + eh)), if (o == sel) c else C.quiet, 1.5f)
        drawCircle(if (muted) C.faint else c, 4.dp.toPx(), Offset(cx + cw / 2, size.height - 9.dp.toPx()))
    }
}

