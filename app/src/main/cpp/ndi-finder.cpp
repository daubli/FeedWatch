#include "ndi-wrapper.h"
#include <cstring>

extern "C" {

JNIEXPORT jlong JNICALL
Java_de_daubli_feedwatch_ndi_NdiFinder_findCreate(
    JNIEnv* env, jclass /*clazz*/,
    jboolean jShowLocalSources,
    jstring jGroups,
    jstring jExtraIps)
{
    // Use stack struct; no heap allocation needed
    NDIlib_find_create_t create{};
    std::memset(&create, 0, sizeof(create));

    create.show_local_sources = (jShowLocalSources == JNI_TRUE);

    // Pull JNI strings for the duration of the call
    const char* groups   = nullptr;
    const char* extraIps = nullptr;

    if (jGroups != nullptr)   groups   = env->GetStringUTFChars(jGroups, nullptr);
    if (jExtraIps != nullptr) extraIps = env->GetStringUTFChars(jExtraIps, nullptr);

    create.p_groups     = groups;
    create.p_extra_ips  = extraIps;

    // Create finder
    NDIlib_find_instance_t finder = NDIlib_find_create_v2(&create);

    // Release JNI strings (important!)
    if (jGroups != nullptr)   env->ReleaseStringUTFChars(jGroups, groups);
    if (jExtraIps != nullptr) env->ReleaseStringUTFChars(jExtraIps, extraIps);

    return reinterpret_cast<jlong>(finder);
}

/**
 * Returns packed String[]:
 * [name0, url0, name1, url1, ...]
 */
JNIEXPORT jobjectArray JNICALL
Java_de_daubli_feedwatch_ndi_NdiFinder_findGetCurrentSources(
    JNIEnv* env, jclass /*clazz*/, jlong finderPtr)
{
    auto instance = reinterpret_cast<NDIlib_find_instance_t>(finderPtr);
    if (!instance) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "NDI finder instance pointer is null in native code!");
        return nullptr;
    }

    uint32_t no_sources = 0;
    const NDIlib_source_t* p_sources = NDIlib_find_get_current_sources(instance, &no_sources);

    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"),
                      "Failed to find java/lang/String class");
        return nullptr;
    }

    if (!p_sources || no_sources == 0) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    // packed array: 2 entries per source (name + url)
    jsize outLen = static_cast<jsize>(no_sources * 2u);
    jobjectArray out = env->NewObjectArray(outLen, stringClass, nullptr);
    if (!out) return nullptr;

    for (uint32_t i = 0; i < no_sources; i++) {
        const char* name = p_sources[i].p_ndi_name ? p_sources[i].p_ndi_name : "";
        const char* url  = p_sources[i].p_url_address ? p_sources[i].p_url_address : "";

        jstring jName = env->NewStringUTF(name);
        jstring jUrl  = env->NewStringUTF(url);

        env->SetObjectArrayElement(out, static_cast<jsize>(2u * i),     jName);
        env->SetObjectArrayElement(out, static_cast<jsize>(2u * i + 1), jUrl);

        // Local refs cleanup (good practice in loops)
        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jUrl);
    }

    return out;
}

JNIEXPORT void JNICALL
Java_de_daubli_feedwatch_ndi_NdiFinder_findDestroy(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pFind)
{
    auto instance = reinterpret_cast<NDIlib_find_instance_t>(pFind);
    if (!instance) return;
    NDIlib_find_destroy(instance);
}

JNIEXPORT jboolean JNICALL
Java_de_daubli_feedwatch_ndi_NdiFinder_findWaitForSources(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong pFind, jint jTimeout)
{
    auto instance = reinterpret_cast<NDIlib_find_instance_t>(pFind);
    if (!instance) return JNI_FALSE;
    return NDIlib_find_wait_for_sources(instance, jTimeout) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
