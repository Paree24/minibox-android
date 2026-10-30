// MIDI controllers (USB and Bluetooth): Android's AMidi (API 29+), loaded at runtime so the app still installs on
// API 28. A reader thread per open port moves messages into a lock-free FIFO; the audio callback turns them into
// the engine's MIDI input (played on the selected track, as on the desktop).
#include "MiniboxHost.h"
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <thread>

#define MBLOG(...) __android_log_print (ANDROID_LOG_INFO, "Minibox", __VA_ARGS__)

namespace mb::android
{
namespace
{
struct AMidiDevice;
struct AMidiOutputPort;
using FromJava = int32_t (*) (JNIEnv*, jobject, AMidiDevice**);
using Release = int32_t (*) (const AMidiDevice*);
using OpenOut = int32_t (*) (const AMidiDevice*, int32_t, AMidiOutputPort**);
using CloseOut = void (*) (const AMidiOutputPort*);
using Receive = ssize_t (*) (const AMidiOutputPort*, int32_t*, uint8_t*, size_t, size_t*, int64_t*);

struct AMidiApi
{
    FromJava fromJava = nullptr; Release release = nullptr; OpenOut openOut = nullptr; CloseOut closeOut = nullptr; Receive receive = nullptr;
    bool ok = false;
    AMidiApi()
    {
        if (void* lib = dlopen ("libamidi.so", RTLD_NOW))
        {
            fromJava = (FromJava) dlsym (lib, "AMidiDevice_fromJava");
            release = (Release) dlsym (lib, "AMidiDevice_release");
            openOut = (OpenOut) dlsym (lib, "AMidiOutputPort_open");
            closeOut = (CloseOut) dlsym (lib, "AMidiOutputPort_close");
            receive = (Receive) dlsym (lib, "AMidiOutputPort_receive");
            ok = fromJava && release && openOut && closeOut && receive;
        }
    }
};
AMidiApi& amidi() { static AMidiApi a; return a; }
constexpr int32_t kOpcodeData = 1;   // AMIDI_OPCODE_DATA
} // namespace

struct MiniboxHost::MidiPort
{
    AMidiDevice* device = nullptr;
    AMidiOutputPort* port = nullptr;
    std::atomic<bool> running { true };
    std::thread reader;
};

MiniboxHost::MiniboxHost() = default;
MiniboxHost::~MiniboxHost() { closeMidi(); }

bool MiniboxHost::openMidi (void* env, void* midiDevice, int portNumber)
{
    auto& api = amidi();
    if (! api.ok) return false;
    auto mp = std::make_unique<MidiPort>();
    if (api.fromJava (static_cast<JNIEnv*> (env), static_cast<jobject> (midiDevice), &mp->device) != 0) return false;
    if (api.openOut (mp->device, portNumber, &mp->port) != 0) { api.release (mp->device); return false; }
    auto* raw = mp.get();
    mp->reader = std::thread ([this, raw]
    {
        uint8_t buf[256];
        while (raw->running.load())
        {
            int32_t opcode = 0; size_t n = 0; int64_t ts = 0;
            const auto got = amidi().receive (raw->port, &opcode, buf, sizeof (buf), &n, &ts);
            if (got <= 0) { std::this_thread::sleep_for (std::chrono::microseconds (500)); continue; }
            if (opcode != kOpcodeData) continue;
            pushMidiBytes (buf, (int) n);
        }
    });
    std::lock_guard<std::mutex> l (midiPortsLock);
    midiPorts.push_back (std::move (mp));
    MBLOG ("MIDI port %d open", portNumber);
    return true;
}

void MiniboxHost::closeMidi()
{
    std::lock_guard<std::mutex> l (midiPortsLock);
    for (auto& mp : midiPorts)
    {
        mp->running = false;
        if (mp->reader.joinable()) mp->reader.join();
        amidi().closeOut (mp->port);
        amidi().release (mp->device);
    }
    midiPorts.clear();
}

/** Reader thread: whole messages (status + data, running status resolved) into the FIFO. SysEx is skipped. */
void MiniboxHost::pushMidiBytes (const uint8_t* b, int n)
{
    for (int i = 0; i < n; ++i)
    {
        const uint8_t x = b[i];
        if (x >= 0xF8) continue;                                     // real-time: clock, start, stop (later)
        if (x & 0x80) { midiStatus = x; midiHave = 0; if (x >= 0xF0) midiStatus = 0; continue; }
        if (midiStatus == 0) continue;
        midiData[midiHave++] = x;
        const int need = ((midiStatus & 0xF0) == 0xC0 || (midiStatus & 0xF0) == 0xD0) ? 1 : 2;
        if (midiHave < need) continue;
        int s1, n1, s2, n2;
        midiFifo.prepareToWrite (1, s1, n1, s2, n2);
        if (n1 + n2 > 0)
            midiQueue[(size_t) (n1 > 0 ? s1 : s2)] = { midiStatus, midiData[0], need > 1 ? midiData[1] : (uint8_t) 0, (uint8_t) need };
        midiFifo.finishedWrite (n1 + n2 > 0 ? 1 : 0);
        midiHave = 0;   // running status: the next data bytes reuse the status
    }
}

/** Audio thread: this callback's MIDI, at the start of the block (it arrived since the last one). */
const juce::MidiBuffer* MiniboxHost::takeMidi()
{
    const int ready = midiFifo.getNumReady();
    if (ready == 0) return nullptr;
    midiBlock.clear();
    int s1, n1, s2, n2;
    midiFifo.prepareToRead (ready, s1, n1, s2, n2);
    auto add = [&] (int start, int count)
    {
        for (int i = 0; i < count; ++i)
        {
            const auto& m = midiQueue[(size_t) (start + i)];
            const uint8_t bytes[3] = { m.status, m.d1, m.d2 };
            midiBlock.addEvent (bytes, 1 + m.size, 0);
        }
    };
    add (s1, n1); add (s2, n2);
    midiFifo.finishedRead (n1 + n2);
    return &midiBlock;
}
} // namespace mb::android
