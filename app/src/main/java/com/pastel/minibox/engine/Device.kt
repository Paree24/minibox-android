package com.pastel.minibox.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.mutableStateOf

/** The phone around the engine (PLAN.md §15.4): audio focus, the output device (Bluetooth adds a lot of latency)
    and how hot it runs. */
object Device {
    val bluetooth = mutableStateOf(false)
    val hot = mutableStateOf(false)
    private var focus: AudioFocusRequest? = null

    fun start(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        fun check() {
            bluetooth.value = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || (Build.VERSION.SDK_INT >= 31 && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        }
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = check()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = check()
        }, Handler(Looper.getMainLooper()))
        check()
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(PowerManager::class.java)
            pm.addThermalStatusListener { hot.value = it >= PowerManager.THERMAL_STATUS_MODERATE }
        }
    }

    /** Playing: ask for audio focus; losing it to another app (a call, a video) stops the transport. */
    fun requestFocus(context: Context) {
        if (focus != null) return
        val am = context.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) Native.stop()
                if (change == AudioManager.AUDIOFOCUS_LOSS) abandonFocus(context)
            }
            .build()
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focus = req
    }

    fun abandonFocus(context: Context) {
        focus?.let { context.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }
}
