#include <jni.h>
extern "C" JNIEXPORT jstring JNICALL
Java_com_aether_sample_SampleApplication_nativeGreeting(JNIEnv* env, jclass) {
    return env->NewStringUTF("native-library-loaded");
}
