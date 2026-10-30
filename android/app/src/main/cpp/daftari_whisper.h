/*
 * دفتري · cpp/daftari_whisper.h
 * واجهة صغيرة فوق whisper.cpp: تحميل النموذج، وتحويل صوت 16 ك.هـ أحادي إلى نص عربي على الجهاز دون إنترنت.
 * A tiny portable layer over whisper.cpp (MIT), shared by the Android JNI bridge and the host test program:
 * load a ggml model file, transcribe 16 kHz mono float PCM in Arabic, free. No network, no files written.
 */
#pragma once
#include <string>

namespace daftari {
    /** Loads the model at [path]; returns an opaque handle, or nullptr when the file is not a valid model. */
    void *load(const char *path);
    /** Transcribes [n] samples (16 kHz mono, -1..1) in [lang] with [threads] CPU threads; "" when nothing was heard. */
    std::string transcribe(void *handle, const float *pcm, int n, const char *lang, int threads);
    void free_model(void *handle);
}
