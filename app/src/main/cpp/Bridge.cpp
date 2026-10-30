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

// ---- regular natives
jboolean startAudio (JNIEnv*, jclass) { return MiniboxHost::get().startAudio(); }
void stopAudio (JNIEnv*, jclass) { MiniboxHost::get().stopAudio(); }
void play (JNIEnv*, jclass) { MiniboxHost::get().play(); }
void stop (JNIEnv*, jclass) { MiniboxHost::get().stop(); }
void setBufferBursts (JNIEnv*, jclass, jint b) { MiniboxHost::get().setBufferBursts (b); }

void readStats (JNIEnv* env, jclass, jfloatArray out)
{
    const auto s = MiniboxHost::get().stats();
    const float v[] = { s.sampleRate, s.framesPerBurst, s.bufferFrames, s.latencyMs, s.xruns,
                        s.mmap, s.exclusive, s.cpu, s.lastCallbackFrames, s.running };
    env->SetFloatArrayRegion (out, 0, std::min<jsize> ((jsize) std::size (v), env->GetArrayLength (out)), v);
}

void runPosted (JNIEnv*, jclass, jlong ptr)
{
    std::unique_ptr<std::function<void()>> fn (reinterpret_cast<std::function<void()>*> ((intptr_t) ptr));
    (*fn)();
}
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pastel_minibox_engine_Native_nativeInit (JNIEnv* env, jclass cls, jobject context, jstring factoryDir)
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
        { "startAudio", "()Z", reinterpret_cast<void*> (startAudio) },
        { "stopAudio", "()V", reinterpret_cast<void*> (stopAudio) },
        { "play", "()V", reinterpret_cast<void*> (play) },
        { "stop", "()V", reinterpret_cast<void*> (stop) },
        { "setBufferBursts", "(I)V", reinterpret_cast<void*> (setBufferBursts) },
        { "readStats", "([F)V", reinterpret_cast<void*> (readStats) },
        { "runPosted", "(J)V", reinterpret_cast<void*> (runPosted) },
    };
    if (env->RegisterNatives (cls, methods, (jint) std::size (methods)) != JNI_OK) return JNI_FALSE;

    mb::setMainThreadPoster (postToMain);
    MiniboxHost::get().init (toJuce (env, factoryDir));
    return JNI_TRUE;
}
