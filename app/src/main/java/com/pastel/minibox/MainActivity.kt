package com.pastel.minibox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.pastel.minibox.engine.Factory
import com.pastel.minibox.engine.Device
import com.pastel.minibox.engine.Midi
import com.pastel.minibox.engine.Native
import com.pastel.minibox.engine.Prefs
import com.pastel.minibox.ui.C
import com.pastel.minibox.state.App
import com.pastel.minibox.ui.MainScreen

class MainActivity : ComponentActivity() {
    private val ready = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // debug builds: `adb shell am broadcast -a com.pastel.minibox.CAPTURE --ef seconds 12` records the engine's
        // output; `--es path <file>` writes it as a WAV (how the phone's sound is checked without a microphone)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            androidx.core.content.ContextCompat.registerReceiver(this, object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    if (i.hasExtra("seconds")) com.pastel.minibox.state.App.call("capture", "seconds" to i.getFloatExtra("seconds", 10f).toDouble())
                    i.getStringExtra("path")?.let { com.pastel.minibox.state.App.call("capture", "path" to it) }
                    // `--es op <op> --es args '<json>'`: any engine call, then the UI refreshes
                    i.getStringExtra("op")?.let { op ->
                        val args = org.json.JSONObject(i.getStringExtra("args") ?: "{}")
                        android.util.Log.i("Minibox", "debug call $op -> " + com.pastel.minibox.state.App.call(op, *args.keys().asSequence().map { it to args.get(it) }.toList().toTypedArray()).take(300))
                        com.pastel.minibox.state.App.refresh(); com.pastel.minibox.state.Sound.refresh()
                    }
                }
            }, android.content.IntentFilter("com.pastel.minibox.CAPTURE"), androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        // pad touches as soon as they are read, not batched to the next frame (PLAN.md §3.3); Android 11+
        if (android.os.Build.VERSION.SDK_INT >= 30) window.decorView.requestUnbufferedDispatch(android.view.InputDevice.SOURCE_CLASS_POINTER)
        setContent {
            if (ready.value) MainScreen() else Box(Modifier.fillMaxSize().background(C.bg))
        }
        // unpack the factory content off the main thread, then bring the engine up on it
        Thread {
            val factory = Factory.unpack(applicationContext)
            runOnUiThread {
                Native.nativeInit(applicationContext, factory.absolutePath, filesDir.absolutePath)
                Native.setLatencyMode(Prefs.latencyMode(this), Prefs.lowBursts(this))
                Prefs.lastProject(this)?.let { App.call("openProject", "name" to it) }   // carry on where we were
                App.init()
                Midi.start(applicationContext)
                Device.start(applicationContext)
                ready.value = true
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) Native.startAudio()
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        PlaybackService.stop(this)   // back in front: the app holds the audio again
        if (ready.value) Native.startAudio()
    }

    override fun onStop() {
        // Home while playing: keep going in the background; Back (closing the app) stops it (onDestroy)
        if (ready.value && App.fast.value.playing && !isFinishing) {
            // playing: keep going in the background, with a notification that can stop it
            App.autosave(this)
            PlaybackService.start(this)
            super.onStop()
            return
        }
        if (ready.value) {
            Device.abandonFocus(this)
            // remember where Low settled on this phone (it starts there next time, without the first clicks)
            val st = FloatArray(11).also { Native.readStats(it) }
            if (Prefs.latencyMode(this) == 0 && st[1] > 0) Prefs.setLowBursts(this, (st[2] / st[1]).toInt().coerceIn(1, 8))
            Native.stopAudio()
            App.autosave(this)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && ready.value) {   // closed with Back: the music stops with the app
            Native.stop(); Native.stop()
            Native.stopAudio()
            PlaybackService.stop(this)
        }
        super.onDestroy()
    }
}
