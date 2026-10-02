// Zeus on-device speech-to-text engine.
//
// Deliberately free of any JNI/Android types so the exact same translation unit
// can be compiled for the host test harness (tools/hosttest). zeus_jni.cpp is a
// thin shim over this.

#ifndef ZEUS_TRANSCRIBER_H
#define ZEUS_TRANSCRIBER_H

#include <cstdint>
#include <string>
#include <vector>

#include "whisper.h"

namespace zeus {

struct TranscribeOptions {
    // Whisper language code, e.g. "en". Empty means "let Whisper detect".
    std::string language = "en";
    bool translate = false;

    // Previously emitted text for the same utterance. Passed to Whisper as the
    // decoder's initial prompt so consecutive decodes of the same growing
    // utterance settle on one continuous transcript instead of restating and
    // rewording themselves.
    std::string initial_prompt;
};

// One loaded Whisper model. A single instance is shared process-wide for the
// lifetime of the app: loading a model costs seconds, decoding costs
// milliseconds.
class WhisperTranscriber {
public:
    // Returns nullptr and fills `error` if the model cannot be loaded.
    static WhisperTranscriber* create(const char* model_path, int threads, std::string* error);

    ~WhisperTranscriber();

    WhisperTranscriber(const WhisperTranscriber&) = delete;
    WhisperTranscriber& operator=(const WhisperTranscriber&) = delete;

    // pcm: mono 16-bit little-endian samples at 16 kHz, as produced by AudioRecord.
    // n_samples: number of samples. Returns the trimmed transcript.
    std::string transcribe(const int16_t* pcm, int n_samples, const TranscribeOptions& options);

    int threads() const { return threads_; }

    // Real-time factor of the most recent transcribe() call, for the setup screen.
    double last_rtf() const { return last_rtf_; }

private:
    WhisperTranscriber() = default;

    whisper_context* ctx_ = nullptr;
    int threads_ = 4;
    double last_rtf_ = 0.0;
    std::vector<float> scratch_;
};

}  // namespace zeus

#endif  // ZEUS_TRANSCRIBER_H