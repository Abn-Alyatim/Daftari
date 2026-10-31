/*
 * دفتري · cpp/whisper_jni.cpp
 * جسر JNI بين Kotlin (voice/WhisperNative.kt) ونموذج whisper.cpp.
 * JNI bridge for com.daftari.app.voice.WhisperNative: load(path) → handle, transcribe(handle, pcm, lang, threads, prompt) → text, free.
 * 1.1.8: prompt = the hint (customer names + phrases) given to whisper as initial_prompt.
 */
#include <jni.h>
#include <string>
#include "daftari_whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_daftari_app_voice_WhisperNative_nativeLoad(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    void *h = daftari::load(p);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT jstring JNICALL
Java_com_daftari_app_voice_WhisperNative_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring lang, jint threads, jstring prompt) {
    const jsize n = env->GetArrayLength(pcm);
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);
    const char *pr = prompt ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    std::string text = daftari::transcribe(reinterpret_cast<void *>(handle), data, n, l, threads, pr);
    if (pr) env->ReleaseStringUTFChars(prompt, pr);
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_daftari_app_voice_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    daftari::free_model(reinterpret_cast<void *>(handle));
}

}
