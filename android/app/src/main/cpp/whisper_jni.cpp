/*
 * دفتري · cpp/whisper_jni.cpp
 * جسر JNI بين Kotlin (voice/WhisperNative.kt) ونموذج whisper.cpp.
 * JNI bridge for com.daftari.app.voice.WhisperNative: load(path) → handle, transcribe(handle, pcm, lang, threads) → text, free.
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
Java_com_daftari_app_voice_WhisperNative_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring lang, jint threads) {
    const jsize n = env->GetArrayLength(pcm);
    jfloat *data = env->GetFloatArrayElements(pcm, nullptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);
    std::string text = daftari::transcribe(reinterpret_cast<void *>(handle), data, n, l, threads);
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseFloatArrayElements(pcm, data, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_daftari_app_voice_WhisperNative_nativeFree(JNIEnv *, jobject, jlong handle) {
    daftari::free_model(reinterpret_cast<void *>(handle));
}

}
