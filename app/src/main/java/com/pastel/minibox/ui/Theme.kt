package com.pastel.minibox.ui

import androidx.compose.ui.graphics.Color

/** Theme tokens (pastel-minibox Source/ui/Theme.h, mockups/SPEC.md). Keep in step with Theme.h. */
object Tokens {
    const val BG = 0xFF141518.toInt()
    const val SURFACE = 0xFF20232A.toInt()
    const val CONTROL = 0xFF262930.toInt()
    const val DIVIDER = 0xFF2A2D34.toInt()
    const val TEXT = 0xFFECE8E3.toInt()
    const val QUIET = 0xFFA8A49E.toInt()
    const val FAINT = 0xFF55585E.toInt()
    const val PATTERN = 0xFFB9A7F0.toInt()
    const val RECORD = 0xFFE88A8A.toInt()
    const val MUTE = 0xFFF2B48F.toInt()
    val TRACK = intArrayOf(
        0xFF9EB9F3.toInt(), 0xFF8FD0C2.toInt(), 0xFFE8B86A.toInt(), 0xFFE9A3B8.toInt(),
        0xFFA8D58A.toInt(), 0xFFF0A07C.toInt(), 0xFF7FC6E8.toInt(), 0xFFD7A6E8.toInt(),
    )
}

/** The same tokens as Compose colours. */
object C {
    val bg = Color(Tokens.BG)
    val surface = Color(Tokens.SURFACE)
    val control = Color(Tokens.CONTROL)
    val divider = Color(Tokens.DIVIDER)
    val text = Color(Tokens.TEXT)
    val quiet = Color(Tokens.QUIET)
    val faint = Color(Tokens.FAINT)
    val pattern = Color(Tokens.PATTERN)
    val record = Color(Tokens.RECORD)
    fun track(i: Int) = Color(Tokens.TRACK[i.coerceIn(0, 7)])
}
