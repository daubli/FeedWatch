#include "ndi-wrapper.h"
#include <cstring>

extern "C" {

JNIEXPORT jlong JNICALL
Java_de_daubli_feedwatch_ndi_NdiReceiver_receiveCreate(JNIEnv* env, jclass /*clazz*/, jboolean useLowBandwidth)
{
    NDIlib_recv_create_v3_t recv_create_struct;
    std::memset(&recv_create_struct, 0, sizeof(recv_create_struct));
	recv_create_struct.color_format = NDIlib_recv_color_format_UYVY_BGRA;

    bool lowBandwidth = (useLowBandwidth == JNI_TRUE);

    recv_create_struct.bandwidth = lowBandwidth ? NDIlib_recv_bandwidth_lowest : NDIlib_recv_bandwidth_highest;

    recv_create_struct.allow_video_fields = true;
    recv_create_struct.source_to_connect_to = nullptr;

    NDIlib_recv_instance_t receiver = NDIlib_recv_create_v3(&recv_create_struct);

    return reinterpret_cast<jlong>(receiver);
}

/**
 * Connect by value (name/url). To disconnect, pass (null, null).
 */
JNIEXPORT void JNICALL
Java_de_daubli_feedwatch_ndi_NdiReceiver_receiveConnect(
    JNIEnv* env, jclass /*clazz*/, jlong pReceiver, jstring jName, jstring jUrlAddress)
{
    auto recv = reinterpret_cast<NDIlib_recv_instance_t>(pReceiver);
    if (!recv) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NDI receiver instance pointer is null in native code!");
        return;
    }

    // Disconnect if both null (or caller wants to clear connection)
    if (jName == nullptr && jUrlAddress == nullptr) {
        NDIlib_recv_connect(recv, nullptr);
        return;
    }

    const char* name = (jName != nullptr) ? env->GetStringUTFChars(jName, nullptr) : nullptr;
    const char* url  = (jUrlAddress != nullptr) ? env->GetStringUTFChars(jUrlAddress, nullptr) : nullptr;

    NDIlib_source_t src;
    std::memset(&src, 0, sizeof(src));
    src.p_ndi_name = name;
    src.p_url_address = url;

    NDIlib_recv_connect(recv, &src);

    if (jName != nullptr) env->ReleaseStringUTFChars(jName, name);
    if (jUrlAddress != nullptr) env->ReleaseStringUTFChars(jUrlAddress, url);
}

JNIEXPORT void JNICALL
Java_de_daubli_feedwatch_ndi_NdiReceiver_receiveDestroy(JNIEnv* env, jclass /*clazz*/, jlong pReceiver) {
    auto recv = reinterpret_cast<NDIlib_recv_instance_t>(pReceiver);
    if (!recv) return;
    NDIlib_recv_destroy(recv);
}

} // extern "C"
