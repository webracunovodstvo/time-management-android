package rs.halotelefon

import android.content.Context
import java.io.File

object ModelManager {
    private const val ASSET_PATH =
        "models/ggml-whisper-small-sr-q5_0.bin"

    private const val FILE_NAME =
        "ggml-whisper-small-sr-q5_0.bin"

    private const val OLD_FILE_NAME =
        "ggml-base-q5_1.bin"

    fun ensureModel(context: Context): File {
        val dir =
            File(
                context.filesDir,
                "models"
            ).apply {
                mkdirs()
            }

        // App updates keep filesDir. Delete the old generic model once the
        // Serbian model is available so the phone does not waste ~57 MB.
        val old =
            File(
                dir,
                OLD_FILE_NAME
            )

        val target =
            File(
                dir,
                FILE_NAME
            )

        if (
            target.exists() &&
            target.length() > 150_000_000L
        ) {
            runCatching {
                old.delete()
            }
            return target
        }

        context.assets
            .open(ASSET_PATH)
            .use { input ->
                target.outputStream()
                    .buffered()
                    .use { output ->
                        input.copyTo(output)
                    }
            }

        require(
            target.length() > 150_000_000L
        ) {
            "Serbian Whisper model nije ispravno kopiran."
        }

        runCatching {
            old.delete()
        }

        return target
    }
}
