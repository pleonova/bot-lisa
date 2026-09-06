// Phase 1 stub -- proves the JNI bridge round-trips before llama.cpp is
// vendored in Phase 2. See ON_DEVICE_LLM_PLAN.md.
#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_botlisa_llm_Smoke_hello(JNIEnv *env, jobject /* this */) {
    std::string result = "onDeviceLlm native stub is alive";
    return env->NewStringUTF(result.c_str());
}
