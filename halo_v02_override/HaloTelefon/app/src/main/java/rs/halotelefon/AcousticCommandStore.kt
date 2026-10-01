package rs.halotelefon

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max

enum class LearnedVoiceCommand(
    val id: String,
    val spokenLabel: String,
    val candidateIndex: Int? = null
) {
    CANCEL("cancel", "OTKAŽI"),
    FIRST("first", "PRVI", 0),
    SECOND("second", "DRUGI", 1),
    THIRD("third", "TREĆI", 2),
    FOURTH("fourth", "ČETVRTI", 3),
    FIFTH("fifth", "PETI", 4)
}

class AcousticCommandStore(context: Context) {
    data class Match(
        val command: LearnedVoiceCommand?,
        val matched: Boolean,
        val distance: Double,
        val threshold: Double,
        val confidence: Int
    )

    private val dir = File(context.filesDir, "command_profile").apply { mkdirs() }
    private val templates =
        LearnedVoiceCommand.values().associateWith {
            mutableListOf<Array<FloatArray>>()
        }

    init { reload() }

    @Synchronized
    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        templates.values.forEach { it.clear() }
    }

    @Synchronized
    fun count(command: LearnedVoiceCommand): Int =
        templates[command]?.size ?: 0

    @Synchronized
    fun isReady(command: LearnedVoiceCommand): Boolean =
        count(command) >= 3

    @Synchronized
    fun allReady(): Boolean =
        LearnedVoiceCommand.values().all(::isReady)

    @Synchronized
    fun summary(): String =
        LearnedVoiceCommand.values().joinToString(" • ") {
            it.spokenLabel + " " + count(it) + "/3"
        }

    @Synchronized
    fun addSample(
        command: LearnedVoiceCommand,
        audio: FloatArray
    ): Int {
        val features = WakeFeatures.extract(audio)
        require(features.size >= 16) {
            "Izgovor je prekratak. Reci samo „" +
                command.spokenLabel.lowercase() +
                "“."
        }
        require(features.size <= 220) {
            "Izgovor je predugačak. Reci samo „" +
                command.spokenLabel.lowercase() +
                "“."
        }

        val list = templates.getValue(command)
        val index = list.size
        val file = File(
            dir,
            command.id + "_" +
                index.toString().padStart(2, '0') +
                ".wf"
        )

        DataOutputStream(
            file.outputStream().buffered()
        ).use { out ->
            out.writeInt(features.size)
            out.writeInt(features[0].size)
            for (row in features) {
                for (v in row) out.writeFloat(v)
            }
        }

        list += features
        return list.size
    }

    @Synchronized
    fun match(
        audio: FloatArray,
        allowed: Set<LearnedVoiceCommand>
    ): Match {
        if (allowed.isEmpty()) {
            return Match(null, false, 99.0, 0.0, 0)
        }

        val query = WakeFeatures.extract(audio)
        if (query.size < 12 || query.size > 240) {
            return Match(null, false, 99.0, 0.0, 0)
        }

        data class Candidate(
            val command: LearnedVoiceCommand,
            val distance: Double,
            val threshold: Double
        )

        val candidates = allowed.mapNotNull { command ->
            val list = templates[command].orEmpty()
            if (list.size < 2) return@mapNotNull null

            val distances =
                list.map {
                    WakeFeatures.dtw(query, it)
                }.sorted()

            val distance =
                if (distances.size >= 2) {
                    (distances[0] + distances[1]) / 2.0
                } else {
                    distances[0]
                }

            Candidate(
                command,
                distance,
                threshold(list)
            )
        }.sortedBy { it.distance }

        val winner =
            candidates.firstOrNull()
                ?: return Match(null, false, 99.0, 0.0, 0)

        val second = candidates.getOrNull(1)

        val clearWinner =
            second == null ||
                winner.distance + 0.035 <= second.distance ||
                winner.distance <= winner.threshold * 0.72

        val matched =
            winner.distance <= winner.threshold &&
                clearWinner

        val confidence =
            if (!matched) {
                0
            } else {
                (
                    (1.0 -
                        winner.distance /
                        (winner.threshold * 1.20)) *
                        100.0
                    )
                    .toInt()
                    .coerceIn(0, 100)
            }

        return Match(
            winner.command,
            matched,
            winner.distance,
            winner.threshold,
            confidence
        )
    }

    private fun threshold(
        list: List<Array<FloatArray>>
    ): Double {
        if (list.size < 2) return 0.90

        val pairwise = mutableListOf<Double>()

        for (i in 0 until list.lastIndex) {
            for (j in i + 1 until list.size) {
                pairwise += WakeFeatures.dtw(
                    list[i],
                    list[j]
                )
            }
        }

        if (pairwise.isEmpty()) return 0.90

        val center = median(pairwise)
        val mad = median(
            pairwise.map {
                abs(it - center)
            }
        )

        val learned =
            max(
                center * 1.50,
                center + max(
                    0.14,
                    mad * 3.5
                )
            )

        return max(0.82, learned)
            .coerceAtMost(1.02)
    }

    private fun reload() {
        templates.values.forEach { it.clear() }

        for (command in LearnedVoiceCommand.values()) {
            dir.listFiles { file ->
                file.name.startsWith(
                    command.id + "_"
                ) &&
                    file.extension == "wf"
            }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    runCatching {
                        DataInputStream(
                            file.inputStream()
                                .buffered()
                        ).use { input ->
                            val rows =
                                input.readInt()
                            val cols =
                                input.readInt()

                            require(
                                rows in 1..260 &&
                                    cols in 1..32
                            )

                            Array(rows) {
                                FloatArray(cols) {
                                    input.readFloat()
                                }
                            }
                        }
                    }.getOrNull()?.let {
                        templates
                            .getValue(command)
                            .add(it)
                    }
                }
        }
    }

    private fun median(
        values: List<Double>
    ): Double {
        if (values.isEmpty()) return 0.0

        val sorted = values.sorted()
        val middle = sorted.size / 2

        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (
                sorted[middle - 1] +
                    sorted[middle]
                ) / 2.0
        }
    }
}
