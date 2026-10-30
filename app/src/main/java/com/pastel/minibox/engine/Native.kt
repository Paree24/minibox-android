package com.pastel.minibox.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import dalvik.annotation.optimization.CriticalNative

/** The C++ engine (app/src/main/cpp/Bridge.cpp). Every method but nativeInit is registered by nativeInit. */
object Native {
    init { System.loadLibrary("minibox") }

    @JvmStatic external fun nativeInit(context: Context, factoryDir: String): Boolean

    // pads: no JNIEnv, no objects (PLAN.md §3.3); main thread only
    @CriticalNative @JvmStatic external fun noteOn(track: Int, note: Int, velocity: Float)
    @CriticalNative @JvmStatic external fun noteOff(track: Int, note: Int)
    @CriticalNative @JvmStatic external fun selectTrack(track: Int)

    @JvmStatic external fun startAudio(): Boolean
    @JvmStatic external fun stopAudio()
    @JvmStatic external fun play()
    @JvmStatic external fun stop()
    @JvmStatic external fun setBufferBursts(bursts: Int)
    @JvmStatic external fun readStats(out: FloatArray)
    @JvmStatic private external fun runPosted(ptr: Long)

    private val main = Handler(Looper.getMainLooper())

    /** Called from C++ worker threads: run a finished job on the main thread. */
    @JvmStatic fun post(ptr: Long) { main.post { runPosted(ptr) } }
}

/** What readStats fills in, in order. */
data class AudioStats(
    val sampleRate: Int = 0, val burst: Int = 0, val bufferFrames: Int = 0, val latencyMs: Float = -1f,
    val xruns: Int = 0, val mmap: Boolean = false, val exclusive: Boolean = false, val cpu: Float = 0f,
    val callbackFrames: Int = 0, val running: Boolean = false,
) {
    companion object {
        fun from(v: FloatArray) = AudioStats(
            v[0].toInt(), v[1].toInt(), v[2].toInt(), v[3], v[4].toInt(), v[5] > 0f, v[6] > 0f, v[7], v[8].toInt(), v[9] > 0f)
    }
}
