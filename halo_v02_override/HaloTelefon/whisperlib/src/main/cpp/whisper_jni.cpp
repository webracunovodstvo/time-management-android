#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperNative_initContext(
        JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperNative_freeContext(
        JNIEnv *, jobject, jlong contextPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (ctx) whisper_free(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperNative_transcribe(
        JNIEnv *env,
        jobject,
        jlong contextPtr,
        jfloatArray samples,
        jstring language,
        jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(contextPtr);
    if (!ctx) return env->NewStringUTF("");

    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    const jsize n = env->GetArrayLength(samples);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = lang;
    params.n_threads = threads;
    params.no_context = true;
    params.single_segment = true;
    params.suppress_blank = true;
    params.no_timestamps = true;
    params.max_tokens = 16;

    std::string result;
    if (whisper_full(ctx, params, pcm, n) == 0) {
        const int count = whisper_full_n_segments(ctx);
        for (int i = 0; i < count; ++i) {
            const char *part = whisper_full_get_segment_text(ctx, i);
            if (part) result += part;
        }
    }

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    return env->NewStringUTF(result.c_str());
}
