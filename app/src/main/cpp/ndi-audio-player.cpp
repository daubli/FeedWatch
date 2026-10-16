#include "ndi-wrapper.h"

#include <android/log.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <ctime>
#include <memory>
#include <mutex>

namespace {

const char *TAG = "NdiAudioPlayer";

int64_t monotonicNanos() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

void raisePeak(std::atomic<float> &peak, float value) {
    float current = peak.load(std::memory_order_relaxed);
    while (value > current && !peak.compare_exchange_weak(current, value, std::memory_order_relaxed)) {
    }
}

/**
 * Plays the audio of a frame sync. The frame sync is pulled from the output's data callback for exactly the
 * number of frames the device asks for, so its resampler follows the DAC clock, as the NDI SDK intends.
 *
 * For A/V sync the callback records when it pulled which output frame; together with the stream's timestamp
 * this gives the time it takes audio from the frame sync to the speaker.
 */
class AudioPlayer : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    explicit AudioPlayer(NDIlib_framesync_instance_t framesync) : framesync(framesync) {}

    bool start() {
        std::lock_guard<std::mutex> guard(lock);
        stopped = false;
        return openLocked();
    }

    void stop() {
        std::lock_guard<std::mutex> guard(lock);
        stopped = true;
        if (stream) {
            stream->stop();
            stream->close();
            stream.reset();
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        int64_t pulledAt = monotonicNanos();
        int64_t frameIndex = audioStream->getFramesWritten();
        int32_t sampleRate = audioStream->getSampleRate();
        auto *out = static_cast<float *>(audioData);

        NDIlib_audio_frame_v2_t frame;
        NDIlib_framesync_capture_audio(framesync, &frame, sampleRate, 2, numFrames);

        float peakLeft = 0.f;
        float peakRight = 0.f;
        int32_t filled = 0;
        if (frame.p_data && frame.no_channels > 0) {
            int stride = frame.channel_stride_in_bytes > 0 ? frame.channel_stride_in_bytes / (int) sizeof(float)
                                                          : frame.no_samples;
            const float *left = frame.p_data;
            const float *right = frame.no_channels > 1 ? frame.p_data + stride : left;
            filled = std::min(numFrames, frame.no_samples);
            for (int32_t i = 0; i < filled; i++) {
                float l = fminf(fmaxf(left[i], -1.f), 1.f);
                float r = fminf(fmaxf(right[i], -1.f), 1.f);
                peakLeft = fmaxf(peakLeft, fabsf(l));
                peakRight = fmaxf(peakRight, fabsf(r));
                out[2 * i] = l;
                out[2 * i + 1] = r;
            }
        }
        NDIlib_framesync_free_audio(framesync, &frame);
        std::fill(out + 2 * filled, out + 2 * numFrames, 0.f);

        raisePeak(this->peakLeft, peakLeft);
        raisePeak(this->peakRight, peakRight);

        // seqlock: readers retry while the sequence is odd or changed
        pullSequence.fetch_add(1, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        lastPulledAt.store(pulledAt, std::memory_order_relaxed);
        lastFrameIndex.store(frameIndex, std::memory_order_relaxed);
        lastSampleRate.store(sampleRate, std::memory_order_relaxed);
        pullSequence.fetch_add(1, std::memory_order_release);

        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override {
        // e.g. headphones plugged in: Oboe has closed the stream on its own thread, reopen on the new device
        __android_log_print(ANDROID_LOG_INFO, TAG, "Stream closed: %s", oboe::convertToText(error));
        std::lock_guard<std::mutex> guard(lock);
        stream.reset();
        if (!stopped && error == oboe::Result::ErrorDisconnected) {
            openLocked();
        }
    }

    void takePeaks(float &left, float &right) {
        left = peakLeft.exchange(0.f, std::memory_order_relaxed);
        right = peakRight.exchange(0.f, std::memory_order_relaxed);
    }

    /**
     * Time from pulling audio out of the frame sync to hearing it, or -1 if the stream can't tell yet.
     */
    int64_t outputLatencyNanos() {
        int64_t pulledAt;
        int64_t frameIndex;
        int32_t sampleRate;
        for (;;) {
            uint32_t sequence = pullSequence.load(std::memory_order_acquire);
            pulledAt = lastPulledAt.load(std::memory_order_relaxed);
            frameIndex = lastFrameIndex.load(std::memory_order_relaxed);
            sampleRate = lastSampleRate.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if ((sequence & 1) == 0 && sequence == pullSequence.load(std::memory_order_acquire)) {
                break;
            }
        }
        if (sampleRate <= 0) {
            return -1;
        }

        std::lock_guard<std::mutex> guard(lock);
        if (!stream) {
            return -1;
        }
        auto timestamp = stream->getTimestamp(CLOCK_MONOTONIC);
        if (!timestamp) {
            return -1;
        }
        // when the first frame of the last pulled buffer reaches the speaker
        int64_t presentedAt = timestamp.value().timestamp
                + (frameIndex - timestamp.value().position) * 1000000000LL / sampleRate;
        return presentedAt - pulledAt;
    }

private:
    bool openLocked() {
        if (stream) {
            return true;
        }
        // No sample rate requested: the device's native rate avoids a second resampler in Android. 10 ms per
        // callback instead of the 2 ms burst: each pull makes the frame sync resample, at 500 calls/s that cost a
        // third of a core; the first frame of each callback still reaches the speaker after the same ~13 ms.
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSharingMode(oboe::SharingMode::Shared)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(oboe::ChannelCount::Stereo)
                ->setUsage(oboe::Usage::Media)
                ->setContentType(oboe::ContentType::Movie)
                ->setFramesPerDataCallback(480)
                ->setDataCallback(this)
                ->setErrorCallback(this);

        oboe::Result result = builder.openStream(stream);
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Opening stream failed: %s", oboe::convertToText(result));
            stream.reset();
            return false;
        }
        result = stream->requestStart();
        if (result != oboe::Result::OK) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "Starting stream failed: %s", oboe::convertToText(result));
            stream->close();
            stream.reset();
            return false;
        }
        __android_log_print(ANDROID_LOG_INFO, TAG, "Playing at %d Hz via %s, burst %d frames, buffer %d frames",
                stream->getSampleRate(), stream->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL ES",
                stream->getFramesPerBurst(), stream->getBufferSizeInFrames());
        return true;
    }

    const NDIlib_framesync_instance_t framesync;

    // guards stream, which the error callback replaces on its own thread
    std::mutex lock;
    std::shared_ptr<oboe::AudioStream> stream;
    bool stopped = true;

    std::atomic<float> peakLeft{0.f};
    std::atomic<float> peakRight{0.f};

    std::atomic<uint32_t> pullSequence{0};
    std::atomic<int64_t> lastPulledAt{0};
    std::atomic<int64_t> lastFrameIndex{0};
    std::atomic<int32_t> lastSampleRate{0};
};

}

extern "C" {
    JNIEXPORT jlong JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_create(JNIEnv *env, jclass jClazz, jlong pFramesync) {
        return (jlong) new AudioPlayer(reinterpret_cast<NDIlib_framesync_instance_t>(pFramesync));
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_destroy(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        auto *player = reinterpret_cast<AudioPlayer *>(pPlayer);
        player->stop();
        delete player;
    }

    JNIEXPORT jboolean JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_start(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        return reinterpret_cast<AudioPlayer *>(pPlayer)->start();
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_stop(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        reinterpret_cast<AudioPlayer *>(pPlayer)->stop();
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_takePeaks(JNIEnv *env, jclass jClazz, jlong pPlayer,
            jfloatArray jPeaks) {
        float peaks[2];
        reinterpret_cast<AudioPlayer *>(pPlayer)->takePeaks(peaks[0], peaks[1]);
        env->SetFloatArrayRegion(jPeaks, 0, 2, peaks);
    }

    JNIEXPORT jlong JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_outputLatencyNanos(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        return reinterpret_cast<AudioPlayer *>(pPlayer)->outputLatencyNanos();
    }
}
