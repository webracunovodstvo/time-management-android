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

    private val dir =
        File(context.filesDir, "command_profile").apply {
            mkdirs()
        }

    private val templates =
        LearnedVoiceCommand.values().associateWith {
            mutableListOf<Array<FloatArray>>()
        }

    init {
        reload()
    }

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
        LearnedVoiceCommand.values().joinToString("  •  ") {
            it.spokenLabel + " " + count(it) + "/3"
        }

    @Synchronized
    fun addSample(
        command: LearnedVoiceCommand,
        audio: FloatArray
    ): Int {
        val features = WakeFeatures.extract(audio)

        // Jedna reč poput "prvi" ili "peti" često traje samo 200-350 ms.
        // V0.20 je odbacivao deo ispravnih uzoraka kao prekratke.
        require(features.size >= 7) {
            "Nisam uhvatio celu reč. Reci „" +
                command.spokenLabel.lowercase() +
                "“ malo jasnije."
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
                for (value in row) {
                    out.writeFloat(value)
                }
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
            return Match(
                command = null,
                matched = false,
                distance = 99.0,
                threshold = 0.0,
                confidence = 0
            )
        }

        val query = WakeFeatures.extract(audio)

        if (query.size < 6 || query.size > 240) {
            return Match(
                command = null,
                matched = false,
                distance = 99.0,
                threshold = 0.0,
                confidence = 0
            )
        }

        data class Candidate(
            val command: LearnedVoiceCommand,
            val distance: Double,
            val threshold: Double,
            val durationOk: Boolean
        )

        val candidates =
            allowed.mapNotNull { command ->
                val list = templates[command].orEmpty()

                if (list.size < 2) {
                    return@mapNotNull null
                }

                val distances =
                    list.map {
                        WakeFeatures.dtw(query, it)
                    }.sorted()

                val distance =
                    when {
                        distances.size >= 3 ->
                            distances[0] * 0.58 +
                                distances[1] * 0.29 +
                                distances[2] * 0.13

                        distances.size == 2 ->
                            distances[0] * 0.68 +
                                distances[1] * 0.32

                        else ->
                            distances[0]
                    }

                val lengths =
                    list.map { it.size }
                        .sorted()

                val medianLength =
                    lengths[lengths.size / 2]
                        .coerceAtLeast(1)

                val ratio =
                    query.size.toDouble() /
                        medianLength.toDouble()

                Candidate(
                    command = command,
                    distance = distance,
                    threshold = threshold(list),
                    durationOk = ratio in 0.45..2.10
                )
            }
                .filter { it.durationOk }
                .sortedBy { it.distance }

        val winner =
            candidates.firstOrNull()
                ?: return Match(
                    null,
                    false,
                    99.0,
                    0.0,
                    0
                )

        val second = candidates.getOrNull(1)

        // Kratke reči mogu biti veoma slične akustički. Ne tražimo više
        // prevelik razmak između prvog i drugog kandidata kao u v0.20.
        val clearWinner =
            second == null ||
                second.distance - winner.distance >= 0.018 ||
                winner.distance <= winner.threshold * 0.88

        val allowedThreshold =
            if (candidates.size == 1) {
                winner.threshold * 1.08
            } else {
                winner.threshold
            }

        val matched =
            winner.distance <= allowedThreshold &&
                clearWinner

        val confidence =
            if (!matched) {
                0
            } else {
                (
                    (1.0 -
                        winner.distance /
                        (allowedThreshold * 1.18)) *
                        100.0
                )
                    .toInt()
                    .coerceIn(0, 100)
            }

        return Match(
            command = winner.command,
            matched = matched,
            distance = winner.distance,
            threshold = allowedThreshold,
            confidence = confidence
        )
    }

    private fun threshold(
        list: List<Array<FloatArray>>
    ): Double {
        if (list.size < 2) {
            return 0.98
        }

        val pairwise =
            mutableListOf<Double>()

        for (i in 0 until list.lastIndex) {
            for (j in i + 1 until list.size) {
                pairwise +=
                    WakeFeatures.dtw(
                        list[i],
                        list[j]
                    )
            }
        }

        if (pairwise.isEmpty()) {
            return 0.98
        }

        val center = median(pairwise)
        val mad =
            median(
                pairwise.map {
                    abs(it - center)
                }
            )

        val learned =
            max(
                center * 1.70,
                center +
                    max(
                        0.20,
                        mad * 4.0
                    )
            )

        return max(
            0.96,
            learned
        ).coerceAtMost(1.14)
    }

    private fun reload() {
        templates.values.forEach {
            it.clear()
        }

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
                    }
                        .getOrNull()
                        ?.let {
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
        if (values.isEmpty()) {
            return 0.0
        }

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
