/*
 * دفتري · tools/whisper-host-test/main.cpp
 * اختبار على الحاسوب (x86_64) لنفس كود التحويل الذي يعمل في التطبيق: يقرأ ملف WAV ‏16 ك.هـ أحادي ويطبع النص والزمن.
 * Host check of the exact transcription code the app ships (cpp/daftari_whisper.cpp): reads a 16 kHz mono 16-bit WAV,
 * prints the Arabic text and the time taken. Build: see README.md next to this file.
 */
#include "daftari_whisper.h"
#include <chrono>
#include <cstdio>
#include <cstring>
#include <vector>

int main(int argc, char **argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: %s model.bin audio.wav [threads]\n", argv[0]); return 2; }
    FILE *f = std::fopen(argv[2], "rb");
    if (!f) return 3;
    std::vector<unsigned char> bytes;
    unsigned char buf[65536]; size_t r;
    while ((r = std::fread(buf, 1, sizeof buf, f)) > 0) bytes.insert(bytes.end(), buf, buf + r);
    std::fclose(f);
    // find the "data" chunk
    size_t pos = 12, data = 0, len = 0;
    while (pos + 8 <= bytes.size()) {
        unsigned sz = bytes[pos + 4] | bytes[pos + 5] << 8 | bytes[pos + 6] << 16 | bytes[pos + 7] << 24;
        if (std::memcmp(&bytes[pos], "data", 4) == 0) { data = pos + 8; len = sz; break; }
        pos += 8 + sz;
    }
    if (!data) return 4;
    std::vector<float> pcm(len / 2);
    for (size_t i = 0; i < pcm.size(); i++) pcm[i] = (short)(bytes[data + 2 * i] | bytes[data + 2 * i + 1] << 8) / 32768.0f;
    int threads = argc > 3 ? std::atoi(argv[3]) : 4;
    auto t0 = std::chrono::steady_clock::now();
    void *m = daftari::load(argv[1]);
    if (!m) return 5;
    auto t1 = std::chrono::steady_clock::now();
    std::string text = daftari::transcribe(m, pcm.data(), (int)pcm.size(), "ar", threads);
    auto t2 = std::chrono::steady_clock::now();
    std::printf("audio %.1fs | load %lld ms | transcribe %lld ms | %d threads\n%s\n", pcm.size() / 16000.0,
        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count(),
        (long long)std::chrono::duration_cast<std::chrono::milliseconds>(t2 - t1).count(), threads, text.c_str());
    daftari::free_model(m);
    return 0;
}
