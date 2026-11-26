#include "ndi-wrapper.h"

extern "C" {

/**
 * Connects to the NDI Discovery Servers (comma-separated, each with an optional port) to advertise receivers there.
 * Created also while no server is reachable, the receivers are registered once one is.
 */
JNIEXPORT jlong JNICALL
Java_de_daubli_feedwatch_ndi_NdiReceiverAdvertiser_advertiserCreate(JNIEnv* env, jclass /*clazz*/, jstring jUrls)
{
    const char* urls = (jUrls != nullptr) ? env->GetStringUTFChars(jUrls, nullptr) : nullptr;

    NDIlib_recv_advertiser_create_t create;
    create.p_url_address = urls;
    NDIlib_recv_advertiser_instance_t advertiser = NDIlib_recv_advertiser_create(&create);

    if (jUrls != nullptr) env->ReleaseStringUTFChars(jUrls, urls);
    return reinterpret_cast<jlong>(advertiser);
}

JNIEXPORT void JNICALL
Java_de_daubli_feedwatch_ndi_NdiReceiverAdvertiser_advertiserDestroy(JNIEnv* /*env*/, jclass /*clazz*/,
                                                                     jlong pAdvertiser)
{
    auto advertiser = reinterpret_cast<NDIlib_recv_advertiser_instance_t>(pAdvertiser);
    if (advertiser) NDIlib_recv_advertiser_destroy(advertiser);
}

/**
 * Advertises the receiver, so controllers see which source it shows and, if allowed, can connect it to another one.
 */
JNIEXPORT jboolean JNICALL
Java_de_daubli_feedwatch_ndi_NdiReceiverAdvertiser_advertiserAddReceiver(JNIEnv* env, jclass /*clazz*/,
                                                                         jlong pAdvertiser, jlong pReceiver,
                                                                         jboolean allowControl, jstring jInputGroup)
{
    auto advertiser = reinterpret_cast<NDIlib_recv_advertiser_instance_t>(pAdvertiser);
    auto recv = reinterpret_cast<NDIlib_recv_instance_t>(pReceiver);
    if (!advertiser || !recv) return JNI_FALSE;
    const char* group = (jInputGroup != nullptr) ? env->GetStringUTFChars(jInputGroup, nullptr) : nullptr;
    bool added = NDIlib_recv_advertiser_add_receiver(advertiser, recv, allowControl == JNI_TRUE, true, group);
    if (jInputGroup != nullptr) env->ReleaseStringUTFChars(jInputGroup, group);
    return added ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_de_daubli_feedwatch_ndi_NdiReceiverAdvertiser_advertiserDelReceiver(JNIEnv* /*env*/, jclass /*clazz*/,
                                                                         jlong pAdvertiser, jlong pReceiver)
{
    auto advertiser = reinterpret_cast<NDIlib_recv_advertiser_instance_t>(pAdvertiser);
    auto recv = reinterpret_cast<NDIlib_recv_instance_t>(pReceiver);
    if (!advertiser || !recv) return JNI_FALSE;
    return NDIlib_recv_advertiser_del_receiver(advertiser, recv) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
