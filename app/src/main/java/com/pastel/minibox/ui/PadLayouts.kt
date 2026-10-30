package com.pastel.minibox.ui

import com.pastel.minibox.state.ProjectInfo
import com.pastel.minibox.state.TrackInfo

private val NOTE_NAMES = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
fun noteName(n: Int) = NOTE_NAMES[((n % 12) + 12) % 12] + (n / 12 - 1)
fun keyName(pc: Int) = NOTE_NAMES[((pc % 12) + 12) % 12]

/** Drum pads 0–7 (0 = bottom-left) by General MIDI note: bottom row Kick, Snare | C Hat, O Hat; above it
    Tom, Clap | Perc, Cymbal. Left thumb on kick and snare, right thumb on the hats and cymbal. */
private val THUMB_ORDER = intArrayOf(36, 38, 42, 46, 45, 39, 37, 49)

/** Base octave of a synth's pads (C2 for the basses, C3 for the rest), before the track's octave shift. */
private fun baseOctave(type: String) = if (type == "mono" || type == "acid") 2 else 3

/** The in-scale pads: 16 consecutive scale notes from the root, left to right and bottom to top, each once. */
private fun scaleNotes(p: ProjectInfo, type: String, octave: Int): IntArray {
    val iv = p.scaleIntervals.ifEmpty { listOf(0, 2, 4, 5, 7, 9, 11) }
    val n = iv.size
    val rowStep = 4
    val base = 12 * (baseOctave(type) + octave + 1) + p.scaleRoot
    return IntArray(16) { pad ->
        val deg = (pad / 4) * rowStep + pad % 4
        base + 12 * (deg / n) + iv[deg % n]
    }
}

fun notesLayout(track: Int, info: TrackInfo, p: ProjectInfo, octave: Int): PadLayout {
    val notes = scaleNotes(p, info.type, octave)
    val roots = (0 until 16).filter { ((notes[it] - p.scaleRoot) % 12 + 12) % 12 == 0 }.toSet()
    return PadLayout(track, Tokens.TRACK[track], notes, Array(16) { noteName(notes[it]) }, roots)
}

/** Chords mode: the same in-scale pads, each playing the engine's diatonic chord on that degree (NoteInput's Auto
    chords follow the project scale); the pads show the chord's name and its degree. */
fun chordsLayout(track: Int, info: TrackInfo, p: ProjectInfo, octave: Int): PadLayout {
    val iv = p.scaleIntervals.ifEmpty { listOf(0, 2, 4, 5, 7, 9, 11) }
    val n = iv.size
    val notes = scaleNotes(p, info.type, octave)
    val rowStep = 4
    val labels = Array(16) { "" }
    val subs = Array(16) { "" }
    val roots = mutableSetOf<Int>()
    for (pad in 0 until 16) {
        val deg = (pad / 4) * rowStep + pad % 4
        val d = deg % n
        fun at(k: Int) = iv[(d + k) % n] + 12 * ((d + k) / n)
        val third = at(2) - at(0)
        val fifth = at(4) - at(0)
        val quality = when {
            n != 7 -> ""
            third == 4 && fifth == 7 -> ""
            third == 3 && fifth == 7 -> "m"
            third == 3 && fifth == 6 -> "°"
            third == 4 && fifth == 8 -> "+"
            else -> "5"
        }
        labels[pad] = keyName(notes[pad]) + quality
        val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII").getOrElse(d) { "${d + 1}" }
        subs[pad] = if (quality == "m" || quality == "°") roman.lowercase() + (if (quality == "°") "°" else "") else roman
        if (d == 0) roots += pad
    }
    return PadLayout(track, Tokens.TRACK[track], notes, labels, roots, subs, bigLabels = true)
}

fun rateName(beats: Float) = when (beats) {
    1f -> "1/4"; 0.5f -> "1/8"; 0.25f -> "1/16"; 0.125f -> "1/32"; 1f / 3f -> "1/8T"; 1f / 6f -> "1/16T"; else -> "1/16"
}

/** Drum pads: the device's parts placed for two thumbs; the lower 8 hit, the upper 8 roll at `rollBeats`. */
fun drumsLayout(track: Int, info: TrackInfo, rollBeats: Float): PadLayout? {
    val lanes = info.lanes
    if (lanes.isEmpty()) return null
    val notes = lanes.map { it.note }
    val order = THUMB_ORDER.map { gm -> notes.indexOf(gm) }.toMutableList()
    val unused = lanes.indices.filter { it !in order }.toMutableList()
    for (i in order.indices) if (order[i] < 0) order[i] = if (unused.isNotEmpty()) unused.removeAt(0) else i % lanes.size
    val lane = IntArray(16) { order[it % 8] }
    val rate = rateName(rollBeats)
    return PadLayout(
        track, Tokens.TRACK[track], IntArray(16) { notes[lane[it]] }, Array(16) { lanes[lane[it]].name }, emptySet(),
        subs = Array(16) { if (it >= 8) rate else "" }, rolls = BooleanArray(16) { it >= 8 }, rollBeats = rollBeats,
    )
}

/** The pads for the selected track as Play shows them (Seq and Sound reuse them in their drawer). */
fun currentLayout(track: Int, info: com.pastel.minibox.state.TrackInfo, p: ProjectInfo, octave: Int, rollBeats: Float): PadLayout? = when {
    info.empty -> null
    info.drums -> drumsLayout(track, info, rollBeats)
    info.play.chordOn && !info.play.arpOn -> chordsLayout(track, info, p, octave)
    else -> notesLayout(track, info, p, octave)
}
