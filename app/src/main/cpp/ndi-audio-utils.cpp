// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

#include "ndi-wrapper.h"

extern "C" {
    JNIEXPORT void JNICALL
    Java_de_daubli_feedwatch_ndi_AudioUtils_convertToInterleaved16s(JNIEnv *env, jclass jClazz, jlong pSourceFrame, jlong pTargetFrame) {
        auto *src = reinterpret_cast<NDIlib_audio_frame_v2_t *>(pSourceFrame);
        auto *dst = reinterpret_cast<NDIlib_audio_frame_interleaved_16s_t *>(pTargetFrame);

        if (!src || !dst) {
            return;
        }

        NDIlib_util_audio_to_interleaved_16s_v2(src, dst);
    }
}
