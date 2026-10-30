/*
 * دفتري · cpp/daftari_whisper.cpp
 * تنفيذ التحويل بـ whisper.cpp: فك ترميز جشع (greedy) بلا سياق سابق، باللغة العربية، مع نافذة صوت مقصوصة على طول الكلام.
 * whisper.cpp transcription: greedy decoding, no previous-text context, single segment, no timestamps, language fixed
 * (Arabic). The encoder window (audio_ctx) is cut to the spoken length (+ margin) instead of the full 30 s, which makes a
 * 3–10 s phrase several times faster on a phone (measured 5× on the computer, same text); blank-audio / bracket markers
 * are stripped, tokens are capped and a back-to-back repeated sentence (a looping small model) is kept once.
 */
#include "daftari_whisper.h"
#include "whisper.h"
#include <algorithm>
#include <cmath>

namespace daftari {

void *load(const char *path) {
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = false;
    return whisper_init_from_file_with_params(path, cp);
}

/** A small model sometimes loops ("X. X. X."): keep each sentence once when it repeats back to back. */
static std::string collapse_repeats(const std::string &s) {
    std::string out, prev, cur;
    auto flush = [&](bool dot) {
        size_t a = cur.find_first_not_of(' ');
        std::string t = a == std::string::npos ? std::string() : cur.substr(a);
        // identical, or a cut-off copy of the previous sentence (the token cap ended a loop mid-way)
        const bool repeat = !prev.empty() && prev.compare(0, t.size(), t) == 0;
        if (!t.empty() && !repeat) { if (!out.empty()) out += ' '; out += t; if (dot) out += '.'; prev = t; }
        cur.clear();
    };
    for (char ch : s) { if (ch == '.') flush(true); else cur += ch; }
    flush(false);
    return out;
}

static std::string clean(std::string s) {
    // drop "[BLANK_AUDIO]", "(music)" style markers and surrounding spaces
    std::string out;
    int depth = 0;
    for (char ch : s) {
        if (ch == '[' || ch == '(') { depth++; continue; }
        if ((ch == ']' || ch == ')') && depth > 0) { depth--; continue; }
        if (depth == 0) out += ch;
    }
    size_t a = out.find_first_not_of(" \t\n");
    size_t b = out.find_last_not_of(" \t\n");
    return a == std::string::npos ? std::string() : out.substr(a, b - a + 1);
}

std::string transcribe(void *handle, const float *pcm, int n, const char *lang, int threads) {
    auto *ctx = static_cast<whisper_context *>(handle);
    if (!ctx || !pcm || n <= 0) return "";
    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.language = lang;
    p.translate = false;
    p.detect_language = false;
    p.n_threads = std::max(1, threads);
    p.no_context = true;
    p.single_segment = true;
    p.no_timestamps = true;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_special = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.temperature_inc = 0.0f; // no slow fallback passes
    p.max_tokens = 128;       // a 15 s sentence never needs more; stops a looping small model early
    // encoder window: 50 frames per second of audio (1500 = 30 s), plus a margin; whisper needs a reasonable minimum
    const double seconds = double(n) / WHISPER_SAMPLE_RATE;
    p.audio_ctx = std::min(1500, std::max(384, int(std::ceil(seconds * 50.0)) + 128));
    if (whisper_full(ctx, p, pcm, n) != 0) return "";
    std::string text;
    const int segs = whisper_full_n_segments(ctx);
    for (int i = 0; i < segs; i++) text += whisper_full_get_segment_text(ctx, i);
    return collapse_repeats(clean(text));
}

void free_model(void *handle) {
    if (handle) whisper_free(static_cast<whisper_context *>(handle));
}

} // namespace daftari
