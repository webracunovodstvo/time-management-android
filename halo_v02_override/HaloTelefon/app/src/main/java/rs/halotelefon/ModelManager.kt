package rs.halotelefon

import android.content.Context
import java.io.File

object ModelManager {
    private const val ASSET_PATH = "models/ggml-base-q5_1.bin"
    private const val FILE_NAME = "ggml-base-q5_1.bin"

    fun ensureModel(context: Context): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val target = File(dir, FILE_NAME)
        if (target.exists() && target.length() > 1_000_000) return target

        context.assets.open(ASSET_PATH).use { input ->
            target.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        return target
    }
}
