package rs.halotelefon

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import com.whispercpp.whisper.WhisperContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

class CarVoiceController(
    private val carContext: CarContext,
    private val onState: (State) -> Unit
) {
    data class Candidate(
        val key: String,
        val name: String,
        val number: String,
        val score: Double,
        val uses: Int
    )

    data class State(
        val status: String = "Spreman. Dodirni mikrofon.",
        val spoken: String = "",
        val candidates: List<Candidate> = emptyList(),
        val selectedIndex: Int = 0,
        val listening: Boolean = false
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val learningStore = LearningStore(carContext)
    private var whisper: WhisperContext? = null
    private var state = State()

    @Volatile
    private var recording = false

    fun select(index: Int) {
        if (state.candidates.isEmpty()) return
        val safe = index.coerceIn(0, state.candidates.lastIndex)
        update(
            state.copy(
                selectedIndex = safe,
                status = "Izabrano: " + state.candidates[safe].name +
                    ". Dodirni mikrofon i reci MOŽE, OK ili ZOVI."
            )
        )
    }

    fun listen() {
        if (recording) return

        if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            update(state.copy(status = "Dozvoli mikrofon u aplikaciji na telefonu."))
            return
        }
        if (carContext.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            update(state.copy(status = "Dozvoli kontakte u aplikaciji na telefonu."))
            return
        }

        recording = true
        update(state.copy(status = "Slušam…", listening = true))

        executor.execute {
            try {
                val audio = captureUtterance()
                if (audio == null || audio.isEmpty()) {
                    update(state.copy(status = "Nisam čuo govor. Pokušaj ponovo.", listening = false))
                    return@execute
                }

                if (state.candidates.isNotEmpty()) {
                    val commandText = transcribe(
                        audio,
                        "zovi, može, ok, okej, pozovi"
                    )

                    if (isConfirmation(commandText)) {
                        confirm()
                        return@execute
                    }

                    val nameForms = recognizeNameForms(audio)

                    if (nameForms.isEmpty()) {
                        update(
                            state.copy(
                                status = "Nisam razumeo. Reci MOŽE, OK, ZOVI ili drugo ime.",
                                listening = false
                            )
                        )
                        return@execute
                    }

                    if (nameForms.any { isConfirmation(it) }) {
                        confirm()
                        return@execute
                    }

                    rankNewName(nameForms)
                } else {
                    val nameForms = recognizeNameForms(audio)

                    if (nameForms.isEmpty()) {
                        update(
                            state.copy(
                                status = "Nisam razumeo. Pokušaj ponovo.",
                                listening = false
                            )
                        )
                        return@execute
                    }

                    rankNewName(nameForms)
                }
            } catch (t: Throwable) {
                update(state.copy(status = "Greška: " + (t.message ?: "nepoznata"), listening = false))
            } finally {
                recording = false
            }
        }
    }

    private fun captureUtterance(): FloatArray? {
        val manager = carContext.getSystemService(AudioManager::class.java)
            ?: return null
        val record = CarAudioRecord.create(carContext)

        val attributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .build()

        val focus = AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        )
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    runCatching { record.stopRecording() }
                }
            }
            .build()

        if (
            manager.requestAudioFocus(focus) !=
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        ) {
            return null
        }

        val commandMode = state.candidates.isNotEmpty()
        val nameSegmenter = NameSegmenter()
        val confirmationSegmenter = ConfirmationSegmenter()
        val bytes = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        var result: FloatArray? = null

        try {
            record.startRecording()

            while (true) {
                val count = record.read(bytes, 0, bytes.size)
                if (count <= 0) break

                val samples = FloatArray(count / 2)
                var p = 0
                var sampleIndex = 0

                while (p + 1 < count) {
                    val hi = bytes[p].toInt() and 0xff
                    val lo = bytes[p + 1].toInt() and 0xff
                    val value = ((hi shl 8) or lo).toShort()
                    samples[sampleIndex++] = value / 32768f
                    p += 2
                }

                if (commandMode) {
                    result = confirmationSegmenter.accept(samples)
                    if (result != null || confirmationSegmenter.timedOut) break
                } else {
                    result = nameSegmenter.accept(samples)
                    if (result != null || nameSegmenter.timedOut) break
                }
            }
        } finally {
            runCatching { record.stopRecording() }
            manager.abandonAudioFocusRequest(focus)
        }

        return result
    }

    private fun transcribe(audio: FloatArray, prompt: String): String {
        val ctx = whisper ?: WhisperContext(
            ModelManager.ensureModel(carContext).absolutePath
        ).also { whisper = it }

        val prefix = FloatArray((16_000 * 0.35).toInt())
        val suffix = FloatArray((16_000 * 0.55).toInt())
        val padded = FloatArray(prefix.size + audio.size + suffix.size)
        audio.copyInto(padded, prefix.size)

        return ctx.transcribe(
            samples = padded,
            language = "sr",
            initialPrompt = prompt,
            threads = 4
        ).trim()
    }

    private fun recognizeNameForms(
        audio: FloatArray
    ): List<String> {
        val contacts =
            ContactRepository(carContext.contentResolver).load()
        val forms = ArrayList<String>(2)

        val primary = transcribe(
            audio,
            "Ime i prezime osobe iz telefonskog imenika u Srbiji."
        ).trim()

        if (primary.isNotBlank()) {
            forms.add(primary)
        }

        val primaryScore = if (primary.isBlank()) {
            0.0
        } else {
            ContactMatcher(learningStore)
                .rank(primary, contacts, 1)
                .firstOrNull()
                ?.score ?: 0.0
        }

        val shortAudio =
            audio.size.toDouble() / 16_000.0 <= 1.35

        if (
            shortAudio ||
            primary.isBlank() ||
            primaryScore < 0.78
        ) {
            val contactAware = transcribe(
                audio,
                buildShortNamePrompt(contacts)
            ).trim()

            if (
                contactAware.isNotBlank() &&
                contactAware !in forms
            ) {
                forms.add(contactAware)
            }
        }

        return forms
    }

    private fun buildShortNamePrompt(
        contacts: List<ContactPhone>
    ): String {
        val orderedContacts = contacts.sortedWith(
            compareByDescending<ContactPhone> {
                learningStore.totalUses(it.lookupKey)
            }.thenBy {
                it.displayName.length
            }
        )

        val tokens = LinkedHashSet<String>()

        for (contact in orderedContacts) {
            val clean = contact.displayName
                .replace(
                    Regex("[#@()\\[\\]{}.,;:_/\\\\|-]+"),
                    " "
                )
                .replace(Regex("\\s+"), " ")
                .trim()

            for (token in clean.split(' ')) {
                val candidate = token.trim()
                if (candidate.length in 2..12) {
                    val key =
                        SerbianNormalizer.normalize(candidate)
                    if (key.isNotBlank()) {
                        tokens.add(candidate)
                    }
                }
            }
        }

        val prefix =
            "Moguća imena kontakata. Izgovor je na srpskom: "
        val builder = StringBuilder(prefix)

        for (token in tokens) {
            if (builder.length + token.length + 2 > 1600) {
                break
            }
            if (builder.length > prefix.length) {
                builder.append(", ")
            }
            builder.append(token)
        }

        return builder.toString()
    }

    private fun isConfirmation(raw: String): Boolean {
        val normalized = normalizeCommand(raw)
        if (normalized.isBlank()) return false

        return normalized
            .split(' ')
            .filter { it.isNotBlank() }
            .any { word ->
                when {
                    word == "ok" || word == "okej" || word == "okay" ||
                        word == "oke" || word == "okey" -> true
                    word == "zovi" || word == "pozovi" || word == "zov" ||
                        word == "zove" || word.endsWith("zovi") -> true
                    word == "moze" || word == "mozes" || word == "mozeh" -> true
                    editDistanceAtMostOne(word, "zovi") -> true
                    editDistanceAtMostOne(word, "moze") -> true
                    else -> false
                }
            }
    }

    private fun normalizeCommand(raw: String): String {
        var value = raw
            .lowercase(Locale.ROOT)
            .replace("може", "moze")
            .replace("зови", "zovi")
            .replace("позови", "pozovi")
            .replace("океј", "okej")
            .replace("ок", "ok")
            .replace('đ', 'd')

        value = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

        return value
            .replace(Regex("[^a-z ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1) return false

        var i = 0
        var j = 0
        var edits = 0

        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }

            edits++
            if (edits > 1) return false

            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> {
                    i++
                    j++
                }
            }
        }

        if (i < a.length || j < b.length) edits++
        return edits <= 1
    }

    private fun rankNewName(formsRaw: List<String>) {
        val forms = formsRaw
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val contacts = ContactRepository(carContext.contentResolver).load()
        val ranked = ContactMatcher(learningStore).rankBestOf(forms, contacts, 30)
        val best = ranked.firstOrNull()

        if (best == null || best.score < 0.58) {
            update(
                State(
                    status = "Nisam našao kontakt za: " + forms.joinToString(" / "),
                    spoken = forms.joinToString(" / ")
                )
            )
            return
        }

        val floor = maxOf(0.58, best.score - 0.16)
        val plausible = ranked
            .filter { it.score >= floor }
            .distinctBy {
                SerbianNormalizer.normalize(it.contact.displayName) + "|" +
                    it.contact.number.filter(Char::isDigit).takeLast(12)
            }

        val ordered = plausible
            .sortedWith(
                compareByDescending<ContactCandidate> {
                    learningStore.totalUses(it.contact.lookupKey)
                }.thenByDescending {
                    learningStore.lastUsed(it.contact.lookupKey)
                }.thenByDescending {
                    it.score
                }
            )
            .take(5)
            .ifEmpty { listOf(best) }

        val bestCandidate = ordered.maxWithOrNull(
            compareBy<ContactCandidate> { it.score }
                .thenBy { learningStore.totalUses(it.contact.lookupKey) }
        ) ?: ordered.first()

        val candidates = ordered.map {
            Candidate(
                key = it.contact.lookupKey,
                name = it.contact.displayName,
                number = it.contact.number,
                score = it.score,
                uses = learningStore.totalUses(it.contact.lookupKey)
            )
        }

        val selectedIndex = ordered.indexOfFirst {
            it.contact.lookupKey == bestCandidate.contact.lookupKey &&
                it.contact.number == bestCandidate.contact.number
        }.coerceAtLeast(0)

        update(
            State(
                status = "Označen: " + candidates[selectedIndex].name +
                    ". Dodirni mikrofon i reci MOŽE, OK ili ZOVI. Za drugi kontakt izgovori drugo ime.",
                spoken = forms.firstOrNull().orEmpty(),
                candidates = candidates,
                selectedIndex = selectedIndex,
                listening = false
            )
        )
    }

    private fun confirm() {
        val candidate = state.candidates.getOrNull(state.selectedIndex)
        if (candidate == null) {
            update(state.copy(status = "Nema izabranog kontakta.", listening = false))
            return
        }

        if (carContext.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            update(state.copy(status = "Dozvoli pozive u aplikaciji na telefonu.", listening = false))
            return
        }

        if (state.spoken.isNotBlank()) {
            learningStore.record(state.spoken, candidate.key)
        }

        update(
            state.copy(
                status = "Pozivam " + candidate.name,
                listening = false
            )
        )
        CallPlacer.call(carContext, candidate.number)
    }

    private fun update(newState: State) {
        state = newState
        main.post { onState(newState) }
    }

    fun close() {
        runCatching { whisper?.close() }
        runCatching { learningStore.close() }
        executor.shutdownNow()
    }
}
