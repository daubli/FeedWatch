#include "ndi-wrapper.h"

#include <android/log.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <ctime>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>

namespace {

const char *TAG = "NdiAudioPlayer";

// Jitter buffer: playback starts once this much audio is buffered, so packets held back by Wi-Fi for a moment do not
// empty it. Each time it runs empty anyway the target grows, up to a limit. With video the limit is low: the video
// waits for its audio, and the frames pending for it are limited. A source without video may buffer more: a Mac
// sending over Wi-Fi holds packets back for up to ~300 ms.
const int32_t BUFFER_INITIAL_MS = 100;
const int32_t BUFFER_STEP_MS = 50;
const int32_t BUFFER_MAX_MS = 500;
const int32_t BUFFER_MAX_WITH_VIDEO_MS = 200;

// On a steady connection the target shrinks again: after a window without running empty, if the deepest dip of the
// level in it plus a margin also fits into the smaller target. Wi-Fi gaps come in rarer, larger ones too: if the
// buffer runs empty within two windows after shrinking, the window doubles, up to the maximum.
const int32_t BUFFER_MIN_MS = 50;
const int64_t BUFFER_SHRINK_WINDOW_NANOS = 30 * 1000000000LL;
const int64_t BUFFER_SHRINK_WINDOW_MAX_NANOS = 8 * 60 * 1000000000LL;
const int32_t BUFFER_SHRINK_MARGIN_MS = 30;

// the level counts as settled at the target within this, only then the target shrinks again
const int32_t BUFFER_SETTLED_MS = 15;

// audio buffered beyond the target plus this, e.g. after a stall of the output, is dropped so it does not delay
// the picture
const int32_t BUFFER_TRIM_MS = 100;

// The playback rate is trimmed by at most this much to follow the clock difference between sender and device and to
// hold the buffer at its target. Crystals differ by ~0.01 %, but a TV device that derives its audio clock from a
// 59.94 Hz HDMI mode can be 0.1 % off. 0.5 % (9 cents) is inaudible when it changes as slowly as here.
const double MAX_RATE_CORRECTION = 0.005;

// rate correction per second of deviation from the target, on top of the measured clock difference: a target 50 ms
// lower is reached in about a minute
const double RATE_CORRECTION_PER_SECOND = 0.02;

// The clock difference is measured once per second of the device's clock. The measurement is used once it spans
// this long and its standard error is below this; until then the one measured last time for the source, if any.
// What is left of the error only moves the level ~15 ms off the target, and it keeps shrinking.
const double CLOCK_MIN_SPAN_SECONDS = 30;
const double CLOCK_MAX_ERROR = 0.0003;

// smoothing of the buffer level for the rate correction, in output callbacks (10 ms each): audio arrives in bursts
const double LEVEL_AVERAGE_CALLBACKS = 100;

// stereo frames, a power of two: ~1.4 s at 48 kHz
const int32_t RING_FRAMES = 1 << 16;

const uint32_t CAPTURE_TIMEOUT_MS = 50;

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

float clampSample(float value) {
    return fminf(fmaxf(value, -1.f), 1.f);
}

// 4-point Hermite interpolation between x0 and x1, t in [0, 1)
float hermite(float xm1, float x0, float x1, float x2, float t) {
    float c1 = 0.5f * (x1 - xm1);
    float c2 = xm1 - 2.5f * x0 + 2.f * x1 - 0.5f * x2;
    float c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1);
    return ((c3 * t + c2) * t + c1) * t + x0;
}

/**
 * Stereo samples from the capture thread to the output callback. One writer and one reader, no locks.
 */
class RingBuffer {
public:
    RingBuffer() : samples(new float[2 * RING_FRAMES]) {}

    int32_t available() const {
        return (int32_t) (writeIndex.load(std::memory_order_acquire) - readIndex.load(std::memory_order_relaxed));
    }

    /**
     * Writer side. @return the frames that did not fit
     */
    int32_t write(const float *left, const float *right, int32_t frames) {
        int64_t write = writeIndex.load(std::memory_order_relaxed);
        int64_t space = RING_FRAMES - (write - readIndex.load(std::memory_order_acquire));
        int32_t count = (int32_t) std::min<int64_t>(frames, space);
        for (int32_t i = 0; i < count; i++) {
            int64_t slot = (write + i) & (RING_FRAMES - 1);
            samples[2 * slot] = clampSample(left[i]);
            samples[2 * slot + 1] = clampSample(right[i]);
        }
        writeIndex.store(write + count, std::memory_order_release);
        return frames - count;
    }

    // reader side: frames are read relative to the read index and consumed afterwards

    float left(int32_t offset) const {
        return samples[2 * ((readIndex.load(std::memory_order_relaxed) + offset) & (RING_FRAMES - 1))];
    }

    float right(int32_t offset) const {
        return samples[2 * ((readIndex.load(std::memory_order_relaxed) + offset) & (RING_FRAMES - 1)) + 1];
    }

    void consume(int32_t frames) {
        readIndex.store(readIndex.load(std::memory_order_relaxed) + frames, std::memory_order_release);
    }

private:
    std::unique_ptr<float[]> samples;
    std::atomic<int64_t> writeIndex{0};
    std::atomic<int64_t> readIndex{0};
};

/**
 * Measures the sender's clock against the device's: a line fitted through the frames received over the time played
 * on the device's clock has the sender's sample rate, as the device sees it, as its slope. Unlike the buffer level
 * it is not upset by the buffer running empty or being trimmed, and network jitter averages out.
 */
class ClockEstimator {
public:
    void reset() {
        count = 0;
        meanSeconds = meanFrames = 0;
        varianceSeconds = varianceFrames = covariance = 0;
    }

    void add(double seconds, double frames) {
        if (count == 0) {
            firstSeconds = seconds;
        }
        lastSeconds = seconds;
        // Welford's method: sums of squares of large values would cancel out
        count++;
        double deltaSeconds = seconds - meanSeconds;
        meanSeconds += deltaSeconds / count;
        double deltaFrames = frames - meanFrames;
        meanFrames += deltaFrames / count;
        varianceSeconds += deltaSeconds * (seconds - meanSeconds);
        varianceFrames += deltaFrames * (frames - meanFrames);
        covariance += deltaSeconds * (frames - meanFrames);
    }

    double spanSeconds() const {
        return count > 0 ? lastSeconds - firstSeconds : 0;
    }

    /**
     * @return false while there are too few samples, otherwise writes how much faster the sender's clock runs than
     *         the device's, as a fraction of the nominal rate, and the standard error of that
     */
    bool estimate(int32_t nominalRate, double &difference, double &standardError) const {
        if (count < 3 || varianceSeconds <= 0) {
            return false;
        }
        double slope = covariance / varianceSeconds;
        double residuals = std::max(0.0, varianceFrames - slope * covariance);
        difference = slope / nominalRate - 1;
        standardError = std::sqrt(residuals / (count - 2) / varianceSeconds) / nominalRate;
        return true;
    }

private:
    int64_t count = 0;
    double firstSeconds = 0;
    double lastSeconds = 0;
    double meanSeconds = 0;
    double meanFrames = 0;
    double varianceSeconds = 0;
    double varianceFrames = 0;
    double covariance = 0;
};

// The clock difference measured per source, so a stream opened again (e.g. after the screen was off) starts with it.
std::mutex measuredClocksLock;
std::unordered_map<std::string, double> measuredClocks;

/**
 * Plays the audio of an NDI source. A receiver of its own (audio only) feeds a jitter buffer on a capture thread,
 * so a late packet only changes how full the buffer is. The output's data callback plays it back with a resampler
 * that converts to the device rate, follows the measured clock difference between sender and device and trims the
 * rate slightly to hold the buffer at its target.
 *
 * For A/V sync the callback records when it played which output frame and how much was buffered in front of it;
 * together with the stream's timestamp this gives the time it takes audio from arriving to the speaker.
 */
class AudioPlayer : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioPlayer(const char *sourceName, const char *urlAddress)
            : sourceName(sourceName ? sourceName : ""), urlAddress(urlAddress ? urlAddress : "") {
        NDIlib_recv_create_v3_t create;
        std::memset(&create, 0, sizeof(create));
        create.color_format = NDIlib_recv_color_format_fastest;
        create.bandwidth = NDIlib_recv_bandwidth_audio_only;
        create.allow_video_fields = false;
        receiver = NDIlib_recv_create_v3(&create);
    }

    ~AudioPlayer() {
        stop();
        if (receiver) {
            NDIlib_recv_destroy(receiver);
        }
    }

    bool start() {
        std::lock_guard<std::mutex> guard(lock);
        if (!receiver) {
            return false;
        }
        stopped = false;
        if (!stream && !clockMeasured) {
            std::lock_guard<std::mutex> clocksGuard(measuredClocksLock);
            auto measured = measuredClocks.find(sourceName);
            if (measured != measuredClocks.end()) {
                clockDifference = measured->second;
                clockRemembered = true;
            }
        }
        startCaptureLocked();
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
        stopCaptureLocked();
        if (clockMeasured) {
            std::lock_guard<std::mutex> clocksGuard(measuredClocksLock);
            measuredClocks[sourceName] = clockDifference;
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        int64_t playedAt = monotonicNanos();
        int64_t frameIndex = audioStream->getFramesWritten();
        int32_t outputRate = audioStream->getSampleRate();
        auto *out = static_cast<float *>(audioData);

        int32_t sourceRate = receivedSampleRate.load(std::memory_order_acquire);
        if (sourceRate <= 0) {
            // nothing received yet
            std::fill(out, out + 2 * numFrames, 0.f);
            return oboe::DataCallbackResult::Continue;
        }

        framesPlayed += numFrames;
        if (framesPlayed >= nextClockSampleAt) {
            nextClockSampleAt = framesPlayed + outputRate;
            measureClock((double) framesPlayed / outputRate, sourceRate);
        }

        int32_t buffered = ring.available();
        int32_t targetMs = bufferTargetMs.load(std::memory_order_relaxed);
        int32_t target = (int32_t) ((int64_t) targetMs * sourceRate / 1000);
        bufferedMillis.store((int32_t) ((int64_t) buffered * 1000 / sourceRate), std::memory_order_relaxed);

        double nominalStep = (double) sourceRate / outputRate;
        // the interpolation reads up to two frames past the last position
        int32_t needed = (int32_t) std::ceil(numFrames * nominalStep * (1 + MAX_RATE_CORRECTION) + phase) + 2;

        if (buffering) {
            if (buffered < target) {
                std::fill(out, out + 2 * numFrames, 0.f);
                return oboe::DataCallbackResult::Continue;
            }
            buffering = false;
            shrinkWindowStart = playedAt;
            shrinkWindowDip = 0;
            // Playback starts when a burst pushes the level past the target. What the burst brought on top would
            // stay as extra delay, the rate correction only removes it over minutes.
            ring.consume(buffered - target);
            buffered = target;
            averageLevel = buffered;
        } else if (buffered < needed) {
            underruns.fetch_add(1, std::memory_order_relaxed);
            int32_t largerMs = std::min(targetMs + BUFFER_STEP_MS, bufferMaxMs.load(std::memory_order_relaxed));
            bufferTargetMs.store(largerMs, std::memory_order_relaxed);
            if (lastShrinkAt != 0 && playedAt - lastShrinkAt < 2 * shrinkWindowNanos) {
                // shrunk too early
                shrinkWindowNanos = std::min(2 * shrinkWindowNanos, BUFFER_SHRINK_WINDOW_MAX_NANOS);
            }
            __android_log_print(ANDROID_LOG_INFO, TAG,
                    "Buffer ran empty, target %d ms, next shrink after %lld s, sender clock %+.3f %%", largerMs,
                    (long long) (shrinkWindowNanos / 1000000000LL), clockDifference * 100);
            buffering = true;
            std::fill(out, out + 2 * numFrames, 0.f);
            return oboe::DataCallbackResult::Continue;
        }

        int32_t trim = (int32_t) ((int64_t) BUFFER_TRIM_MS * sourceRate / 1000);
        if (buffered > target + trim) {
            ring.consume(buffered - target);
            buffered = target;
            averageLevel = buffered;
        }

        // play a little faster while above the target and slower while below
        averageLevel += (buffered - averageLevel) / LEVEL_AVERAGE_CALLBACKS;
        double deviationSeconds = (averageLevel - target) / sourceRate;
        double correction = std::clamp(clockDifference + deviationSeconds * RATE_CORRECTION_PER_SECOND,
                -MAX_RATE_CORRECTION, MAX_RATE_CORRECTION);
        double step = nominalStep * (1 + correction);

        // how far the level dips below its average, right after this pull: the jitter the buffer has to cover
        shrinkWindowDip = std::max(shrinkWindowDip, averageLevel - (buffered - needed));
        if (playedAt - shrinkWindowStart >= shrinkWindowNanos) {
            int32_t smallerMs = std::max(targetMs - BUFFER_STEP_MS, BUFFER_MIN_MS);
            double offTargetMs = fabs(averageLevel - target) * 1000 / sourceRate;
            double coveredMs = shrinkWindowDip * 1000 / sourceRate + BUFFER_SHRINK_MARGIN_MS;
            if (smallerMs < targetMs && offTargetMs < BUFFER_SETTLED_MS && coveredMs <= smallerMs) {
                // the rate correction plays the surplus off slowly
                bufferTargetMs.store(smallerMs, std::memory_order_relaxed);
                lastShrinkAt = playedAt;
                __android_log_print(ANDROID_LOG_INFO, TAG, "Steady for %lld s with dips of %.0f ms, target %d ms",
                        (long long) (shrinkWindowNanos / 1000000000LL), shrinkWindowDip * 1000 / sourceRate,
                        smallerMs);
            }
            __android_log_print(ANDROID_LOG_INFO, TAG,
                    "Level %+.1f ms off target, sender clock %+.3f %% (%s, ±%.3f %% over %.0f s)",
                    (averageLevel - target) * 1000 / sourceRate, clockDifference * 100,
                    clockMeasured ? "measured" : clockRemembered ? "remembered, measuring" : "not measured yet",
                    clockError * 100, clockEstimator.spanSeconds());
            shrinkWindowStart = playedAt;
            shrinkWindowDip = 0;
        }

        float peakLeft = 0.f;
        float peakRight = 0.f;
        int32_t position = 0;
        for (int32_t i = 0; i < numFrames; i++) {
            auto t = (float) phase;
            float l = hermite(previousLeft, ring.left(position), ring.left(position + 1), ring.left(position + 2), t);
            float r = hermite(previousRight, ring.right(position), ring.right(position + 1), ring.right(position + 2),
                    t);
            l = clampSample(l);
            r = clampSample(r);
            peakLeft = fmaxf(peakLeft, fabsf(l));
            peakRight = fmaxf(peakRight, fabsf(r));
            out[2 * i] = l;
            out[2 * i + 1] = r;

            phase += step;
            while (phase >= 1.0) {
                previousLeft = ring.left(position);
                previousRight = ring.right(position);
                position++;
                phase -= 1.0;
            }
        }
        ring.consume(position);

        raisePeak(this->peakLeft, peakLeft);
        raisePeak(this->peakRight, peakRight);

        // seqlock: readers retry while the sequence is odd or changed
        playSequence.fetch_add(1, std::memory_order_relaxed);
        std::atomic_thread_fence(std::memory_order_release);
        lastPlayedAt.store(playedAt, std::memory_order_relaxed);
        lastFrameIndex.store(frameIndex, std::memory_order_relaxed);
        lastSampleRate.store(outputRate, std::memory_order_relaxed);
        lastBufferedNanos.store((int64_t) buffered * 1000000000LL / sourceRate, std::memory_order_relaxed);
        playSequence.fetch_add(1, std::memory_order_release);

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

    /**
     * Lowers the buffer's limit for a source that also sends video: the video waits for its audio.
     */
    void limitBufferForVideo() {
        bufferMaxMs.store(BUFFER_MAX_WITH_VIDEO_MS, std::memory_order_relaxed);
        int32_t targetMs = bufferTargetMs.load(std::memory_order_relaxed);
        while (targetMs > BUFFER_MAX_WITH_VIDEO_MS
                && !bufferTargetMs.compare_exchange_weak(targetMs, BUFFER_MAX_WITH_VIDEO_MS,
                        std::memory_order_relaxed)) {
        }
    }

    void takePeaks(float &left, float &right) {
        left = peakLeft.exchange(0.f, std::memory_order_relaxed);
        right = peakRight.exchange(0.f, std::memory_order_relaxed);
    }

    bool isReceiving() const {
        return receivedSampleRate.load(std::memory_order_acquire) > 0;
    }

    /**
     * Writes the jitter buffer's target and what is buffered now (both in milliseconds), how often it ran empty and
     * how many audio frames the receiver dropped.
     */
    void bufferStats(int64_t &targetMs, int64_t &bufferedMs, int64_t &underrunCount, int64_t &droppedFrames) {
        targetMs = bufferTargetMs.load(std::memory_order_relaxed);
        bufferedMs = bufferedMillis.load(std::memory_order_relaxed);
        underrunCount = underruns.load(std::memory_order_relaxed);
        NDIlib_recv_performance_t dropped;
        std::memset(&dropped, 0, sizeof(dropped));
        if (receiver) {
            NDIlib_recv_get_performance(receiver, nullptr, &dropped);
        }
        droppedFrames = dropped.audio_frames;
    }

    /**
     * Time from audio arriving to hearing it: the time it waits in the jitter buffer plus the output latency. -1 if
     * the stream can't tell yet.
     */
    int64_t outputLatencyNanos() {
        int64_t playedAt;
        int64_t frameIndex;
        int32_t sampleRate;
        int64_t bufferedNanos;
        for (;;) {
            uint32_t sequence = playSequence.load(std::memory_order_acquire);
            playedAt = lastPlayedAt.load(std::memory_order_relaxed);
            frameIndex = lastFrameIndex.load(std::memory_order_relaxed);
            sampleRate = lastSampleRate.load(std::memory_order_relaxed);
            bufferedNanos = lastBufferedNanos.load(std::memory_order_relaxed);
            std::atomic_thread_fence(std::memory_order_acquire);
            if ((sequence & 1) == 0 && sequence == playSequence.load(std::memory_order_acquire)) {
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
        // when the first frame of the last played buffer reaches the speaker
        int64_t presentedAt = timestamp.value().timestamp
                + (frameIndex - timestamp.value().position) * 1000000000LL / sampleRate;
        return presentedAt - playedAt + bufferedNanos;
    }

private:
    /**
     * Adds a sample of the frames received so far at {@code seconds} played on the device's clock, and uses the
     * measured clock difference once it is accurate enough.
     */
    void measureClock(double seconds, int32_t sourceRate) {
        int64_t received = receivedFrames.load(std::memory_order_relaxed);
        if (received == lastSampleReceived || sourceRate != measuredSourceRate) {
            // nothing arrived for a second, the sender paused, or its rate changed: either would bend the line
            clockEstimator.reset();
        }
        lastSampleReceived = received;
        measuredSourceRate = sourceRate;
        clockEstimator.add(seconds, (double) received);

        double difference;
        double error;
        if (clockEstimator.spanSeconds() >= CLOCK_MIN_SPAN_SECONDS
                && clockEstimator.estimate(sourceRate, difference, error)) {
            clockError = error;
            if (error <= CLOCK_MAX_ERROR) {
                clockDifference = std::clamp(difference, -MAX_RATE_CORRECTION, MAX_RATE_CORRECTION);
                clockMeasured = true;
            }
        }
    }

    void startCaptureLocked() {
        if (capturing.exchange(true)) {
            return;
        }
        NDIlib_source_t source;
        std::memset(&source, 0, sizeof(source));
        source.p_ndi_name = sourceName.c_str();
        source.p_url_address = urlAddress.empty() ? nullptr : urlAddress.c_str();
        NDIlib_recv_connect(receiver, &source);

        captureThread = std::thread(&AudioPlayer::captureLoop, this);
    }

    void stopCaptureLocked() {
        if (!capturing.exchange(false)) {
            return;
        }
        if (captureThread.joinable()) {
            captureThread.join();
        }
        NDIlib_recv_connect(receiver, nullptr);
    }

    void captureLoop() {
        while (capturing.load(std::memory_order_relaxed)) {
            NDIlib_audio_frame_v3_t frame;
            std::memset(&frame, 0, sizeof(frame));
            NDIlib_frame_type_e type = NDIlib_recv_capture_v3(receiver, nullptr, &frame, nullptr, CAPTURE_TIMEOUT_MS);
            if (type != NDIlib_frame_type_audio) {
                continue;
            }

            if (frame.p_data && frame.no_channels > 0 && frame.no_samples > 0 && frame.sample_rate > 0) {
                // the buffer counts samples at the sender's rate
                receivedSampleRate.store(frame.sample_rate, std::memory_order_release);
                receivedFrames.fetch_add(frame.no_samples, std::memory_order_relaxed);
                // planar float: one block of samples per channel
                const auto *left = reinterpret_cast<const float *>(frame.p_data);
                const float *right = frame.no_channels > 1
                        ? reinterpret_cast<const float *>(frame.p_data + frame.channel_stride_in_bytes) : left;
                ring.write(left, right, frame.no_samples);
            }
            NDIlib_recv_free_audio_v3(receiver, &frame);
        }
    }

    bool openLocked() {
        if (stream) {
            return true;
        }
        // audio left over from before audio was switched off is old, the buffer fills up again
        ring.consume(ring.available());
        buffering = true;
        phase = 0;
        previousLeft = 0.f;
        previousRight = 0.f;
        // the device's clock is counted in frames played, which start over
        framesPlayed = 0;
        nextClockSampleAt = 0;
        lastSampleReceived = -1;
        clockEstimator.reset();

        // No sample rate requested: the device's native rate avoids a second resampler in Android. 10 ms per
        // callback instead of the 2 ms burst keeps the per-call overhead low; the first frame of each callback
        // still reaches the speaker after the same ~13 ms.
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

    const std::string sourceName;
    const std::string urlAddress;
    NDIlib_recv_instance_t receiver = nullptr;

    // guards stream, which the error callback replaces on its own thread, and the capture thread
    std::mutex lock;
    std::shared_ptr<oboe::AudioStream> stream;
    bool stopped = true;

    std::atomic<bool> capturing{false};
    std::thread captureThread;
    std::atomic<int32_t> receivedSampleRate{0};
    std::atomic<int64_t> receivedFrames{0};
    RingBuffer ring;

    // only touched by the output callback, and by openLocked while no stream runs
    bool buffering = true;
    double phase = 0;
    double averageLevel = 0;
    int64_t shrinkWindowStart = 0;
    double shrinkWindowDip = 0;
    int64_t shrinkWindowNanos = BUFFER_SHRINK_WINDOW_NANOS;
    int64_t lastShrinkAt = 0;
    float previousLeft = 0.f;
    float previousRight = 0.f;
    int64_t framesPlayed = 0;
    int64_t nextClockSampleAt = 0;
    int64_t lastSampleReceived = -1;
    int32_t measuredSourceRate = 0;
    ClockEstimator clockEstimator;
    // How much faster the sender's clock runs than the device's, as a fraction. Kept when the stream is reopened:
    // the clocks stay the same. Also touched by start() and stop() while no stream runs.
    double clockDifference = 0;
    double clockError = 0;
    bool clockMeasured = false;
    bool clockRemembered = false;

    std::atomic<int32_t> bufferTargetMs{BUFFER_INITIAL_MS};
    std::atomic<int32_t> bufferMaxMs{BUFFER_MAX_MS};
    std::atomic<int32_t> bufferedMillis{0};
    std::atomic<int64_t> underruns{0};

    std::atomic<float> peakLeft{0.f};
    std::atomic<float> peakRight{0.f};

    std::atomic<uint32_t> playSequence{0};
    std::atomic<int64_t> lastPlayedAt{0};
    std::atomic<int64_t> lastFrameIndex{0};
    std::atomic<int32_t> lastSampleRate{0};
    std::atomic<int64_t> lastBufferedNanos{0};
};

}

extern "C" {
    JNIEXPORT jlong JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_create(JNIEnv *env, jclass jClazz, jstring jName, jstring jUrlAddress) {
        const char *name = jName ? env->GetStringUTFChars(jName, nullptr) : nullptr;
        const char *url = jUrlAddress ? env->GetStringUTFChars(jUrlAddress, nullptr) : nullptr;
        auto *player = new AudioPlayer(name, url);
        if (name) env->ReleaseStringUTFChars(jName, name);
        if (url) env->ReleaseStringUTFChars(jUrlAddress, url);
        return (jlong) player;
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_destroy(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        delete reinterpret_cast<AudioPlayer *>(pPlayer);
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
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_limitBufferForVideo(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        reinterpret_cast<AudioPlayer *>(pPlayer)->limitBufferForVideo();
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_takePeaks(JNIEnv *env, jclass jClazz, jlong pPlayer,
            jfloatArray jPeaks) {
        float peaks[2];
        reinterpret_cast<AudioPlayer *>(pPlayer)->takePeaks(peaks[0], peaks[1]);
        env->SetFloatArrayRegion(jPeaks, 0, 2, peaks);
    }

    JNIEXPORT jboolean JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_isReceiving(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        return reinterpret_cast<AudioPlayer *>(pPlayer)->isReceiving();
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_bufferStats(JNIEnv *env, jclass jClazz, jlong pPlayer,
            jlongArray jStats) {
        int64_t targetMs;
        int64_t bufferedMs;
        int64_t underrunCount;
        int64_t droppedFrames;
        reinterpret_cast<AudioPlayer *>(pPlayer)->bufferStats(targetMs, bufferedMs, underrunCount, droppedFrames);
        jlong stats[4] = {targetMs, bufferedMs, underrunCount, droppedFrames};
        env->SetLongArrayRegion(jStats, 0, 4, stats);
    }

    JNIEXPORT jlong JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioPlayer_outputLatencyNanos(JNIEnv *env, jclass jClazz, jlong pPlayer) {
        return reinterpret_cast<AudioPlayer *>(pPlayer)->outputLatencyNanos();
    }
}
