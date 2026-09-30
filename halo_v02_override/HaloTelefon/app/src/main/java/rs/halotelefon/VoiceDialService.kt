package rs.halotelefon

import android.Manifest
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import com.whispercpp.whisper.WhisperContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoiceDialService : Service() {
    companion object {
        const val ACTION_STOP = "rs.halotelefon.STOP"
        const val ACTION_TRAIN_WAKE = "rs.halotelefon.TRAIN_WAKE"
        const val ACTION_TEST_NAME = "rs.halotelefon.TEST_NAME"
        const val ACTION_SELECT_CANDIDATE = "rs.halotelefon.SELECT_CANDIDATE"

        const val EXTRA_LOOKUP_KEY = "lookupKey"
        const val EXTRA_NAME = "name"
        const val EXTRA_NUMBER = "number"

        private const val CHANNEL_ID = "halo_voice"
        private const val NOTIFICATION_ID = 1001
        private const val CANDIDATE_NOTIFICATION_ID = 1002
        private const val CANDIDATE_CHANNEL_ID = "halo_candidates_v1"
        private const val SAMPLE_RATE = 16_000
    }

    private enum class Mode {
        WAIT_WAKE,
        WAIT_NAME,
        WAIT_CONFIRM,
        TRAIN_WAKE
    }

    @Volatile private var mode = Mode.WAIT_WAKE
    @Volatile private var running = false
    @Volatile private var nameInferencePending = false

    private var selectedContact: ContactPhone? = null
    private var selectedSpoken: String = ""
    private var trainRemaining = 0

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val segmentQueue = ArrayBlockingQueue<FloatArray>(3)

    private lateinit var recorder: AudioRecord
    private lateinit var whisper: WhisperContext
    private lateinit var acousticWakeStore: AcousticWakeStore
    private lateinit var learningStore: LearningStore

    private var contactCache: List<ContactPhone>? = null
    private var tone: ToneGenerator? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var screenWakeLock: PowerManager.WakeLock? = null

    @Volatile private var ignoreAudioUntilMs: Long = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> syncScreenWakeLock()
                Intent.ACTION_SCREEN_ON -> releaseScreenWakeLock()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()
        acousticWakeStore = AcousticWakeStore(this)
        learningStore = LearningStore(this)
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 76)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TRAIN_WAKE -> {
                selectedContact = null
                selectedSpoken = ""
                acousticWakeStore.clear()
                mode = Mode.TRAIN_WAKE
                nameInferencePending = false
                trainRemaining = 5
                segmentQueue.clear()
                AppPrefs.setLastWake(this, "Novi lokalni audio profil: 0/5")
                AppPrefs.setStatus(this, "Trening: reci ‘Halo telefon’ 1/5")
            }

            ACTION_TEST_NAME -> {
                selectedContact = null
                selectedSpoken = ""
                mode = Mode.WAIT_NAME
                nameInferencePending = false
                segmentQueue.clear()
                AppPrefs.setStatus(this, "Test imena. Posle tona reci ime i prezime.")
                AppPrefs.setNameDebug(this, "Direktan test imena, wake je preskočen")
            }

            ACTION_SELECT_CANDIDATE -> {
                val key = intent.getStringExtra(EXTRA_LOOKUP_KEY).orEmpty()
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()

                if (key.isNotBlank() && name.isNotBlank() && number.isNotBlank()) {
                    selectedContact = ContactPhone(key, name, number)
                    mode = Mode.WAIT_CONFIRM
                    nameInferencePending = false
                    segmentQueue.clear()
                    AppPrefs.setStatus(
                        this,
                        "Izabrano: " + name + ". Reci ‘može’, ‘ok’ ili ‘zovi’."
                    )
                    updateServiceNotification("Čekam potvrdu: može / ok / zovi")
                    if (running) beepReady()
                }
            }
        }

        if (!running) {
            startVoiceEngine()
        } else {
            when (intent?.action) {
                ACTION_TRAIN_WAKE,
                ACTION_TEST_NAME -> beepReady()
            }
        }

        return START_NOT_STICKY
    }

    private fun startVoiceEngine() {
        startForeground(
            NOTIFICATION_ID,
            serviceNotification("Pokrećem lokalno slušanje…")
        )

        running = true
        AppPrefs.setRunning(this, true)
        AppPrefs.setStatus(this, "Učitavam lokalni model…")
        syncScreenWakeLock()

        inferenceExecutor.execute {
            try {
                val model = ModelManager.ensureModel(this)
                whisper = WhisperContext(model.absolutePath)
                contactCache = loadContactsSafely()
                initRecorder()

                when (mode) {
                    Mode.TRAIN_WAKE -> {
                        AppPrefs.setStatus(this, "Trening: reci ‘Halo telefon’ 1/5")
                        updateServiceNotification("Trening wake profila")
                    }

                    Mode.WAIT_NAME -> {
                        AppPrefs.setStatus(this, "Posle tona reci ime i prezime.")
                        updateServiceNotification("Slušam ime kontakta")
                    }

                    Mode.WAIT_CONFIRM -> {
                        AppPrefs.setStatus(this, "Reci ‘može’, ‘ok’, ‘zovi’ ili drugo ime.")
                        updateServiceNotification("Čekam potvrdu ili novo ime")
                    }

                    Mode.WAIT_WAKE -> {
                        val ready = acousticWakeStore.isReady()
                        AppPrefs.setStatus(
                            this,
                            if (ready) "Aktivno. Reci ‘Halo telefon’."
                            else "Prvo nauči ‘Halo telefon’ 5x."
                        )
                        updateServiceNotification(
                            if (ready) "Slušam: ‘Halo telefon’"
                            else "Čeka trening wake fraze"
                        )
                    }
                }

                startRecordingLoop()

                if (
                    mode == Mode.TRAIN_WAKE ||
                    mode == Mode.WAIT_NAME ||
                    mode == Mode.WAIT_CONFIRM
                ) {
                    beepReady()
                }

                startInferenceLoop()
            } catch (t: Throwable) {
                AppPrefs.setStatus(this, "Greška: " + (t.message ?: "nepoznata"))
                stopSelf()
            }
        }
    }

    private fun initRecorder() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            error("Nema dozvole za mikrofon")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, SAMPLE_RATE * 2)
        )

        require(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord nije inicijalizovan"
        }

        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            }
        }

        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl =
                    AutomaticGainControl.create(recorder.audioSessionId)?.apply {
                        enabled = true
                    }
            }
        }
    }

    private fun startRecordingLoop() {
        audioExecutor.execute {
            val frameShort = ShortArray(320)
            val wakeSegmenter = VoiceSegmenter()
            val speechSegmenter = NameSegmenter()
            var observedMode = mode

            recorder.startRecording()

            while (
                running &&
                recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
            ) {
                val read = recorder.read(
                    frameShort,
                    0,
                    frameShort.size,
                    AudioRecord.READ_BLOCKING
                )

                if (read <= 0) continue

                val currentMode = mode

                if (currentMode != observedMode) {
                    wakeSegmenter.reset()
                    speechSegmenter.reset()
                    observedMode = currentMode
                }

                if (SystemClock.elapsedRealtime() < ignoreAudioUntilMs) {
                    wakeSegmenter.reset()
                    speechSegmenter.reset()
                    continue
                }

                val frame = FloatArray(read) { index ->
                    frameShort[index] / 32768f
                }

                when (currentMode) {
                    Mode.WAIT_NAME,
                    Mode.WAIT_CONFIRM -> {
                        if (nameInferencePending) continue

                        val wasSpeaking = speechSegmenter.speechStarted
                        val segment = speechSegmenter.accept(frame)

                        if (!wasSpeaking && speechSegmenter.speechStarted) {
                            if (currentMode == Mode.WAIT_CONFIRM) {
                                AppPrefs.setStatus(this, "Čujem potvrdu ili novo ime…")
                                updateServiceNotification("Slušam potvrdu ili novo ime…")
                            } else {
                                AppPrefs.setStatus(this, "Čujem ime…")
                                updateServiceNotification("Čujem ime kontakta…")
                            }
                        }

                        if (speechSegmenter.timedOut) {
                            speechSegmenter.reset()

                            if (currentMode == Mode.WAIT_CONFIRM) {
                                AppPrefs.setStatus(
                                    this,
                                    "Čekam: ‘može’, ‘ok’, ‘zovi’ ili drugo ime."
                                )
                                updateServiceNotification("Čekam potvrdu ili novo ime")
                                continue
                            }

                            mode = Mode.WAIT_WAKE
                            observedMode = mode
                            selectedContact = null
                            selectedSpoken = ""
                            AppPrefs.setStatus(
                                this,
                                "Nisam čuo ime. Reci ponovo ‘Halo telefon’."
                            )
                            AppPrefs.setNameDebug(
                                this,
                                "Timeout: govor nije detektovan u 5 s"
                            )
                            updateServiceNotification("Slušam: ‘Halo telefon’")
                            beepError()
                            continue
                        }

                        if (segment != null && segment.size >= 8_000) {
                            nameInferencePending = true

                            if (currentMode == Mode.WAIT_CONFIRM) {
                                AppPrefs.setStatus(this, "Proveravam potvrdu…")
                                AppPrefs.setNameDebug(
                                    this,
                                    "Snimljena potvrda ili novo ime"
                                )
                                updateServiceNotification("Proveravam potvrdu…")
                            } else {
                                AppPrefs.setStatus(this, "Prepoznajem ime…")
                                AppPrefs.setNameDebug(
                                    this,
                                    "Snimljeno ime, pokrećem lokalni Whisper…"
                                )
                                updateServiceNotification("Prepoznajem ime…")
                            }

                            if (!segmentQueue.offer(segment)) {
                                nameInferencePending = false

                                if (currentMode == Mode.WAIT_CONFIRM) {
                                    AppPrefs.setStatus(
                                        this,
                                        "Pokušaj potvrdu ponovo."
                                    )
                                } else {
                                    mode = Mode.WAIT_WAKE
                                    updateServiceNotification(
                                        "Slušam: ‘Halo telefon’"
                                    )
                                }

                                beepError()
                            }
                        }
                    }

                    Mode.WAIT_WAKE,
                    Mode.TRAIN_WAKE -> {
                        val segment = wakeSegmenter.accept(frame, 3.0)

                        if (segment != null && segment.size >= 4_800) {
                            segmentQueue.offer(segment)
                        }
                    }
                }
            }
        }
    }

    private fun startInferenceLoop() {
        while (running) {
            val audio = segmentQueue.poll(1, TimeUnit.SECONDS) ?: continue

            when (mode) {
                Mode.WAIT_WAKE -> handleWake(audio)

                Mode.TRAIN_WAKE -> handleWakeTraining(audio)

                Mode.WAIT_NAME -> {
                    val text = transcribeShort(
                        audio,
                        buildContactPrompt(),
                        "ime"
                    )
                    nameInferencePending = false

                    if (text.isBlank()) {
                        AppPrefs.setLastHeard(
                            this,
                            "(Whisper nije vratio tekst)"
                        )
                        mode = Mode.WAIT_WAKE
                        AppPrefs.setStatus(
                            this,
                            "Nisam razumeo ime. Reci ponovo ‘Halo telefon’."
                        )
                        updateServiceNotification("Slušam: ‘Halo telefon’")
                        beepError()
                        continue
                    }

                    AppPrefs.setLastHeard(this, text)
                    handleName(text)
                }

                Mode.WAIT_CONFIRM -> {
                    // First pass is deliberately command-only. Short words such as
                    // "zovi" must not be sent straight into contact matching.
                    val commandText = transcribeShort(
                        audio,
                        "Komanda za potvrdu telefonskog poziva. Dozvoljene reči su: zovi, može, ok, okej, pozovi.",
                        "komanda"
                    )

                    if (isConfirmation(commandText)) {
                        nameInferencePending = false
                        AppPrefs.setLastHeard(this, commandText)
                        confirmSelectedCall()
                        continue
                    }

                    // Only when the command pass clearly was NOT a confirmation do
                    // we run the same audio as a possible replacement contact name.
                    val nameText = transcribeShort(
                        audio,
                        buildContactPrompt(),
                        "novo ime"
                    )
                    nameInferencePending = false

                    if (nameText.isBlank()) {
                        AppPrefs.setStatus(
                            this,
                            "Nisam razumeo. Reci ‘može’, ‘ok’, ‘zovi’ ili drugo ime."
                        )
                        beepError()
                        continue
                    }

                    // The second ASR pass may actually recognize the command
                    // better than the command-biased pass. Check it again before
                    // ever sending it to contact matching.
                    if (isConfirmation(nameText)) {
                        AppPrefs.setLastHeard(this, nameText)
                        confirmSelectedCall()
                        continue
                    }

                    AppPrefs.setLastHeard(this, nameText)
                    handleName(nameText)
                }
            }
        }
    }

    private fun transcribeShort(
        audio: FloatArray,
        prompt: String,
        label: String
    ): String {
        val padded = padForWhisper(audio)
        val started = SystemClock.elapsedRealtime()

        val text = try {
            whisper.transcribe(
                samples = padded,
                language = "sr",
                initialPrompt = prompt,
                threads = 4
            ).trim()
        } catch (t: Throwable) {
            AppPrefs.setNameDebug(
                this,
                "Whisper greška: " + (t.message ?: "nepoznata")
            )
            ""
        }

        val elapsed = SystemClock.elapsedRealtime() - started

        AppPrefs.setNameDebug(
            this,
            label + " • Whisper " + elapsed + " ms • raw: " +
                if (text.isBlank()) "(prazno)" else text
        )

        return text
    }

    private fun isConfirmation(raw: String): Boolean {
        val normalized = normalizeCommand(raw)
        if (normalized.isBlank()) return false

        val words = normalized
            .split(' ')
            .filter { it.isNotBlank() }

        return words.any { word ->
            when {
                word == "ok" || word == "okej" || word == "okay" -> true
                word == "zovi" || word == "pozovi" || word.endsWith("zovi") -> true
                word == "moze" -> true
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
            .replace('Đ', 'd')

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

    private fun confirmSelectedCall() {
        val contact = selectedContact

        if (contact == null) {
            mode = Mode.WAIT_NAME
            AppPrefs.setStatus(
                this,
                "Nema izabranog kontakta. Izgovori ime ponovo."
            )
            updateServiceNotification("Reci ime ponovo")
            beepError()
            return
        }

        if (
            checkSelfPermission(Manifest.permission.CALL_PHONE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            AppPrefs.setStatus(this, "Nema dozvole za pozivanje")
            beepError()
            return
        }

        if (selectedSpoken.isNotBlank()) {
            runCatching {
                learningStore.record(
                    selectedSpoken,
                    contact.lookupKey
                )
            }
        }

        AppPrefs.setLastMatch(
            this,
            "Potvrđeno: " + contact.displayName
        )
        AppPrefs.setStatus(
            this,
            "Pozivam " + contact.displayName
        )

        sendBroadcast(
            Intent(CandidateActivity.ACTION_CLOSE_PICKER)
                .setPackage(packageName)
        )

        getSystemService(NotificationManager::class.java)
            .cancel(CANDIDATE_NOTIFICATION_ID)

        selectedContact = null
        selectedSpoken = ""
        mode = Mode.WAIT_WAKE
        updateServiceNotification("Slušam: ‘Halo telefon’")
        beepSuccess()

        CallPlacer.call(this, contact.number)
    }

    private fun handleWake(audio: FloatArray) {
        if (!acousticWakeStore.isReady()) {
            AppPrefs.setStatus(
                this,
                "Wake profil nije naučen. Pritisni ‘Nauči izgovor’."
            )
            AppPrefs.setLastWake(
                this,
                "Wake profil: " + acousticWakeStore.count() + "/5"
            )
            return
        }

        val result = runCatching {
            acousticWakeStore.match(audio)
        }.getOrNull() ?: return

        AppPrefs.setLastWake(
            this,
            "Wake " + result.confidence + "% • d=" +
                "%.2f".format(result.distance) + " / " +
                "%.2f".format(result.threshold)
        )

        if (!result.matched) return

        selectedContact = null
        selectedSpoken = ""
        mode = Mode.WAIT_NAME
        nameInferencePending = false
        segmentQueue.clear()

        AppPrefs.setStatus(
            this,
            "Prepoznato ‘Halo telefon’. Posle tona reci ime."
        )
        AppPrefs.setNameDebug(
            this,
            "Čekam ime nakon wake fraze"
        )
        updateServiceNotification(
            "Posle tona reci ime kontakta"
        )
        beepReady()
    }

    private fun handleWakeTraining(audio: FloatArray) {
        val before = acousticWakeStore.count()

        val count = try {
            acousticWakeStore.addSample(audio)
        } catch (t: Throwable) {
            AppPrefs.setStatus(
                this,
                t.message ?: "Ponovi ‘Halo telefon’."
            )
            beepError()
            return
        }

        if (count <= before) return

        trainRemaining = (5 - count).coerceAtLeast(0)
        AppPrefs.setLastWake(
            this,
            "Lokalni audio profil: " + count + "/5"
        )

        if (trainRemaining <= 0) {
            mode = Mode.WAIT_WAKE
            segmentQueue.clear()
            AppPrefs.setStatus(
                this,
                "Trening završen. Reci ‘Halo telefon’."
            )
            updateServiceNotification(
                "Slušam: ‘Halo telefon’"
            )
            beepSuccess()
        } else {
            AppPrefs.setStatus(
                this,
                "Snimljeno " + count +
                    "/5. Reci ‘Halo telefon’ ponovo."
            )
            beepReady()
        }
    }

    private fun handleName(spokenRaw: String) {
        val spoken = spokenRaw.trim()
        AppPrefs.setStatus(this, "Tražim kontakt…")

        val contacts = getContacts()
        val ranked = ContactMatcher(learningStore)
            .rank(spoken, contacts, 30)

        val best = ranked.firstOrNull()

        if (best == null || best.score < 0.58) {
            selectedContact = null
            selectedSpoken = ""
            mode = Mode.WAIT_NAME
            AppPrefs.setStatus(
                this,
                "Nisam našao kontakt. Reci ime ponovo."
            )
            updateServiceNotification("Reci drugo ime")
            beepError()
            return
        }

        val floor = maxOf(0.58, best.score - 0.16)

        val plausible = ranked
            .filter { it.score >= floor }
            .distinctBy {
                SerbianNormalizer.normalize(
                    it.contact.displayName
                ) + "|" +
                    it.contact.number
                        .filter(Char::isDigit)
                        .takeLast(12)
            }

        val ordered = plausible
            .sortedWith(
                compareByDescending<ContactCandidate> {
                    learningStore.totalUses(
                        it.contact.lookupKey
                    )
                }.thenByDescending {
                    learningStore.lastUsed(
                        it.contact.lookupKey
                    )
                }.thenByDescending {
                    it.score
                }
            )
            .take(5)
            .ifEmpty {
                listOf(best)
            }

        val selected = ordered.maxWithOrNull(
            compareBy<ContactCandidate> {
                it.score
            }.thenBy {
                learningStore.totalUses(
                    it.contact.lookupKey
                )
            }
        ) ?: ordered.first()

        selectedContact = selected.contact
        selectedSpoken = spoken
        mode = Mode.WAIT_CONFIRM

        AppPrefs.setStatus(
            this,
            "Označen: " + selected.contact.displayName +
                ". Reci ‘može’, ‘ok’, ‘zovi’ ili izgovori drugo ime."
        )

        updateServiceNotification(
            "Čekam potvrdu ili drugo ime"
        )

        showCandidatePicker(
            spoken,
            ordered,
            selected.contact
        )

        beepReady()
    }

    private fun loadContactsSafely(): List<ContactPhone> {
        if (
            checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        return ContactRepository(contentResolver).load()
    }

    private fun getContacts(): List<ContactPhone> {
        val cached = contactCache

        if (!cached.isNullOrEmpty()) {
            return cached
        }

        return loadContactsSafely().also {
            contactCache = it
        }
    }

    private fun buildContactPrompt(): String =
        "Ime i prezime osobe iz telefonskog imenika u Srbiji."

    private fun padForWhisper(audio: FloatArray): FloatArray {
        val prefix = (SAMPLE_RATE * 0.35).toInt()
        val suffix = (SAMPLE_RATE * 0.55).toInt()
        val out = FloatArray(
            prefix + audio.size + suffix
        )

        audio.copyInto(
            out,
            destinationOffset = prefix
        )

        return out
    }

    private fun showCandidatePicker(
        spoken: String,
        candidates: List<ContactCandidate>,
        selected: ContactPhone
    ) {
        val ordered = candidates
            .distinctBy {
                SerbianNormalizer.normalize(
                    it.contact.displayName
                ) + "|" +
                    it.contact.number
                        .filter(Char::isDigit)
                        .takeLast(12)
            }
            .sortedWith(
                compareByDescending<ContactCandidate> {
                    learningStore.totalUses(
                        it.contact.lookupKey
                    )
                }.thenByDescending {
                    learningStore.lastUsed(
                        it.contact.lookupKey
                    )
                }.thenByDescending {
                    it.score
                }
            )
            .take(5)

        val names = ArrayList(
            ordered.map {
                it.contact.displayName
            }
        )

        val numbers = ArrayList(
            ordered.map {
                it.contact.number
            }
        )

        val keys = ArrayList(
            ordered.map {
                it.contact.lookupKey
            }
        )

        val uses = ArrayList(
            ordered.map {
                learningStore.totalUses(
                    it.contact.lookupKey
                )
            }
        )

        val scores = ordered
            .map { it.score }
            .toDoubleArray()

        val selectedIndex = ordered
            .indexOfFirst {
                it.contact.lookupKey ==
                    selected.lookupKey &&
                    it.contact.number ==
                    selected.number
            }
            .coerceAtLeast(0)

        AppPrefs.setLastMatch(
            this,
            ordered.joinToString(" • ") {
                it.contact.displayName + " " +
                    "%.0f".format(
                        it.score * 100
                    ) + "% · " +
                    learningStore.totalUses(
                        it.contact.lookupKey
                    ) + "×"
            }
        )

        val picker = Intent(
            this,
            CandidateActivity::class.java
        ).apply {
            putExtra(
                CandidateActivity.EXTRA_SPOKEN,
                spoken
            )
            putStringArrayListExtra(
                CandidateActivity.EXTRA_NAMES,
                names
            )
            putStringArrayListExtra(
                CandidateActivity.EXTRA_NUMBERS,
                numbers
            )
            putStringArrayListExtra(
                CandidateActivity.EXTRA_KEYS,
                keys
            )
            putIntegerArrayListExtra(
                CandidateActivity.EXTRA_USES,
                uses
            )
            putExtra(
                CandidateActivity.EXTRA_SCORES,
                scores
            )
            putExtra(
                CandidateActivity.EXTRA_SELECTED_INDEX,
                selectedIndex
            )
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        val pending = PendingIntent.getActivity(
            this,
            901,
            picker,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val builder = Notification.Builder(
            this,
            CANDIDATE_CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.sym_action_call
            )
            .setContentTitle(
                "Potvrdi kontakt"
            )
            .setContentText(
                names.take(3)
                    .joinToString(" • ")
            )
            .setContentIntent(pending)
            .setCategory(
                Notification.CATEGORY_CALL
            )
            .setPriority(
                Notification.PRIORITY_MAX
            )
            .setVisibility(
                Notification.VISIBILITY_PUBLIC
            )
            .setAutoCancel(true)

        val canFullScreen =
            Build.VERSION.SDK_INT < 34 ||
                manager.canUseFullScreenIntent()

        if (canFullScreen) {
            builder.setFullScreenIntent(
                pending,
                true
            )
        }

        manager.notify(
            CANDIDATE_NOTIFICATION_ID,
            builder.build()
        )

        val pm =
            getSystemService(
                PowerManager::class.java
            )

        if (pm.isInteractive) {
            runCatching {
                startActivity(picker)
            }
        }
    }

    private fun syncScreenWakeLock() {
        val pm =
            getSystemService(
                PowerManager::class.java
            )

        if (
            !running ||
            !AppPrefs.keepAwake(this) ||
            pm.isInteractive
        ) {
            releaseScreenWakeLock()
            return
        }

        if (
            screenWakeLock?.isHeld == true
        ) {
            return
        }

        screenWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "HaloTelefon:ScreenOffListening"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseScreenWakeLock() {
        screenWakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching {
                    lock.release()
                }
            }
        }

        screenWakeLock = null
    }

    private fun beepReady() {
        ignoreAudioUntilMs =
            SystemClock.elapsedRealtime() + 180

        tone?.startTone(
            ToneGenerator.TONE_PROP_BEEP,
            110
        )
    }

    private fun beepSuccess() {
        ignoreAudioUntilMs =
            SystemClock.elapsedRealtime() + 240

        tone?.startTone(
            ToneGenerator.TONE_PROP_ACK,
            170
        )
    }

    private fun beepError() {
        ignoreAudioUntilMs =
            SystemClock.elapsedRealtime() + 280

        tone?.startTone(
            ToneGenerator.TONE_PROP_NACK,
            200
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) return

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val listening = NotificationChannel(
            CHANNEL_ID,
            "Glasovno pozivanje",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description =
                "Lokalno slušanje wake fraze. Audio se ne šalje na internet."
        }

        manager.createNotificationChannel(
            listening
        )

        val candidates = NotificationChannel(
            CANDIDATE_CHANNEL_ID,
            "Izbor kontakta",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description =
                "Potvrda kontakta pre poziva."
            lockscreenVisibility =
                Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(
            candidates
        )
    }

    private fun serviceNotification(
        text: String
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(
                this,
                MainActivity::class.java
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this,
            2,
            Intent(
                this,
                VoiceDialService::class.java
            ).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentTitle(
                "Halo Telefon"
            )
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    0,
                    "Zaustavi",
                    stop
                ).build()
            )
            .build()
    }

    private fun updateServiceNotification(
        text: String
    ) {
        getSystemService(
            NotificationManager::class.java
        ).notify(
            NOTIFICATION_ID,
            serviceNotification(text)
        )
    }

    override fun onDestroy() {
        running = false

        AppPrefs.setRunning(
            this,
            false
        )

        AppPrefs.setStatus(
            this,
            "Isključeno"
        )

        try {
            if (::recorder.isInitialized) {
                recorder.stop()
            }
        } catch (_: Throwable) {
        }

        try {
            noiseSuppressor?.release()
        } catch (_: Throwable) {
        }

        try {
            automaticGainControl?.release()
        } catch (_: Throwable) {
        }

        try {
            if (::recorder.isInitialized) {
                recorder.release()
            }
        } catch (_: Throwable) {
        }

        try {
            if (::whisper.isInitialized) {
                whisper.close()
            }
        } catch (_: Throwable) {
        }

        try {
            learningStore.close()
        } catch (_: Throwable) {
        }

        tone?.release()
        releaseScreenWakeLock()

        runCatching {
            unregisterReceiver(screenReceiver)
        }

        audioExecutor.shutdownNow()
        inferenceExecutor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
