#include <jni.h>
#ifdef __ANDROID__
#include <android/log.h>
#include <sys/resource.h>
#include <unistd.h>
#else
#include <cstdarg>
#include <cstdio>
constexpr int ANDROID_LOG_INFO = 4;
static int __android_log_print(int, const char* tag, const char* format, ...) {
    std::fprintf(stderr, "%s: ", tag);
    va_list args;
    va_start(args, format);
    const int result = std::vfprintf(stderr, format, args);
    va_end(args);
    std::fputc('\n', stderr);
    return result;
}
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "nuked/backend/audio.h"
#include "nuked/backend/emu.h"
#include "nuked/backend/pcm.h"
#include "nuked/common/rom_loader.h"

namespace {
constexpr const char* TAG = "NukedSC55";
constexpr size_t RING_FRAMES = 4096;
constexpr size_t RING_MASK = RING_FRAMES - 1;
constexpr uint64_t HIGH_WATER_FRAMES = 384;
constexpr uint64_t LOW_WATER_FRAMES = 192;

struct Engine {
    std::unique_ptr<common::LoadRomsetResult> roms;
    std::unique_ptr<Emulator> emulator;

    std::array<int16_t, RING_FRAMES * 2> audio{};
    std::atomic<uint64_t> readFrame{0};
    std::atomic<uint64_t> writeFrame{0};
    std::atomic<bool> running{false};
    std::thread worker;
    std::mutex wakeMutex;
    std::condition_variable wake;

    std::mutex midiMutex;
    std::deque<uint8_t> midiQueue;
    std::atomic<bool> midiWaiting{false};
    std::atomic<int> pendingReset{-1};

    uint32_t sourceRate = 0;
    std::atomic<uint32_t> outputRate{0};
    double resamplePosition = 0.0;
    std::atomic<uint64_t> underruns{0};

    ~Engine() { Stop(); }

    void Start() {
        running.store(true, std::memory_order_release);
        worker = std::thread([this] { Run(); });
    }

    void Stop() {
        if (!running.exchange(false, std::memory_order_acq_rel)) return;
        wake.notify_all();
        if (worker.joinable()) worker.join();
    }

    void QueueMidi(const uint8_t* bytes, size_t count) {
        if (!bytes || count == 0) return;
        {
            std::lock_guard lock(midiMutex);
            // Active sensing can otherwise grow without bound if a broken sender floods us.
            constexpr size_t MAX_MIDI_BYTES = 65536;
            const size_t accepted = std::min(count, MAX_MIDI_BYTES - std::min(midiQueue.size(), MAX_MIDI_BYTES));
            midiQueue.insert(midiQueue.end(), bytes, bytes + accepted);
        }
        midiWaiting.store(true, std::memory_order_release);
        wake.notify_one();
    }

    void QueueReset(int reset) {
        pendingReset.store(reset, std::memory_order_release);
        wake.notify_one();
    }

    void DrainMidi() {
        const int reset = pendingReset.exchange(-1, std::memory_order_acq_rel);
        if (reset >= 0) {
            emulator->PostSystemReset(reset == 1 ? EMU_SystemReset::GS_RESET : EMU_SystemReset::GM_RESET);
        }
        if (!midiWaiting.exchange(false, std::memory_order_acq_rel)) return;
        std::deque<uint8_t> pending;
        {
            std::lock_guard lock(midiMutex);
            pending.swap(midiQueue);
        }
        for (uint8_t byte : pending) emulator->PostMIDI(byte);
    }

    static void ReceiveSample(void* userdata, const AudioFrame<int32_t>& frame) {
        auto& engine = *static_cast<Engine*>(userdata);
        const uint64_t write = engine.writeFrame.load(std::memory_order_relaxed);
        const uint64_t read = engine.readFrame.load(std::memory_order_acquire);
        if (write - read >= RING_FRAMES) return;

        AudioFrame<int16_t> normalized{};
        Normalize(frame, normalized);
        const size_t slot = static_cast<size_t>(write & RING_MASK) * 2;
        engine.audio[slot] = normalized.left;
        engine.audio[slot + 1] = normalized.right;
        engine.writeFrame.store(write + 1, std::memory_order_release);
        if ((write & 31U) == 0) engine.wake.notify_one();
    }

    void Run() {
#ifdef __ANDROID__
        // A dedicated urgent-audio producer avoids Java GC and Binder scheduling jitter.
        setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), -16);
#endif
        while (running.load(std::memory_order_acquire)) {
            DrainMidi();
            const uint64_t read = readFrame.load(std::memory_order_acquire);
            const uint64_t write = writeFrame.load(std::memory_order_acquire);
            if (write - read >= HIGH_WATER_FRAMES) {
                std::unique_lock lock(wakeMutex);
                wake.wait_for(lock, std::chrono::milliseconds(2), [&] {
                    const uint64_t queued = writeFrame.load(std::memory_order_acquire)
                            - readFrame.load(std::memory_order_acquire);
                    return !running.load(std::memory_order_acquire)
                            || midiWaiting.load(std::memory_order_acquire)
                            || pendingReset.load(std::memory_order_acquire) >= 0
                            || queued <= LOW_WATER_FRAMES;
                });
                continue;
            }
            emulator->Step();
        }
    }

    bool WaitForSourceFrame(uint64_t index, std::chrono::steady_clock::time_point deadline) {
        while (writeFrame.load(std::memory_order_acquire) <= index) {
            if (!running.load(std::memory_order_acquire)) return false;
            std::unique_lock lock(wakeMutex);
            if (wake.wait_until(lock, deadline) == std::cv_status::timeout) {
                return writeFrame.load(std::memory_order_acquire) > index;
            }
        }
        return true;
    }

    int Render(int16_t* output, int frames) {
        if (!output || frames <= 0) return 0;
        const uint32_t targetRate = std::max<uint32_t>(1, outputRate.load(std::memory_order_acquire));
        const double step = static_cast<double>(sourceRate) / static_cast<double>(targetRate);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(12);
        int produced = 0;

        for (; produced < frames; ++produced) {
            const uint64_t base = static_cast<uint64_t>(resamplePosition);
            if (!WaitForSourceFrame(base + 1, deadline)) break;
            const double fraction = resamplePosition - static_cast<double>(base);
            const size_t a = static_cast<size_t>(base & RING_MASK) * 2;
            const size_t b = static_cast<size_t>((base + 1) & RING_MASK) * 2;

            for (int channel = 0; channel < 2; ++channel) {
                const double value = static_cast<double>(audio[a + channel])
                        + (static_cast<double>(audio[b + channel]) - static_cast<double>(audio[a + channel]))
                        * fraction;
                output[produced * 2 + channel] = static_cast<int16_t>(
                        std::clamp(std::lrint(value), static_cast<long>(INT16_MIN), static_cast<long>(INT16_MAX)));
            }
            resamplePosition += step;
            readFrame.store(static_cast<uint64_t>(resamplePosition), std::memory_order_release);
        }

        if (produced < frames) {
            std::fill(output + produced * 2, output + frames * 2, 0);
            underruns.fetch_add(1, std::memory_order_relaxed);
        }
        wake.notify_one();
        return frames;
    }
};

std::mutex g_engineMutex;
std::shared_ptr<Engine> g_engine;

std::shared_ptr<Engine> GetEngine() {
    std::lock_guard lock(g_engineMutex);
    return g_engine;
}

void ReplaceEngine(std::shared_ptr<Engine> replacement) {
    std::shared_ptr<Engine> previous;
    {
        std::lock_guard lock(g_engineMutex);
        previous.swap(g_engine);
        g_engine = std::move(replacement);
    }
    if (previous) previous->Stop();
}

std::string fromJString(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_nukedsc55_android_NativeBridge_load(
        JNIEnv* env, jclass, jstring directory, jstring model, jboolean oversampling) {
    ReplaceEngine(nullptr);
    auto engine = std::make_shared<Engine>();
    engine->roms = std::make_unique<common::LoadRomsetResult>();

    const std::string romDirectory = fromJString(env, directory);
    const std::string requestedModel = fromJString(env, model);
    common::RomOverrides overrides{};
    const auto error = common::LoadRomset(
            romDirectory, requestedModel, common::RomLoader::Hashing, overrides, *engine->roms);
    if (error != common::LoadRomsetError{}) {
        return toJString(env, std::string(common::ToCString(error)) +
                ". 请确认已导入该型号的一套完整原始 ROM。目录: " + romDirectory);
    }

    engine->emulator = std::make_unique<Emulator>();
    if (!engine->emulator->Init({})) return toJString(env, "无法分配仿真器内存");
    if (!engine->emulator->LoadRoms(engine->roms->romset, engine->roms->romset_info)) {
        return toJString(env, "ROM 已识别，但载入仿真核心失败");
    }

    engine->emulator->Reset();
    engine->emulator->GetPCM().enable_oversampling = oversampling == JNI_TRUE;
    engine->sourceRate = PCM_GetOutputFrequency(engine->emulator->GetPCM());
    engine->outputRate.store(engine->sourceRate, std::memory_order_release);
    engine->emulator->SetSampleCallback(Engine::ReceiveSample, engine.get());
    ReplaceEngine(engine);
    engine->Start();
    __android_log_print(ANDROID_LOG_INFO, TAG, "Loaded %s at %u Hz",
            engine->roms->picked_name.c_str(), engine->sourceRate);
    return toJString(env, "");
}

extern "C" JNIEXPORT void JNICALL
Java_com_nukedsc55_android_NativeBridge_unload(JNIEnv*, jclass) {
    ReplaceEngine(nullptr);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nukedsc55_android_NativeBridge_sampleRate(JNIEnv*, jclass) {
    auto engine = GetEngine();
    return engine ? static_cast<jint>(engine->sourceRate) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nukedsc55_android_NativeBridge_setOutputSampleRate(JNIEnv*, jclass, jint sampleRate) {
    auto engine = GetEngine();
    if (engine && sampleRate >= 8000 && sampleRate <= 192000) {
        engine->outputRate.store(static_cast<uint32_t>(sampleRate), std::memory_order_release);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nukedsc55_android_NativeBridge_render(JNIEnv* env, jclass, jshortArray output, jint frames) {
    if (!output || frames <= 0) return 0;
    const int requested = std::min<int>(frames, env->GetArrayLength(output) / 2);
    jshort* samples = env->GetShortArrayElements(output, nullptr);
    if (!samples) return 0;
    auto engine = GetEngine();
    const int rendered = engine ? engine->Render(samples, requested) : 0;
    if (!engine) std::fill(samples, samples + requested * 2, 0);
    env->ReleaseShortArrayElements(output, samples, 0);
    return rendered;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nukedsc55_android_NativeBridge_sendMidi(
        JNIEnv* env, jclass, jbyteArray data, jint offset, jint count) {
    if (!data || offset < 0 || count <= 0 || offset + count > env->GetArrayLength(data)) return;
    std::vector<uint8_t> bytes(static_cast<size_t>(count));
    env->GetByteArrayRegion(data, offset, count, reinterpret_cast<jbyte*>(bytes.data()));
    auto engine = GetEngine();
    if (engine) engine->QueueMidi(bytes.data(), bytes.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_nukedsc55_android_NativeBridge_sendShortMidi(
        JNIEnv*, jclass, jint status, jint data1, jint data2) {
    const uint8_t bytes[] = {
            static_cast<uint8_t>(status), static_cast<uint8_t>(data1), static_cast<uint8_t>(data2)};
    const uint8_t command = static_cast<uint8_t>(status) & 0xF0U;
    const size_t length = (command == 0xC0U || command == 0xD0U) ? 2U : 3U;
    auto engine = GetEngine();
    if (engine) engine->QueueMidi(bytes, length);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nukedsc55_android_NativeBridge_reset(JNIEnv*, jclass, jint type) {
    auto engine = GetEngine();
    if (engine) engine->QueueReset(type == 1 ? 1 : 0);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nukedsc55_android_NativeBridge_underrunCount(JNIEnv*, jclass) {
    auto engine = GetEngine();
    return engine ? static_cast<jlong>(engine->underruns.load(std::memory_order_relaxed)) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nukedsc55_android_NativeBridge_isLoaded(JNIEnv*, jclass) {
    return GetEngine() ? JNI_TRUE : JNI_FALSE;
}
