// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

#include "ndi-wrapper.h"

extern "C" {
    JNIEXPORT jlong JNICALL
    Java_de_daubli_feedwatch_ndi_NdiFrameSync_framesyncCreate(JNIEnv *env, jclass jClazz, jlong pReceiver) {
        return (jlong) NDIlib_framesync_create(reinterpret_cast<NDIlib_recv_instance_t>(pReceiver));
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiFrameSync_framesyncDestroy(JNIEnv *env, jclass jClazz, jlong pFramesync) {
        NDIlib_framesync_destroy(reinterpret_cast<NDIlib_framesync_instance_t>(pFramesync));
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiFrameSync_framesyncCaptureAudio
            (JNIEnv *env, jclass jClazz, jlong pFramesync, jlong pFrame, jint jSampleRate, jint jNoChannels, jint jNoSamples) {
        NDIlib_framesync_capture_audio(reinterpret_cast<NDIlib_framesync_instance_t>(pFramesync),
                reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame),
                jSampleRate, jNoChannels, jNoSamples);
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiFrameSync_framesyncFreeAudio
            (JNIEnv *env, jclass jClazz, jlong pFramesync, jlong pFrame) {
        NDIlib_framesync_free_audio(reinterpret_cast<NDIlib_framesync_instance_t>(pFramesync),
                reinterpret_cast<NDIlib_audio_frame_v2_t *>(pFrame));
    }

    JNIEXPORT jboolean JNICALL
    Java_de_daubli_feedwatch_ndi_NdiFrameSync_framesyncCaptureVideo
            (JNIEnv *env, jclass jClazz, jlong pFramesync, jlong pFrame, jint jFrameFormatType) {
        NDIlib_framesync_capture_video(reinterpret_cast<NDIlib_framesync_instance_t>(pFramesync),
                reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame),
                (NDIlib_frame_format_type_e)jFrameFormatType);

        return reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame)->p_data != (uint8_t *)nullptr;
    }

    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_NdiFrameSync_framesyncFreeVideo
            (JNIEnv *env, jclass jClazz, jlong pFramesync, jlong pFrame) {
        NDIlib_framesync_free_video(reinterpret_cast<NDIlib_framesync_instance_t>(pFramesync),
                reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame));
    }
}
