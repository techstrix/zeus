// Host-side verification of the Zeus engine.
//
// Builds the exact same zeus_transcriber.cpp that ships in the APK, so accuracy and
// the partial-result strategy can be checked without an Android device or emulator.
//
//   ./gradlew fetchModel fetchWhisperCpp
//   cmake -S tools/hosttest -B /tmp/zeus-host
//   cmake --build /tmp/zeus-host -j
//   /tmp/zeus-host/hosttest app/src/main/assets/models/ggml-tiny.en-q5_1.bin \
//       app/src/main/cpp/whisper.cpp/samples/jfk.wav
//
// For the cost model that shapes the design, see scaling.cpp.

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

#include "zeus_transcriber.h"

namespace {

constexpr int kSampleRate = 16000;

int g_failures = 0;

void check(bool ok, const std::string& what) {
    std::printf("  [%s] %s\n", ok ? " ok " : "FAIL", what.c_str());
    if (!ok) g_failures++;
}

std::string lower(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

bool contains(const std::string& haystack, const std::string& needle) {
    return lower(haystack).find(lower(needle)) != std::string::npos;
}

double since(const std::chrono::steady_clock::time_point& t0) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
}

// Minimal RIFF/WAVE reader for uncompressed 16-bit PCM.
bool read_wav_mono16(const char* path, std::vector<int16_t>& out, int& sample_rate) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    char riff[4], size[4], wave[4];
    in.read(riff, 4);
    in.read(size, 4);
    in.read(wave, 4);
    if (in.gcount() != 4 || std::memcmp(riff, "RIFF", 4) != 0 ||
        std::memcmp(wave, "WAVE", 4) != 0) {
        std::printf("  not a RIFF/WAVE file\n");
        return false;
    }

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
            if (!have_format) std::printf("  expected mono\n");
        } else if (std::memcmp(id, "data", 4) == 0) {
            std::vector<int16_t> data(n / 2);
            in.read(reinterpret_cast<char*>(data.data()), n);
            data.resize(in.gcount() / 2);
            out = std::move(data);
        } else {
            in.seekg(n, std::ios::cur);
            if (n & 1) in.seekg(1, std::ios::cur);  // word alignment
        }
    }
    return have_format && !out.empty();
}

struct Run {
    std::string text;
    double seconds = 0.0;
    int decodes = 0;
};

void report(const char* name, const Run& r) {
    std::printf("  %-26s %2d decodes  %6.2f s  \"%s\"\n", name, r.decodes, r.seconds,
                r.text.c_str());
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::printf("usage: %s <model.bin> <wav> [language] [budget_seconds]\n", argv[0]);
        return 2;
    }
    const char* model_path = argv[1];
    const std::string language = (argc > 3) ? argv[3] : "en";
    const double budget_seconds = (argc > 4) ? std::atof(argv[4]) : 6.0;

    std::vector<int16_t> pcm;
    int sample_rate = 0;
    if (!read_wav_mono16(argv[2], pcm, sample_rate)) {
        std::printf("FAIL: cannot read %s\n", argv[2]);
        return 1;
    }
    const std::vector<int16_t> full = pcm;
    const double full_seconds = static_cast<double>(full.size()) / sample_rate;

    // Accuracy is judged on the whole file; only the strategy comparison below is
    // truncated, because a slow host would otherwise spend many minutes on it.
    pcm.resize(static_cast<size_t>(budget_seconds * sample_rate));

    std::printf("input: %s (%d Hz, %.1f s)\n", argv[2], sample_rate, full_seconds);
    std::printf("strategy comparison uses the first %.1f s\n\n", budget_seconds);

    std::string error;
    const auto t0 = std::chrono::steady_clock::now();
    zeus::WhisperTranscriber* t = zeus::WhisperTranscriber::create(model_path, 4, &error);
    if (t == nullptr) {
        std::printf("FAIL: %s\n", error.c_str());
        return 1;
    }
    std::printf("model loaded in %.2f s, %d threads\n\n", since(t0), t->threads());

    // --- accuracy on the whole file ---------------------------------------
    zeus::TranscribeOptions one_shot;
    one_shot.language = language;
    const auto t1 = std::chrono::steady_clock::now();
    const std::string text = t->transcribe(
        const_cast<int16_t*>(full.data()), static_cast<int>(full.size()), one_shot);
    const double one_shot_seconds = since(t1);

    std::printf("full decode of the whole file (the accuracy reference)\n");
    std::printf("  \"%s\"\n", text.c_str());
    std::printf("  %.2f s for %.1f s of audio (%.2fx slower than real time)\n\n",
                one_shot_seconds, full_seconds, one_shot_seconds / full_seconds);

    // JFK: "And so my fellow Americans, ask not what your country can do for you,
    // ask what you can do for your country."
    std::printf("accuracy (tiny.en q5_1)\n");
    check(contains(text, "fellow americans"), "recognises 'fellow Americans'");
    check(contains(text, "ask not what your country can do for you"), "recognises clause 1");
    check(contains(text, "ask what you can do for your country"), "recognises clause 2");
    check(text.front() != ' ' && text.back() != ' ', "no leading/trailing whitespace");
    check(text.find("  ") == std::string::npos, "no doubled spaces");
    check(text.find('\n') == std::string::npos, "no newlines");

    // --- partial strategies ------------------------------------------------
    // Both re-decode everything said so far; the only difference is how many
    // times, which is the only thing that costs anything (see scaling.cpp).
    std::printf("\npartial-result strategies over %.1f s of speech\n", budget_seconds);

    Run naive;
    {
        const auto s = std::chrono::steady_clock::now();
        for (int ms = 500; ms <= budget_seconds * 1000; ms += 500) {
            zeus::TranscribeOptions o;
            o.language = language;
            naive.text = t->transcribe(pcm.data(), ms * kSampleRate / 1000, o);
            naive.decodes++;
        }
        naive.seconds = since(s);
    }
    report("fixed 500 ms tick", naive);

    Run paced;
    {
        const auto s = std::chrono::steady_clock::now();
        // Approximates RecognitionSession: an interval floor plus a pause
        // trigger, i.e. far fewer decodes for the same audio.
        const int interval_ms = 2000;
        for (int ms = interval_ms; ms <= budget_seconds * 1000; ms += interval_ms) {
            zeus::TranscribeOptions o;
            o.language = language;
            paced.text = t->transcribe(pcm.data(), ms * kSampleRate / 1000, o);
            paced.decodes++;
        }
        // The final pass is what the user reads.
        zeus::TranscribeOptions o;
        o.language = language;
        paced.text = t->transcribe(pcm.data(), static_cast<int>(pcm.size()), o);
        paced.decodes++;
        paced.seconds = since(s);
    }
    report("pause/interval paced", paced);

    std::printf("\nstrategy checks\n");
    check(paced.decodes < naive.decodes, "paced issues fewer decodes");
    check(paced.seconds < naive.seconds, "paced costs less wall time");
    check(contains(paced.text, "fellow americans"),
          "paced transcript still recognises 'fellow Americans'");

    delete t;

    std::printf("\n%s (%d failure%s)\n", g_failures == 0 ? "PASS" : "FAIL", g_failures,
                g_failures == 1 ? "" : "s");
    return g_failures == 0 ? 0 : 1;
}