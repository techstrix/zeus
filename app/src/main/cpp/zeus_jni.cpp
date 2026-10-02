// Thin JNI shim over WhisperTranscriber. All logic lives in zeus_transcriber.cpp
// so it can be tested on the host without an Android device.

#include <jni.h>

#include <string>
#include <vector>

#include "zeus_transcriber.h"

namespace {

void throw_java(JNIEnv* env, const char* class_name, const char* message) {
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

zeus::WhisperTranscriber* as_transcriber(jlong handle) {
    return reinterpret_cast<zeus::WhisperTranscriber*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_xorbi_zeus_WhisperEngine_nativeLoadModel(JNIEnv* env,
                                                   jclass /* clazz */,
                                                   jstring model_path,
                                                   jint threads) {
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0;
    }

    std::string error;
    zeus::WhisperTranscriber* t =
        zeus::WhisperTranscriber::create(path, static_cast<int>(threads), &error);
    env->ReleaseStringUTFChars(model_path, path);

    if (t == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", error.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(t);
}

JNIEXPORT void JNICALL
Java_com_xorbi_zeus_WhisperEngine_nativeFree(JNIEnv* /* env */,
                                              jclass /* clazz */,
                                              jlong handle) {
    delete as_transcriber(handle);
}

// pcm is a java short[]; `length` samples starting at index 0 are decoded.
// The caller owns the array, so the same backing array is reused for every
// decode of an utterance.
//
// Note there is deliberately no offset/duration parameter. Measured on this
// engine, whisper_full() costs the same for 0.5 s of audio as for 11 s because
// every utterance is padded to a 30-second encoder context, so decoding only the
// new audio would save nothing while costing accuracy at the seams.
JNIEXPORT jstring JNICALL
Java_com_xorbi_zeus_WhisperEngine_nativeTranscribe(JNIEnv* env,
                                                    jclass /* clazz */,
                                                    jlong handle,
                                                    jshortArray pcm,
                                                    jint length,
                                                    jstring language,
                                                    jboolean translate,
                                                    jstring initial_prompt) {
    zeus::WhisperTranscriber* t = as_transcriber(handle);
    if (t == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "engine released");
        return nullptr;
    }
    if (pcm == nullptr) {
        throw_java(env, "java/lang/NullPointerException", "pcm");
        return nullptr;
    }

    const jsize array_length = env->GetArrayLength(pcm);
    if (length < 0 || length > array_length) {
        throw_java(env, "java/lang/IndexOutOfBoundsException", "length out of pcm bounds");
        return nullptr;
    }

    zeus::TranscribeOptions options;
    if (language != nullptr) {
        const char* lang = env->GetStringUTFChars(language, nullptr);
        if (lang != nullptr) {
            options.language = lang;
            env->ReleaseStringUTFChars(language, lang);
        }
    }
    options.translate = (translate == JNI_TRUE);

    std::string prompt;
    if (initial_prompt != nullptr) {
        const char* p = env->GetStringUTFChars(initial_prompt, nullptr);
        if (p != nullptr) {
            prompt = p;
            env->ReleaseStringUTFChars(initial_prompt, p);
        }
    }
    options.initial_prompt = prompt;

    jshort* elements = env->GetShortArrayElements(pcm, nullptr);
    if (elements == nullptr) {
        // OutOfMemoryError already pending.
        return nullptr;
    }

    const std::string text =
        t->transcribe(reinterpret_cast<const int16_t*>(elements),
                      static_cast<int>(length),
                      options);

    env->ReleaseShortArrayElements(pcm, elements, JNI_ABORT);

    return env->NewStringUTF(text.c_str());
}

JNIEXPORT jint JNICALL
Java_com_xorbi_zeus_WhisperEngine_nativeThreads(JNIEnv* /* env */,
                                                 jclass /* clazz */,
                                                 jlong handle) {
    zeus::WhisperTranscriber* t = as_transcriber(handle);
    return (t != nullptr) ? t->threads() : 0;
}

JNIEXPORT jdouble JNICALL
Java_com_xorbi_zeus_WhisperEngine_nativeLastRtf(JNIEnv* /* env */,
                                                jclass /* clazz */,
                                                jlong handle) {
    zeus::WhisperTranscriber* t = as_transcriber(handle);
    return (t != nullptr) ? t->last_rtf() : 0.0;
}

}  // extern "C"