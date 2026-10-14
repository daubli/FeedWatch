// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

#include "ndi-wrapper.h"
#include <cmath>

extern "C" {
    JNIEXPORT jlong JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioFrame_createNewAudioFrameDefaultSettings(JNIEnv * env, jclass jClazz) {
        NDIlib_audio_frame_v2_t *NDI_audio_frame = new NDIlib_audio_frame_v2_t();
        return (jlong) NDI_audio_frame;
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioFrame_destroyAudioFrame(JNIEnv *env, jclass jClazz, jlong pFrame) {
        delete reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame);
    }

    JNIEXPORT jint JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioFrame_getNoSamples(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame)->no_samples;
    }

    JNIEXPORT jint JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioFrame_getSampleRate(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame)->sample_rate;
    }

    JNIEXPORT jint JNICALL Java_de_daubli_feedwatch_ndi_NdiAudioFrame_getNoChannels(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame)->no_channels;
    }

    JNIEXPORT jint JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioFrameInterleaved16s_getNoChannels(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_audio_frame_interleaved_16s_t *>(pFrame)->no_channels;
    }

    /**
     * Converts the planar float frame to interleaved 16-bit stereo in native byte order and measures the peak
     * level of both channels. Mono is duplicated, channels beyond the first two are ignored. Returns the number of
     * bytes written to jOut, or -1 if it is too small.
     */
    JNIEXPORT jint JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioFrame_toStereoPcm16(JNIEnv *env, jclass jClazz, jlong pFrame, jobject jOut,
            jfloatArray jPeaks) {
        auto frame = reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame);
        auto out = static_cast<int16_t *>(env->GetDirectBufferAddress(jOut));
        jlong capacity = env->GetDirectBufferCapacity(jOut);
        if (!frame || !frame->p_data || !out || frame->no_samples <= 0 || frame->no_channels <= 0) {
            return 0;
        }
        jlong bytes = (jlong) frame->no_samples * 2 * (jlong) sizeof(int16_t);
        if (capacity < bytes) {
            return -1;
        }

        int stride = frame->channel_stride_in_bytes > 0 ? frame->channel_stride_in_bytes / (int) sizeof(float)
                                                        : frame->no_samples;
        const float *left = frame->p_data;
        const float *right = frame->no_channels > 1 ? frame->p_data + stride : left;

        float peakLeft = 0.f;
        float peakRight = 0.f;
        for (int i = 0; i < frame->no_samples; i++) {
            float l = fminf(fmaxf(left[i], -1.f), 1.f);
            float r = fminf(fmaxf(right[i], -1.f), 1.f);
            peakLeft = fmaxf(peakLeft, fabsf(l));
            peakRight = fmaxf(peakRight, fabsf(r));
            out[2 * i] = (int16_t) lrintf(l * 32767.f);
            out[2 * i + 1] = (int16_t) lrintf(r * 32767.f);
        }

        if (jPeaks != nullptr && env->GetArrayLength(jPeaks) >= 2) {
            float peaks[2] = {peakLeft, peakRight};
            env->SetFloatArrayRegion(jPeaks, 0, 2, peaks);
        }
        return (jint) bytes;
    }

    JNIEXPORT jobject JNICALL Java_de_daubli_feedwatch_ndi_NdiAudioFrame_getData(JNIEnv *env, jclass jClazz, jlong pFrame) {
        auto frame = reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame);
        // planar: each channel starts channel_stride_in_bytes after the previous one
        jlong stride = frame->channel_stride_in_bytes > 0 ? frame->channel_stride_in_bytes
                                                          : frame->no_samples * (jlong) sizeof(float);
        return env->NewDirectByteBuffer(frame->p_data, stride * frame->no_channels);
    }
}
