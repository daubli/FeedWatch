// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

#include "ndi-wrapper.h"

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

    JNIEXPORT jint JNICALL Java_de_daubli_feedwatch_ndi_NdiAudioFrame_getNoChannels(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame)->no_channels;
    }

    JNIEXPORT jint JNICALL
    Java_de_daubli_feedwatch_ndi_NdiAudioFrameInterleaved16s_getNoChannels(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_audio_frame_interleaved_16s_t *>(pFrame)->no_channels;
    }

    JNIEXPORT jobject JNICALL Java_de_daubli_feedwatch_ndi_NdiAudioFrame_getData(JNIEnv *env, jclass jClazz, jlong pFrame) {
        auto frame = reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame);
        return env->NewDirectByteBuffer(frame->p_data, frame->no_samples * frame->no_channels * sizeof(float));
    }
}
