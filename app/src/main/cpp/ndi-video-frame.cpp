#include "ndi-wrapper.h"

extern "C" {
    JNIEXPORT jlong JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_createNewVideoFrameDefaultSettings(JNIEnv *env, jclass jClazz) {
        auto *NDI_video_frame = new NDIlib_video_frame_v2_t();
        return (jlong) NDI_video_frame;
    }

    JNIEXPORT void JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_destroyVideoFrame(JNIEnv *env, jclass jClazz, jlong pFrame) {
        delete reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame);
    }

    JNIEXPORT jint JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_getXRes(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame)->xres;
    }

    JNIEXPORT jint JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_getYRes(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame)->yres;
    }

    JNIEXPORT jint JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_getFourCCType(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame)->FourCC;
    }

    JNIEXPORT jint JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_getFrameRateN(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame)->frame_rate_N;
    }

    JNIEXPORT jint JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_getFrameRateD(JNIEnv *env, jclass jClazz, jlong pFrame) {
        return reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame)->frame_rate_D;
    }

    JNIEXPORT jobject JNICALL Java_de_daubli_feedwatch_ndi_NdiVideoFrame_getData(JNIEnv *env, jclass jClazz, jlong pFrame) {
        auto *frame = reinterpret_cast<NDIlib_video_frame_v2_t *>(pFrame);
        if(frame->p_data) {
            return env->NewDirectByteBuffer(frame->p_data, frame->line_stride_in_bytes * frame->yres);
        } else {
            return nullptr;
        }
    }
}
