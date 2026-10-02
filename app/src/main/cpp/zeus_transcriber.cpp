#include "zeus_transcriber.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

namespace zeus {

namespace {

// Whisper consumes -1.0f..1.0f floats.
constexpr float kInt16Scale = 1.0f / 32768.0f;

// Whisper's native rate. Audio arriving at another rate is resampled by
// AudioCapture, so the engine can assume this.
constexpr int kSampleRate = 16000;

// Collapses the runs of whitespace Whisper emits between segments, and strips
// the leading space the first segment always starts with.
std::string tidy(const std::string& in) {
    std::string out;
    out.reserve(in.size());

    bool pending_space = false;
    bool seen_content = false;

    for (char c : in) {
        const bool is_space = (c == ' ' || c == '\t' || c == '\n' || c == '\r');
        if (is_space) {
            pending_space = seen_content;
            continue;
        }
        if (pending_space) {
            out.push_back(' ');
            pending_space = false;
        }
        out.push_back(c);
        seen_content = true;
    }

    // Keep sentence-final punctuation that Whisper emits without a space after it.
    return out;
}

}  // namespace

WhisperTranscriber* WhisperTranscriber::create(const char* model_path,
                                               int threads,
                                               std::string* error) {
    if (model_path == nullptr || *model_path == '\0') {
        if (error) *error = "no model path";
        return nullptr;
    }

    whisper_context_params cparams = whisper_context_default_params();
    // TVs have no dependable GPU compute stack and ggml's Vulkan backend is not
    // worth the crash risk. CPU is fast enough for these model sizes.
    cparams.use_gpu = false;
    cparams.flash_attn = true;

    whisper_context* ctx = whisper_init_from_file_with_params(model_path, cparams);
    if (ctx == nullptr) {
        if (error) {
            *error = std::string("failed to load model: ") + model_path;
        }
        return nullptr;
    }

    auto* t = new (std::nothrow) WhisperTranscriber();
    if (t == nullptr) {
        whisper_free(ctx);
        if (error) *error = "out of memory";
        return nullptr;
    }

    t->ctx_ = ctx;
    // Four threads is the sweet spot: beyond that, ggml's synchronisation
    // overhead eats the gain on the small core counts TVs have.
    t->threads_ = std::max(1, std::min(threads, 8));
    return t;
}

WhisperTranscriber::~WhisperTranscriber() {
    if (ctx_ != nullptr) {
        whisper_free(ctx_);
        ctx_ = nullptr;
    }
}

std::string WhisperTranscriber::transcribe(const int16_t* pcm,
                                           int n_samples,
                                           const TranscribeOptions& options) {
    if (ctx_ == nullptr || pcm == nullptr || n_samples <= 0) {
        return std::string();
    }

    const auto started = std::chrono::steady_clock::now();

    scratch_.resize(static_cast<size_t>(n_samples));
    for (int i = 0; i < n_samples; ++i) {
        scratch_[static_cast<size_t>(i)] = static_cast<float>(pcm[i]) * kInt16Scale;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    params.n_threads = threads_;
    params.translate = options.translate;
    params.detect_language = options.language.empty();
    params.language = options.language.empty() ? nullptr : options.language.c_str();

    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = false;

    // We already re-decode the whole utterance on every partial, so Whisper must
    // not carry its own decoded text across calls; we hand it the previous
    // transcript explicitly instead.
    params.no_context = true;
    params.initial_prompt = options.initial_prompt.empty()
                                ? nullptr
                                : options.initial_prompt.c_str();
    params.carry_initial_prompt = false;

    // Greedy with no fallback sampling. Whisper's accuracy comes from model size
    // and prompt context, not from beam search; staying greedy is what keeps a
    // partial decode fast enough to run every 500 ms.
    params.temperature = 0.0f;
    params.greedy.best_of = 1;

    if (whisper_full(ctx_, params, scratch_.data(), n_samples) != 0) {
        return std::string();
    }

    const int n_segments = whisper_full_n_segments(ctx_);
    std::string text;
    text.reserve(256);
    for (int i = 0; i < n_segments; ++i) {
        const char* seg = whisper_full_get_segment_text(ctx_, i);
        if (seg != nullptr) {
            text.append(seg);
        }
    }

    const auto finished = std::chrono::steady_clock::now();
    const double seconds =
        std::chrono::duration<double>(finished - started).count();
    const double audio_seconds = static_cast<double>(n_samples) / kSampleRate;
    last_rtf_ = (audio_seconds > 0.0) ? seconds / audio_seconds : 0.0;

    return tidy(text);
}

}  // namespace zeus