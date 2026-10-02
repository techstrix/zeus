// Measures how decode cost scales with audio length.
//
// This decides the incremental dictation design. whisper.cpp pads every
// utterance to a 30-second encoder context, so if cost per whisper_full() call
// is roughly constant then re-decoding only the new audio buys nothing, the
// number of calls is the only thing that costs, and partials must be driven by
// pause detection plus a slow interval rather than a fixed 500 ms tick.

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "zeus_transcriber.h"

namespace {

constexpr int kSampleRate = 16000;

bool read_wav_mono16(const char* path, std::vector<int16_t>& out, int& sample_rate) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    char riff[4], size[4], wave[4];
    in.read(riff, 4); in.read(size, 4); in.read(wave, 4);
    if (std::memcmp(riff, "RIFF", 4) != 0 || std::memcmp(wave, "WAVE", 4) != 0) return false;

    bool have_format = false;
    while (in) {
        char id[4];
        uint32_t n = 0;
        in.read(id, 4);
        in.read(reinterpret_cast<char*>(&n), 4);
        if (!in) break;
        if (std::memcmp(id, "fmt ", 4) == 0) {
            std::vector<char> fmt(n);
            in.read(fmt.data(), n);
            sample_rate = *reinterpret_cast<uint32_t*>(fmt.data() + 4);
            have_format = (*reinterpret_cast<uint16_t*>(fmt.data() + 2) == 1);
        } else if (std::memcmp(id, "data", 4) == 0) {
            std::vector<int16_t> data(n / 2);
            in.read(reinterpret_cast<char*>(data.data()), n);
            data.resize(in.gcount() / 2);
            out = std::move(data);
        } else {
            in.seekg(n, std::ios::cur);
            if (n & 1) in.seekg(1, std::ios::cur);
        }
    }
    return have_format && !out.empty();
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::printf("usage: %s <model.bin> <wav> [threads]\n", argv[0]);
        return 2;
    }
    const int threads = (argc > 3) ? std::atoi(argv[3]) : 4;

    std::vector<int16_t> pcm;
    int sample_rate = 0;
    if (!read_wav_mono16(argv[2], pcm, sample_rate)) {
        std::printf("FAIL: cannot read %s\n", argv[2]);
        return 1;
    }

    std::string error;
    zeus::WhisperTranscriber* t = zeus::WhisperTranscriber::create(argv[1], threads, &error);
    if (t == nullptr) {
        std::printf("FAIL: %s\n", error.c_str());
        return 1;
    }
    std::printf("model loaded, %d threads\n\n", t->threads());

    std::printf("  %-14s %-12s %-12s %s\n", "audio", "wall time", "per call", "verbatim?");
    std::printf("  %-14s %-12s %-12s %s\n", "------------", "-----------", "----------", "---------");

    for (double seconds : {0.5, 1.0, 2.0, 4.0, 8.0, 11.0, 15.0}) {
        const size_t n = static_cast<size_t>(seconds * sample_rate);
        if (n > pcm.size()) break;

        zeus::WhisperTranscriber* warm = zeus::WhisperTranscriber::create(argv[1], threads, &error);
        zeus::TranscribeOptions o;
        o.language = "en";
        const auto t0 = std::chrono::steady_clock::now();
        const std::string text = warm->transcribe(pcm.data(), static_cast<int>(n), o);
        const double wall = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - t0).count();

        // Same call a second time, to separate one-off warmup from steady state.
        const auto t1 = std::chrono::steady_clock::now();
        warm->transcribe(pcm.data(), static_cast<int>(n), o);
        const double warm_wall = std::chrono::duration<double>(
            std::chrono::steady_clock::now() - t1).count();

        std::printf("  %5.1f s %-8s %9.2f s %9.2f s   %s\n", seconds, "", wall, warm_wall,
                    text.empty() ? "(silent)" : "yes");
        delete warm;
    }

    std::printf("\n  If 'per call' is flat as audio grows, the 30 s encoder context\n"
                "  dominates and the number of whisper_full() calls is the only cost\n"
                "  that matters.\n");

    delete t;
    return 0;
}