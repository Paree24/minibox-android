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
import com.pastel.minibox.engine.Native
import com.pastel.minibox.ui.C
import com.pastel.minibox.ui.PlayScreen

class MainActivity : ComponentActivity() {
    private val ready = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            if (ready.value) PlayScreen() else Box(Modifier.fillMaxSize().background(C.bg))
        }
        // unpack the factory content off the main thread, then bring the engine up on it
        Thread {
            val factory = Factory.unpack(applicationContext)
            runOnUiThread {
                Native.nativeInit(applicationContext, factory.absolutePath)
                ready.value = true
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) Native.startAudio()
            }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        if (ready.value) Native.startAudio()
    }

    override fun onStop() {
        // background playback (a foreground service) comes in phase 7; until then the stream stops with the app
        if (ready.value) Native.stopAudio()
        super.onStop()
    }
}
