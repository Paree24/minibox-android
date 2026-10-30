// JNI bridge: com.pastel.minibox.engine.Native
//
// The pad calls (noteOn / noteOff / selectTrack) are @CriticalNative: no JNIEnv, no objects, just a push into the
// engine's lock-free FIFO (PLAN.md §3.3). Critical natives must be registered explicitly, so nativeInit registers
// every method; JUCE's juce_core already defines JNI_OnLoad, which is why this file does not.
#define JUCE_CORE_INCLUDE_JNI_HELPERS 1   // juce::JNIClassBase, to start JUCE inside a non-JUCE app
#include "MiniboxHost.h"
#include "devices/SampleSlot.h"
#include <jni.h>

using mb::android::MiniboxHost;

namespace
{
JavaVM* vm = nullptr;
jclass nativeClass = nullptr;
jmethodID postMethod = nullptr;

JNIEnv* attachedEnv()
{
    JNIEnv* env = nullptr;
    if (vm->GetEnv (reinterpret_cast<void**> (&env), JNI_VERSION_1_6) == JNI_EDETACHED)
        vm->AttachCurrentThread (&env, nullptr);
    return env;
}

/** Background sample loads finish on a worker: hand the result to the main thread through Native.post(). */
void postToMain (std::function<void()> fn)
{
    auto* env = attachedEnv();
    auto* boxed = new std::function<void()> (std::move (fn));
    env->CallStaticVoidMethod (nativeClass, postMethod, (jlong) reinterpret_cast<intptr_t> (boxed));
}

juce::String toJuce (JNIEnv* env, jstring s)
{
    const char* c = env->GetStringUTFChars (s, nullptr);
    juce::String r = juce::String::fromUTF8 (c);
    env->ReleaseStringUTFChars (s, c);
    return r;
}

// ---- @CriticalNative (no env, no class)
void criticalNoteOn (jint track, jint note, jfloat vel) { MiniboxHost::get().noteOn (track, note, vel); }
void criticalNoteOff (jint track, jint note) { MiniboxHost::get().noteOff (track, note); }
void criticalSelectTrack (jint track) { MiniboxHost::get().selectTrack (track); }
void criticalRoll (jint track, jint note, jfloat vel, jfloat beats, jboolean on) { MiniboxHost::get().roll (track, note, vel, beats, on); }
void criticalFxHold (jint pad, jboolean on) { MiniboxHost::get().fxHold (pad, on); }

// ---- regular natives
jboolean startAudio (JNIEnv*, jclass) { return MiniboxHost::get().startAudio(); }
void stopAudio (JNIEnv*, jclass) { MiniboxHost::get().stopAudio(); }
void play (JNIEnv*, jclass) { MiniboxHost::get().play(); }
void stop (JNIEnv*, jclass) { MiniboxHost::get().stop(); }
void setLatencyMode (JNIEnv*, jclass, jint m, jint lowBursts) { MiniboxHost::get().setLatencyMode (m, lowBursts); }

void readStats (JNIEnv* env, jclass, jfloatArray out)
{
    const auto s = MiniboxHost::get().stats();
    const float v[] = { s.sampleRate, s.framesPerBurst, s.bufferFrames, s.latencyMs, s.xruns,
                        s.mmap, s.exclusive, s.cpu, s.lastCallbackFrames, s.running, s.peak };
    env->SetFloatArrayRegion (out, 0, std::min<jsize> ((jsize) std::size (v), env->GetArrayLength (out)), v);
}

/** A drum device's parts: their names and the notes that play them (empty for a synth or an empty slot). */
jintArray laneNotes (JNIEnv* env, jclass, jint track)
{
    auto* d = MiniboxHost::get().getEngine().device (0, track);
    const int n = d != nullptr ? d->laneCount() : 0;
    std::vector<jint> notes ((size_t) n);
    for (int l = 0; l < n; ++l) notes[(size_t) l] = d->laneNote (l);
    auto arr = env->NewIntArray (n);
    env->SetIntArrayRegion (arr, 0, n, notes.data());
    return arr;
}

jstring laneName (JNIEnv* env, jclass, jint track, jint lane)
{
    auto* d = MiniboxHost::get().getEngine().device (0, track);
    return env->NewStringUTF (d != nullptr ? d->laneName (lane).toRawUTF8() : "");
}

void readFast (JNIEnv* env, jclass, jfloatArray out)
{
    float v[MiniboxHost::kFastSize];
    MiniboxHost::get().readFast (v);
    env->SetFloatArrayRegion (out, 0, std::min<jsize> (MiniboxHost::kFastSize, env->GetArrayLength (out)), v);
}

/** call (op, json) → json: the UI's commands and queries (Api.cpp). */
jstring call (JNIEnv* env, jclass, jstring op, jstring args)
{
    const auto parsed = juce::JSON::parse (toJuce (env, args));
    const auto result = MiniboxHost::get().call (toJuce (env, op), parsed);
    return env->NewStringUTF (juce::JSON::toString (result, true).toRawUTF8());
}

jboolean midiOpen (JNIEnv* env, jclass, jobject device, jint port) { return MiniboxHost::get().openMidi (env, device, port); }
void midiCloseAll (JNIEnv*, jclass) { MiniboxHost::get().closeMidi(); }

void runPosted (JNIEnv*, jclass, jlong ptr)
{
    std::unique_ptr<std::function<void()>> fn (reinterpret_cast<std::function<void()>*> ((intptr_t) ptr));
    (*fn)();
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pastel_minibox_engine_Native_nativeInit (JNIEnv* env, jclass cls, jobject context, jstring factoryDir, jstring storageDir)
{
    if (vm != nullptr) return JNI_TRUE;   // already up (an Activity was recreated)
    env->GetJavaVM (&vm);
    nativeClass = static_cast<jclass> (env->NewGlobalRef (cls));
    postMethod = env->GetStaticMethodID (cls, "post", "(J)V");

    juce::JNIClassBase::initialiseAllClasses (env, context);
    juce::Thread::initialiseJUCE (env, context);

    static const JNINativeMethod methods[] = {
        { "noteOn", "(IIF)V", reinterpret_cast<void*> (criticalNoteOn) },
        { "noteOff", "(II)V", reinterpret_cast<void*> (criticalNoteOff) },
        { "selectTrack", "(I)V", reinterpret_cast<void*> (criticalSelectTrack) },
        { "roll", "(IIFFZ)V", reinterpret_cast<void*> (criticalRoll) },
        { "fxHold", "(IZ)V", reinterpret_cast<void*> (criticalFxHold) },
        { "readFast", "([F)V", reinterpret_cast<void*> (readFast) },
        { "call", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", reinterpret_cast<void*> (call) },
        { "startAudio", "()Z", reinterpret_cast<void*> (startAudio) },
        { "stopAudio", "()V", reinterpret_cast<void*> (stopAudio) },
        { "play", "()V", reinterpret_cast<void*> (play) },
        { "stop", "()V", reinterpret_cast<void*> (stop) },
        { "setLatencyMode", "(II)V", reinterpret_cast<void*> (setLatencyMode) },
        { "readStats", "([F)V", reinterpret_cast<void*> (readStats) },
        { "runPosted", "(J)V", reinterpret_cast<void*> (runPosted) },
        { "laneNotes", "(I)[I", reinterpret_cast<void*> (laneNotes) },
        { "midiOpen", "(Landroid/media/midi/MidiDevice;I)Z", reinterpret_cast<void*> (midiOpen) },
        { "midiCloseAll", "()V", reinterpret_cast<void*> (midiCloseAll) },
        { "laneName", "(II)Ljava/lang/String;", reinterpret_cast<void*> (laneName) },
    };
    if (env->RegisterNatives (cls, methods, (jint) std::size (methods)) != JNI_OK) return JNI_FALSE;

    mb::setMainThreadPoster (postToMain);
    MiniboxHost::get().init (toJuce (env, factoryDir), toJuce (env, storageDir));
    return JNI_TRUE;
}
