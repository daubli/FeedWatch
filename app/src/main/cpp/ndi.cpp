// Based on code from https://github.com/WalkerKnapp/devolay (Apache 2.0).

#include "ndi-wrapper.h"

extern "C" {
    // Helper: Convert std::string to jstring
    jstring stringToJString(JNIEnv* env, const std::string& str) {
        return env->NewStringUTF(str.c_str());
    }

    jboolean boolToJBoolean(bool value) {
        return value ? JNI_TRUE : JNI_FALSE;
    }

    JNIEXPORT jboolean JNICALL
    Java_de_daubli_feedwatch_ndi_Ndi_nInitializeNDI(JNIEnv *env, jobject thiz) {
        return boolToJBoolean(NDIlib_initialize());
    }

    JNIEXPORT jstring JNICALL
    Java_de_daubli_feedwatch_ndi_Ndi_nGetNdiVersion(JNIEnv* env, jobject thiz) {
        const char* version = NDIlib_version();
        if (version == nullptr) {
            version = "Unknown";
        }
        return stringToJString(env, version);
    }

    JNIEXPORT jboolean JNICALL
    Java_de_daubli_feedwatch_ndi_Ndi_nIsSupportedCpu(JNIEnv *env, jclass jClazz) {
        return boolToJBoolean(NDIlib_is_supported_CPU());
    }
}
