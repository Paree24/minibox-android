package com.pastel.minibox.engine

import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

/** MIDI controllers over USB (and Bluetooth once paired in the system settings): every device that sends MIDI is
    opened and plays the selected track, as on the desktop. Needs Android 10 (AMidi); earlier phones show none. */
object Midi {
    val devices = mutableStateOf(listOf<String>())
    private var manager: MidiManager? = null
    private val open = mutableListOf<android.media.midi.MidiDevice>()

    fun start(context: Context) {
        if (Build.VERSION.SDK_INT < 29 || !context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) return
        val m = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager ?: return
        manager = m
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) = reopen()
            override fun onDeviceRemoved(device: MidiDeviceInfo) = reopen()
        }, Handler(Looper.getMainLooper()))
        reopen()
    }

    private fun reopen() {
        val m = manager ?: return
        Native.midiCloseAll()
        open.forEach { runCatching { it.close() } }
        open.clear()
        @Suppress("DEPRECATION")
        val infos = m.devices.filter { it.outputPortCount > 0 }
        devices.value = infos.map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device" }
        for (info in infos) m.openDevice(info, { dev ->
            if (dev != null) {
                open += dev
                for (p in 0 until info.outputPortCount) Native.midiOpen(dev, p)
            }
        }, Handler(Looper.getMainLooper()))
    }
}
