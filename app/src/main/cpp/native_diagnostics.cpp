#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_aether_sandbox_core_NativeDiagnostics_abi(JNIEnv* env, jobject) {
#if defined(__aarch64__)
    constexpr auto abi = "arm64-v8a";
#elif defined(__arm__)
    constexpr auto abi = "armeabi-v7a";
#elif defined(__x86_64__)
    constexpr auto abi = "x86_64";
#else
    constexpr auto abi = "unknown";
#endif
    return env->NewStringUTF(abi);
}
