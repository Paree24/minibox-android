#include "MiniboxHost.h"
#include "app/presets/PresetBank.h"
#include "devices/SampleSlot.h"
#include <android/log.h>
#include <thread>

#define MBLOG(...) __android_log_print (ANDROID_LOG_INFO, "Minibox", __VA_ARGS__)

namespace mb::android
{
MiniboxHost& MiniboxHost::get()
{
    static MiniboxHost h;
    return h;
}

void MiniboxHost::init (const juce::String& factoryDir)
{
    setFactoryDir (juce::File (factoryDir));
    presets::rescan();
    engine.prepare (48000.0, kMaxBlock);   // re-prepared at the stream's own rate when it opens

    // the starter rack on pattern A: a drum kit and three synths
    const DeviceType starter[] = { DeviceType::Drums, DeviceType::Mono, DeviceType::Acid, DeviceType::Poly };
    for (int t = 0; t < (int) std::size (starter); ++t)
    {
        auto d = createDevice (starter[t]);
        presets::loadDefault (*d, true);
        engine.setDevice (0, t, std::move (d)).reset();
    }
    engine.focusTrack = 1;
}

bool MiniboxHost::openStream()
{
    oboe::AudioStreamBuilder b;
    b.setDirection (oboe::Direction::Output)
        ->setPerformanceMode (oboe::PerformanceMode::LowLatency)
        ->setSharingMode (oboe::SharingMode::Exclusive)              // MMAP where the device allows it
        ->setFormat (oboe::AudioFormat::Float)
        ->setChannelCount (oboe::ChannelCount::Stereo)
        ->setUsage (oboe::Usage::Media)
        ->setContentType (oboe::ContentType::Music)
        ->setSampleRateConversionQuality (oboe::SampleRateConversionQuality::None)   // the device's own rate
        ->setDataCallback (this)
        ->setErrorCallback (this);

    std::shared_ptr<oboe::AudioStream> s;
    if (b.openStream (s) != oboe::Result::OK)
    {
        MBLOG ("openStream failed");
        return false;
    }
    if (std::abs (engine.getSampleRate() - s->getSampleRate()) > 0.5)
        engine.prepare ((double) s->getSampleRate(), kMaxBlock);
    s->setPerformanceHintEnabled (true);   // ADPF: clocks ramp before an underrun, not after
    stream = s;
    lastXruns = 0;
    tuneBuffer();
    MBLOG ("stream: %d Hz, burst %d, buffer %d, %s, mmap %d", s->getSampleRate(), s->getFramesPerBurst(),
           s->getBufferSizeInFrames(), s->getSharingMode() == oboe::SharingMode::Exclusive ? "exclusive" : "shared",
           (int) oboe::OboeExtensions::isMMapUsed (s.get()));
    return stream->requestStart() == oboe::Result::OK;
}

bool MiniboxHost::startAudio()
{
    std::lock_guard<std::mutex> l (streamLock);
    wantRunning = true;
    if (stream != nullptr) return stream->requestStart() == oboe::Result::OK;
    return openStream();
}

void MiniboxHost::stopAudio()
{
    std::lock_guard<std::mutex> l (streamLock);
    wantRunning = false;
    if (stream != nullptr)
    {
        stream->stop();
        stream->close();
        stream.reset();
    }
}

void MiniboxHost::setBufferBursts (int bursts)
{
    std::lock_guard<std::mutex> l (streamLock);
    bufferBursts = std::clamp (bursts, 0, 4);
    tuneBuffer();
}

void MiniboxHost::tuneBuffer()
{
    if (stream == nullptr) return;
    const int burst = stream->getFramesPerBurst();
    if (bufferBursts > 0)
    {
        stream->setBufferSizeInFrames (burst * bufferBursts);
        return;
    }
    // automatic: start at two bursts; each new underrun adds one (up to six)
    const auto x = stream->getXRunCount();
    const int xr = x ? x.value() : 0;
    int size = std::max (stream->getBufferSizeInFrames(), burst * 2);
    if (xr == 0) size = burst * 2;
    else if (xr > lastXruns) size = std::min (size + burst, burst * 6);
    lastXruns = xr;
    stream->setBufferSizeInFrames (size);
}

MiniboxHost::Stats MiniboxHost::stats()
{
    std::lock_guard<std::mutex> l (streamLock);
    Stats st;
    st.cpu = engine.cpuLoad.load();
    st.lastCallbackFrames = (float) lastFrames.load();
    st.peak = std::max (engine.master().peakL.load(), engine.master().peakR.load());
    if (stream == nullptr) return st;
    if (bufferBursts == 0) tuneBuffer();   // the UI polls this a few times a second
    st.running = stream->getState() == oboe::StreamState::Started ? 1.0f : 0.0f;
    st.sampleRate = (float) stream->getSampleRate();
    st.framesPerBurst = (float) stream->getFramesPerBurst();
    st.bufferFrames = (float) stream->getBufferSizeInFrames();
    if (auto x = stream->getXRunCount()) st.xruns = (float) x.value();
    if (auto lat = stream->calculateLatencyMillis()) st.latencyMs = (float) lat.value();
    st.exclusive = stream->getSharingMode() == oboe::SharingMode::Exclusive ? 1.0f : 0.0f;
    st.mmap = oboe::OboeExtensions::isMMapUsed (stream.get()) ? 1.0f : 0.0f;
    return st;
}

oboe::DataCallbackResult MiniboxHost::onAudioReady (oboe::AudioStream*, void* audioData, int32_t numFrames)
{
    const juce::ScopedNoDenormals noDenormals;   // flush-to-zero on arm64
    auto* out = static_cast<float*> (audioData);
    lastFrames.store (numFrames, std::memory_order_relaxed);
    for (int pos = 0; pos < numFrames; pos += kMaxBlock)
    {
        const int n = std::min (kMaxBlock, numFrames - pos);
        engine.process (left.data(), right.data(), n, nullptr);
        for (int i = 0; i < n; ++i)
        {
            out[(pos + i) * 2] = left[(size_t) i];
            out[(pos + i) * 2 + 1] = right[(size_t) i];
        }
    }
    return oboe::DataCallbackResult::Continue;
}

void MiniboxHost::onErrorAfterClose (oboe::AudioStream*, oboe::Result r)
{
    // the device went away (headphones, USB, Bluetooth): reopen on the new one; the transport keeps its place
    MBLOG ("stream closed (%s), reopening", oboe::convertToText (r));
    std::lock_guard<std::mutex> l (streamLock);
    stream.reset();
    if (wantRunning) openStream();
}
} // namespace mb::android
