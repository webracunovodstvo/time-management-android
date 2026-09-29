package com.whispercpp.whisper

class WhisperContext(modelPath: String) : AutoCloseable {
    private var ptr: Long = WhisperNative.initContext(modelPath)

    init {
        require(ptr != 0L) { "Whisper model nije mogao da se učita: $modelPath" }
    }

    @Synchronized
    fun transcribe(
        samples: FloatArray,
        language: String = "sr",
        initialPrompt: String = "",
        threads: Int = WhisperCpuConfig.preferredThreadCount
    ): String {
        check(ptr != 0L) { "WhisperContext je zatvoren" }
        return WhisperNative.transcribe(ptr, samples, language, initialPrompt, threads)
    }

    @Synchronized
    override fun close() {
        if (ptr != 0L) {
            WhisperNative.freeContext(ptr)
            ptr = 0L
        }
    }
}

internal object WhisperNative {
    init { System.loadLibrary("halowhisper") }
    external fun initContext(modelPath: String): Long
    external fun freeContext(contextPtr: Long)
    external fun transcribe(
        contextPtr: Long,
        samples: FloatArray,
        language: String,
        initialPrompt: String,
        threads: Int
    ): String
}
