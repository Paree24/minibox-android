package com.pastel.minibox.engine

import android.content.Context

/** What the phone remembers between launches: the latency mode and the project last worked on. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("minibox", Context.MODE_PRIVATE)
    fun latencyMode(c: Context) = p(c).getInt("latencyMode", 0)
    fun setLatencyMode(c: Context, m: Int) = p(c).edit().putInt("latencyMode", m).apply()
    fun lowBursts(c: Context) = p(c).getInt("lowBursts", 1)
    fun setLowBursts(c: Context, b: Int) = p(c).edit().putInt("lowBursts", b).apply()
    fun lastProject(c: Context): String? = p(c).getString("lastProject", null)
    fun setLastProject(c: Context, name: String?) = p(c).edit().putString("lastProject", name).apply()
}
