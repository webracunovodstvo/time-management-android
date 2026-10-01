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
        const val ACTION_TRAIN_COMMANDS = "rs.halotelefon.TRAIN_COMMANDS"
        const val ACTION_TEST_NAME = "rs.halotelefon.TEST_NAME"
        const val ACTION_SELECT_CANDIDATE = "rs.halotelefon.SELECT_CANDIDATE"
        const val ACTION_CANCEL_INTERACTION = "rs.halotelefon.CANCEL_INTERACTION"

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
        WAIT_SELECT,
        WAIT_CONFIRM,
        TRAIN_WAKE,
        TRAIN_COMMANDS
    }

    @Volatile private var mode = Mode.WAIT_WAKE
    @Volatile private var running = false
    @Volatile private var nameInferencePending = false

    private var selectedContact: ContactPhone? = null
    private var selectedSpoken: String = ""
    private var pendingCandidates: List<ContactCandidate> = emptyList()
    private var trainRemaining = 0
    private var commandTrainingIndex = 0

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val contactExecutor = Executors.newSingleThreadExecutor()
    private val segmentQueue = ArrayBlockingQueue<FloatArray>(3)

    private lateinit var recorder: AudioRecord
    private lateinit var whisper: WhisperContext
    private lateinit var acousticWakeStore: AcousticWakeStore
    private lateinit var acousticCommandStore: AcousticCommandStore
    private lateinit var learningStore: LearningStore

    @Volatile private var contactCache: List<ContactPhone>? = null
    private var tone: ToneGenerator? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var interactionScreenWakeLock: PowerManager.WakeLock? = null

    @Volatile private var ignoreAudioUntilMs: Long = 0L
    @Volatile private var nameCaptureAllowedAtMs: Long = 0L
    @Volatile private var wakeAllowedAtMs: Long = 0L

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
        acousticCommandStore = AcousticCommandStore(this)
        learningStore = LearningStore(this)
        AppPrefs.setCommandProfile(
            this,
            acousticCommandStore.summary()
        )
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

            ACTION_CANCEL_INTERACTION -> {
                if (running) {
                    cancelCurrentInteraction("Otkazano.")
                } else {
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_TRAIN_WAKE -> {
                selectedContact = null
                selectedSpoken = ""
                pendingCandidates = emptyList()
                acousticWakeStore.clear()
                mode = Mode.TRAIN_WAKE
                nameInferencePending = false
                trainRemaining = 5
                segmentQueue.clear()
                AppPrefs.setLastWake(this, "Novi lokalni audio profil: 0/5")
                AppPrefs.setStatus(this, "Trening: reci ‘Halo telefon’ 1/5")
            }

            ACTION_TRAIN_COMMANDS -> {
                selectedContact = null
                selectedSpoken = ""
                pendingCandidates = emptyList()
                acousticCommandStore.clearAll()
                commandTrainingIndex = 0
                mode = Mode.TRAIN_COMMANDS
                nameInferencePending = false
                segmentQueue.clear()
                AppPrefs.setCommandProfile(
                    this,
                    acousticCommandStore.summary()
                )
                AppPrefs.setStatus(
                    this,
                    commandTrainingStatus()
                )
            }

            ACTION_TEST_NAME -> {
                selectedContact = null
                selectedSpoken = ""
                pendingCandidates = emptyList()
                mode = Mode.WAIT_NAME
                nameInferencePending = false
                segmentQueue.clear()
                nameCaptureAllowedAtMs =
                    SystemClock.elapsedRealtime() + 550L
                acquireInteractionScreenLock()
                AppPrefs.setStatus(this, "Test imena. Posle tona reci ime i prezime.")
                AppPrefs.setNameDebug(this, "Direktan test imena, wake je preskočen")
            }

            ACTION_SELECT_CANDIDATE -> {
                val key = intent.getStringExtra(EXTRA_LOOKUP_KEY).orEmpty()
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()

                if (key.isNotBlank() && name.isNotBlank() && number.isNotBlank()) {
                    selectedContact = ContactPhone(key, name, number)
                    acquireInteractionScreenLock()
                    mode = Mode.WAIT_CONFIRM
                    nameInferencePending = false
                    segmentQueue.clear()
                    AppPrefs.setStatus(
                        this,
                        "Izabrano: " + name +
                            ". Reci ZOVI / MOŽE / OK / OTKAŽI ili ponovo ‘HALO TELEFON’."
                    )
                    updateServiceNotification(
                        "ZOVI / MOŽE / OK / OTKAŽI • HALO TELEFON = novo ime"
                    )
                    if (running) beepReady()
                }
            }
        }

        if (!running) {
            startVoiceEngine()
        } else {
            when (intent?.action) {
                ACTION_TRAIN_WAKE,
                ACTION_TRAIN_COMMANDS,
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
                // Wake training must react immediately. Audio starts before
                // loading the large Whisper model or scanning contacts.
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

                    Mode.WAIT_SELECT -> {
                        AppPrefs.setStatus(
                            this,
                            selectionStatus()
                        )
                        updateServiceNotification(
                            "PRVI / DRUGI / TREĆI / ČETVRTI / PETI • OTKAŽI"
                        )
                    }

                    Mode.WAIT_CONFIRM -> {
                        AppPrefs.setStatus(
                            this,
                            confirmationStatus()
                        )
                        updateServiceNotification(
                            "Čekam potvrdu • HALO TELEFON = novo ime"
                        )
                    }

                    Mode.TRAIN_COMMANDS -> {
                        AppPrefs.setStatus(
                            this,
                            commandTrainingStatus()
                        )
                        updateServiceNotification(
                            "Trening glasovnih komandi"
                        )
                    }

                    Mode.WAIT_WAKE -> {
                        val ready = acousticWakeStore.isReady()
                        AppPrefs.setStatus(
                            this,
                            if (ready) "ČEKAM: ‘HALO TELEFON’"
                            else "Prvo nauči ‘Halo telefon’ 5x."
                        )
                        updateServiceNotification(
                            if (ready) "ČEKAM: ‘HALO TELEFON’"
                            else "Čeka trening wake fraze"
                        )
                    }
                }

                startRecordingLoop()

                if (
                    mode == Mode.TRAIN_WAKE ||
                    mode == Mode.TRAIN_COMMANDS ||
                    mode == Mode.WAIT_NAME ||
                    mode == Mode.WAIT_SELECT ||
                    mode == Mode.WAIT_CONFIRM
                ) {
                    beepReady()
                }

                // Heavy resources load only after the microphone is already live.
                val model = ModelManager.ensureModel(this)
                whisper = WhisperContext(model.absolutePath)

                // Warm the grouped phonebook in parallel. Wake recognition
                // remains independent, but the first real name lookup is faster.
                contactExecutor.execute {
                    runCatching {
                        val loaded = loadContactsSafely()
                        if (loaded.isNotEmpty()) {
                            contactCache = loaded
                        }
                    }
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
            val trainingSegmenter = WakeTrainingSegmenter()
            val nameSegmenter = NameSegmenter()
            val confirmationSegmenter = ConfirmationSegmenter()
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
                    trainingSegmenter.reset()
                    nameSegmenter.reset()
                    confirmationSegmenter.reset()
                    observedMode = currentMode
                }

                if (SystemClock.elapsedRealtime() < ignoreAudioUntilMs) {
                    wakeSegmenter.reset()
                    trainingSegmenter.reset()
                    nameSegmenter.reset()
                    confirmationSegmenter.reset()
                    continue
                }

                val frame = FloatArray(read) { index ->
                    frameShort[index] / 32768f
                }

                when (currentMode) {
                    Mode.WAIT_NAME -> {
                        if (nameInferencePending) continue

                        if (
                            SystemClock.elapsedRealtime() <
                            nameCaptureAllowedAtMs
                        ) {
                            nameSegmenter.reset()
                            continue
                        }

                        val wasSpeaking = nameSegmenter.speechStarted
                        val segment = nameSegmenter.accept(frame)

                        if (!wasSpeaking && nameSegmenter.speechStarted) {
                            AppPrefs.setStatus(this, "SLUŠAM IME…")
                            updateServiceNotification("Čujem ime kontakta…")
                        }

                        if (nameSegmenter.timedOut) {
                            nameSegmenter.reset()
                            AppPrefs.setStatus(
                                this,
                                "RECI IME • ili reci OTKAŽI"
                            )
                            AppPrefs.setNameDebug(
                                this,
                                "Čekam ime; interakcija ostaje aktivna"
                            )
                            updateServiceNotification(
                                "RECI IME • OTKAŽI"
                            )
                            continue
                        }

                        if (segment != null && segment.size >= 8_000) {
                            if (!isLikelyNameSpeech(segment)) {
                                AppPrefs.setStatus(this, "RECI IME")
                                AppPrefs.setNameDebug(
                                    this,
                                    "Odbačen zvuk koji ne liči dovoljno na govor"
                                )
                                updateServiceNotification("RECI IME")
                                nameSegmenter.reset()
                                continue
                            }

                            nameInferencePending = true
                            AppPrefs.setStatus(this, "PREPOZNAJEM IME…")
                            AppPrefs.setNameDebug(
                                this,
                                "Snimljeno ime, pokrećem lokalni Whisper…"
                            )
                            updateServiceNotification("Prepoznajem ime…")

                            if (!segmentQueue.offer(segment)) {
                                nameInferencePending = false
                                mode = Mode.WAIT_WAKE
                                releaseInteractionScreenLock()
                                AppPrefs.setStatus(
                                    this,
                                    "Audio red je zauzet. Pokušaj ponovo."
                                )
                                updateServiceNotification("Slušam: ‘Halo telefon’")
                                beepError()
                            }
                        }
                    }

                    Mode.WAIT_SELECT,
                    Mode.WAIT_CONFIRM -> {
                        if (nameInferencePending) continue

                        val wasSpeaking =
                            confirmationSegmenter.speechStarted
                        val segment =
                            confirmationSegmenter.accept(frame)

                        if (
                            !wasSpeaking &&
                            confirmationSegmenter.speechStarted
                        ) {
                            AppPrefs.setStatus(
                                this,
                                "Čujem komandu…"
                            )
                            updateServiceNotification(
                                if (currentMode == Mode.WAIT_SELECT) {
                                    "Slušam PRVI / DRUGI / TREĆI / ČETVRTI / PETI / OTKAŽI"
                                } else {
                                    "Slušam ZOVI / MOŽE / OK / OTKAŽI / HALO TELEFON"
                                }
                            )
                        }

                        if (confirmationSegmenter.timedOut) {
                            confirmationSegmenter.reset()
                            AppPrefs.setStatus(
                                this,
                                if (currentMode == Mode.WAIT_SELECT) {
                                    selectionStatus()
                                } else {
                                    confirmationStatus()
                                }
                            )
                            updateServiceNotification(
                                if (currentMode == Mode.WAIT_SELECT) {
                                    "Izaberi broj kontakta glasom"
                                } else {
                                    "Čekam potvrdu • HALO TELEFON = novo ime"
                                }
                            )
                            continue
                        }

                        if (
                            segment != null &&
                            segment.size >= 3_200
                        ) {
                            nameInferencePending = true
                            AppPrefs.setStatus(
                                this,
                                "Proveravam komandu…"
                            )
                            AppPrefs.setNameDebug(
                                this,
                                "Komanda: " +
                                    "%.2f".format(
                                        segment.size /
                                            SAMPLE_RATE.toDouble()
                                    ) +
                                    " s"
                            )
                            updateServiceNotification(
                                "Proveravam komandu…"
                            )

                            if (!segmentQueue.offer(segment)) {
                                nameInferencePending = false
                                AppPrefs.setStatus(
                                    this,
                                    if (currentMode == Mode.WAIT_SELECT) {
                                        selectionStatus()
                                    } else {
                                        confirmationStatus()
                                    }
                                )
                                beepError()
                            }
                        }
                    }

                    Mode.TRAIN_WAKE -> {
                        val wasSpeaking =
                            trainingSegmenter.speechStarted

                        val segment =
                            trainingSegmenter.accept(
                                frame,
                                3.0
                            )

                        if (
                            !wasSpeaking &&
                            trainingSegmenter.speechStarted
                        ) {
                            AppPrefs.setStatus(
                                this,
                                "ČUJEM ‘HALO TELEFON’…"
                            )
                            updateServiceNotification(
                                "Snimam wake frazu…"
                            )
                        }

                        if (
                            segment != null &&
                            segment.size >= 4_800
                        ) {
                            // Training does not need Whisper. Process immediately
                            // so contact/model loading cannot block the 5 samples.
                            handleWakeTraining(segment)
                        }
                    }

                    Mode.TRAIN_COMMANDS -> {
                        val wasSpeaking =
                            confirmationSegmenter.speechStarted

                        val segment =
                            confirmationSegmenter.accept(frame)

                        if (
                            !wasSpeaking &&
                            confirmationSegmenter.speechStarted
                        ) {
                            AppPrefs.setStatus(
                                this,
                                "SNIMAM: " +
                                    currentTrainingCommand()
                                        .spokenLabel
                            )
                            updateServiceNotification(
                                "Snimam glasovnu komandu"
                            )
                        }

                        if (
                            segment != null &&
                            segment.size >= 3_200
                        ) {
                            handleCommandTraining(
                                segment
                            )
                        }
                    }

                    Mode.WAIT_WAKE -> {
                        if (
                            SystemClock.elapsedRealtime() <
                            wakeAllowedAtMs
                        ) {
                            wakeSegmenter.reset()
                            continue
                        }

                        val segment =
                            wakeSegmenter.accept(
                                frame,
                                3.0
                            )

                        if (
                            segment != null &&
                            segment.size >= 4_800
                        ) {
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

                Mode.TRAIN_WAKE,
                Mode.TRAIN_COMMANDS -> {
                    // Training samples are handled immediately on the audio
                    // thread. Ignore any stale queued segment.
                    continue
                }

                Mode.WAIT_NAME -> {
                    val learnedCancel =
                        runCatching {
                            acousticCommandStore.match(
                                audio,
                                setOf(
                                    LearnedVoiceCommand.CANCEL
                                )
                            )
                        }.getOrNull()

                    if (
                        learnedCancel?.matched == true &&
                        learnedCancel.command ==
                        LearnedVoiceCommand.CANCEL
                    ) {
                        nameInferencePending = false
                        AppPrefs.setLastHeard(
                            this,
                            "Naučeno: OTKAŽI"
                        )
                        cancelCurrentInteraction(
                            "Otkazano glasom."
                        )
                        continue
                    }

                    val forms = recognizeNameForms(audio)
                    nameInferencePending = false

                    if (forms.isEmpty()) {
                        AppPrefs.setLastHeard(
                            this,
                            "(Whisper nije vratio tekst)"
                        )
                        mode = Mode.WAIT_WAKE
                        releaseInteractionScreenLock()
                        AppPrefs.setStatus(
                            this,
                            "Nisam razumeo ime. ČEKAM: ‘HALO TELEFON’"
                        )
                        updateServiceNotification("Slušam: ‘Halo telefon’")
                        beepError()
                        continue
                    }

                    val cancelFromName =
                        forms.firstOrNull {
                            isCancelCommand(it)
                        }

                    if (cancelFromName != null) {
                        AppPrefs.setLastHeard(
                            this,
                            cancelFromName
                        )
                        cancelCurrentInteraction(
                            "Otkazano glasom."
                        )
                        continue
                    }

                    AppPrefs.setLastHeard(
                        this,
                        forms.joinToString(" / ")
                    )
                    handleNameForms(forms)
                }

                Mode.WAIT_SELECT,
                Mode.WAIT_CONFIRM -> {
                    handleInteractionCommand(audio)
                }
            }
        }
    }

    private fun handleInteractionCommand(
        audio: FloatArray
    ) {
        nameInferencePending = false

        val allowed =
            buildSet {
                add(
                    LearnedVoiceCommand.CANCEL
                )

                if (pendingCandidates.isNotEmpty()) {
                    LearnedVoiceCommand.values()
                        .filter {
                            it.candidateIndex != null &&
                                it.candidateIndex <
                                pendingCandidates.size
                        }
                        .forEach(::add)
                }
            }

        val learned =
            runCatching {
                acousticCommandStore.match(
                    audio,
                    allowed
                )
            }.getOrNull()

        if (learned?.matched == true) {
            when (val command = learned.command) {
                LearnedVoiceCommand.CANCEL -> {
                    AppPrefs.setLastHeard(
                        this,
                        "Naučeno: OTKAŽI"
                    )
                    cancelCurrentInteraction(
                        "Otkazano glasom."
                    )
                    return
                }

                null -> Unit

                else -> {
                    val index =
                        command.candidateIndex

                    if (
                        index != null &&
                        index <
                        pendingCandidates.size
                    ) {
                        AppPrefs.setLastHeard(
                            this,
                            "Naučeno: " +
                                command.spokenLabel
                        )
                        selectCandidateByVoice(
                            index
                        )
                        return
                    }
                }
            }
        }

        val commandText =
            transcribeShort(
                audio,
                "Komanda: prvi, drugi, treći, četvrti, peti, zovi, može, ok, okej, pozovi, otkaži, odustani, prekini, halo telefon.",
                "komanda"
            )

        if (isCancelCommand(commandText)) {
            AppPrefs.setLastHeard(
                this,
                commandText
            )
            cancelCurrentInteraction(
                "Otkazano glasom."
            )
            return
        }

        val ordinal =
            ordinalIndex(commandText)

        if (
            ordinal != null &&
            ordinal <
            pendingCandidates.size
        ) {
            AppPrefs.setLastHeard(
                this,
                commandText
            )
            selectCandidateByVoice(
                ordinal
            )
            return
        }

        if (isConfirmation(commandText)) {
            AppPrefs.setLastHeard(
                this,
                commandText
            )
            confirmSelectedCall()
            return
        }

        if (isWakeCommand(commandText)) {
            val wake =
                runCatching {
                    acousticWakeStore.match(
                        audio
                    )
                }.getOrNull()

            if (wake?.matched == true) {
                AppPrefs.setLastHeard(
                    this,
                    commandText
                )
                beginFreshNameAfterWake()
                return
            }
        }

        AppPrefs.setNameDebug(
            this,
            "Ignorisano u komandama: " +
                if (commandText.isBlank()) {
                    "(bez teksta)"
                } else {
                    commandText
                }
        )

        AppPrefs.setStatus(
            this,
            if (mode == Mode.WAIT_SELECT) {
                selectionStatus()
            } else {
                confirmationStatus()
            }
        )

        updateServiceNotification(
            if (mode == Mode.WAIT_SELECT) {
                "Izaberi broj kontakta glasom"
            } else {
                "Čekam potvrdu • HALO TELEFON = novo ime"
            }
        )
    }

    private fun selectCandidateByVoice(
        index: Int
    ) {
        val candidate =
            pendingCandidates.getOrNull(index)

        if (candidate == null) {
            AppPrefs.setStatus(
                this,
                selectionStatus()
            )
            beepError()
            return
        }

        selectedContact =
            candidate.contact
        mode = Mode.WAIT_CONFIRM
        segmentQueue.clear()

        AppPrefs.setLastMatch(
            this,
            "Glasom izabrano: " +
                candidate.contact.displayName
        )
        AppPrefs.setStatus(
            this,
            "Izabrano: " +
                candidate.contact.displayName +
                ". Reci ZOVI / MOŽE / OK / OTKAŽI."
        )
        updateServiceNotification(
            "ZOVI / MOŽE / OK / OTKAŽI"
        )

        sendBroadcast(
            Intent(
                CandidateActivity.ACTION_VOICE_SELECTION
            )
                .setPackage(packageName)
                .putExtra(
                    CandidateActivity.EXTRA_VOICE_INDEX,
                    index
                )
        )

        beepReady()
    }

    private fun ordinalIndex(
        raw: String
    ): Int? {
        val normalized =
            normalizeCommand(raw)

        val words =
            normalized
                .split(' ')
                .filter { it.isNotBlank() }

        for (word in words) {
            when {
                word in setOf(
                    "prvi",
                    "prva",
                    "prvo",
                    "jedan"
                ) ||
                    editDistanceAtMostOne(
                        word,
                        "prvi"
                    ) ->
                    return 0

                word in setOf(
                    "drugi",
                    "druga",
                    "drugo",
                    "dva"
                ) ||
                    editDistanceAtMostOne(
                        word,
                        "drugi"
                    ) ->
                    return 1

                word in setOf(
                    "treci",
                    "treca",
                    "trece",
                    "tri"
                ) ||
                    editDistanceAtMostOne(
                        word,
                        "treci"
                    ) ->
                    return 2

                word in setOf(
                    "cetvrti",
                    "cetvrta",
                    "cetvrto",
                    "cetiri"
                ) ||
                    editDistanceAtMostOne(
                        word,
                        "cetvrti"
                    ) ->
                    return 3

                word in setOf(
                    "peti",
                    "peta",
                    "peto",
                    "pet"
                ) ||
                    editDistanceAtMostOne(
                        word,
                        "peti"
                    ) ->
                    return 4
            }
        }

        return null
    }

    private fun selectionStatus(): String {
        val labels =
            LearnedVoiceCommand.values()
                .filter {
                    it.candidateIndex != null &&
                        it.candidateIndex <
                        pendingCandidates.size
                }
                .joinToString(" / ") {
                    it.spokenLabel
                }

        return if (labels.isBlank()) {
            confirmationStatus()
        } else {
            "IZABERI: " +
                labels +
                " • OTKAŽI"
        }
    }

    private fun confirmationStatus(): String =
        "Čekam ZOVI / MOŽE / OK / OTKAŽI. " +
            "Za novi kontakt reci ‘HALO TELEFON’."

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

    private fun isCancelCommand(raw: String): Boolean {
        val normalized = normalizeCommand(raw)
        if (normalized.isBlank()) return false

        return normalized
            .split(' ')
            .filter { it.isNotBlank() }
            .any { word ->
                word in setOf(
                    "otkazi",
                    "odkazi",
                    "otkaz",
                    "otkaze",
                    "otkazi",
                    "odustani",
                    "ponisti",
                    "prekini",
                    "stop"
                ) ||
                    editDistanceAtMostOne(word, "otkazi") ||
                    SerbianPhonetics.similarity(word, "otkazi") >= 0.72
            }
    }

    private fun isWakeCommand(raw: String): Boolean {
        val normalized = normalizeCommand(raw)
        if (normalized.isBlank()) return false

        val words = normalized
            .split(' ')
            .filter { it.isNotBlank() }

        val hasHalo = words.any {
            it == "halo" ||
                editDistanceAtMostOne(it, "halo")
        }

        val hasTelefon = words.any {
            it == "telefon" ||
                editDistanceAtMostOne(it, "telefon")
        }

        return hasHalo && hasTelefon
    }

    private fun isConfirmation(raw: String): Boolean {
        val normalized = normalizeCommand(raw)
        if (normalized.isBlank()) return false

        val words = normalized
            .split(' ')
            .filter { it.isNotBlank() }

        return words.any { word ->
            when {
                word == "ok" || word == "okej" || word == "okay" ||
                    word == "oke" || word == "okey" -> true
                word == "zovi" || word == "pozovi" ||
                    word == "zov" || word == "zove" ||
                    word.endsWith("zovi") -> true
                word == "moze" || word == "moz" -> true
                editDistanceAtMostOne(word, "zovi") -> true
                editDistanceAtMostOne(word, "moze") -> true
                editDistanceAtMostOne(word, "okej") -> true
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
            .replace("откажи", "otkazi")
            .replace("одустани", "odustani")
            .replace("поништи", "ponisti")
            .replace("прекини", "prekini")
            .replace("хало", "halo")
            .replace("телефон", "telefon")
            .replace("први", "prvi")
            .replace("прва", "prva")
            .replace("други", "drugi")
            .replace("друга", "druga")
            .replace("трећи", "treci")
            .replace("треци", "treci")
            .replace("четврти", "cetvrti")
            .replace("четврта", "cetvrta")
            .replace("пети", "peti")
            .replace("пета", "peta")
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

    private fun cancelCurrentInteraction(reason: String) {
        selectedContact = null
        selectedSpoken = ""
        pendingCandidates = emptyList()
        nameInferencePending = false
        segmentQueue.clear()
        mode = Mode.WAIT_WAKE
        releaseInteractionScreenLock()
        wakeAllowedAtMs =
            SystemClock.elapsedRealtime() + 1_200L
        ignoreAudioUntilMs =
            SystemClock.elapsedRealtime() + 900L

        sendBroadcast(
            Intent(CandidateActivity.ACTION_CLOSE_PICKER)
                .setPackage(packageName)
        )

        getSystemService(NotificationManager::class.java)
            .cancel(CANDIDATE_NOTIFICATION_ID)

        AppPrefs.setStatus(
            this,
            reason + " ČEKAM: ‘HALO TELEFON’"
        )
        updateServiceNotification("ČEKAM: ‘HALO TELEFON’")
        beepReady()

        // beepReady uses a short mic ignore window; extend it here so the
        // cancel acknowledgement itself cannot re-enter wake detection.
        ignoreAudioUntilMs =
            SystemClock.elapsedRealtime() + 900L
    }

    private fun beginFreshNameAfterWake() {
        selectedContact = null
        selectedSpoken = ""
        pendingCandidates = emptyList()
        nameInferencePending = false
        segmentQueue.clear()
        mode = Mode.WAIT_NAME
        acquireInteractionScreenLock()
        nameCaptureAllowedAtMs =
            SystemClock.elapsedRealtime() + 550L

        sendBroadcast(
            Intent(CandidateActivity.ACTION_CLOSE_PICKER)
                .setPackage(packageName)
        )
        getSystemService(NotificationManager::class.java)
            .cancel(CANDIDATE_NOTIFICATION_ID)

        AppPrefs.setStatus(this, "RECI IME")
        AppPrefs.setNameDebug(
            this,
            "Nova wake komanda u režimu potvrde"
        )
        updateServiceNotification("RECI IME")
        beepReady()
    }

    private fun isLikelyNameSpeech(
        audio: FloatArray
    ): Boolean {
        if (audio.size < 8_000) return false

        val frame = 320
        val rmsValues = ArrayList<Double>()
        val zcrValues = ArrayList<Double>()
        var offset = 0

        while (offset + frame <= audio.size) {
            var energy = 0.0
            var crossings = 0
            var previous = audio[offset]

            for (i in offset until offset + frame) {
                val sample = audio[i]
                energy += sample * sample

                if (
                    (sample >= 0f && previous < 0f) ||
                    (sample < 0f && previous >= 0f)
                ) {
                    crossings++
                }
                previous = sample
            }

            rmsValues += kotlin.math.sqrt(
                energy / frame.toDouble()
            )
            zcrValues +=
                crossings.toDouble() / frame.toDouble()
            offset += frame
        }

        if (rmsValues.isEmpty()) return false

        val peak =
            rmsValues.maxOrNull() ?: return false
        if (peak < 0.0075) return false

        val threshold =
            maxOf(0.0045, peak * 0.22)

        val active =
            rmsValues.indices.filter {
                rmsValues[it] >= threshold
            }

        if (active.size < 6) return false

        val spanFrames =
            active.last() - active.first() + 1
        if (spanFrames < 9) return false

        val averageZcr =
            active.map { zcrValues[it] }
                .average()

        // Impulsive / hiss-like noises tend to have very high ZCR.
        if (averageZcr > 0.34) return false

        return true
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
        pendingCandidates = emptyList()
        mode = Mode.WAIT_WAKE
        releaseInteractionScreenLock()
        wakeAllowedAtMs =
            SystemClock.elapsedRealtime() + 1_200L
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
        pendingCandidates = emptyList()
        acquireInteractionScreenLock()
        mode = Mode.WAIT_NAME
        nameInferencePending = false
        segmentQueue.clear()
        nameCaptureAllowedAtMs =
            SystemClock.elapsedRealtime() + 550L

        AppPrefs.setStatus(
            this,
            "RECI IME"
        )
        AppPrefs.setNameDebug(
            this,
            "Čekam ime nakon wake fraze"
        )
        updateServiceNotification(
            "RECI IME"
        )
        beepReady()
    }

    private fun currentTrainingCommand():
        LearnedVoiceCommand =
        LearnedVoiceCommand.values()[
            commandTrainingIndex.coerceIn(
                0,
                LearnedVoiceCommand.values().lastIndex
            )
        ]

    private fun commandTrainingStatus(): String {
        val command =
            currentTrainingCommand()
        val count =
            acousticCommandStore.count(
                command
            )

        return "TRENING KOMANDI • RECI „" +
            command.spokenLabel +
            "“ " +
            (count + 1).coerceAtMost(3) +
            "/3"
    }

    private fun handleCommandTraining(
        audio: FloatArray
    ) {
        val command =
            currentTrainingCommand()

        val before =
            acousticCommandStore.count(
                command
            )

        val count =
            try {
                acousticCommandStore.addSample(
                    command,
                    audio
                )
            } catch (t: Throwable) {
                AppPrefs.setStatus(
                    this,
                    t.message
                        ?: "Ponovi komandu."
                )
                beepError()
                return
            }

        if (count <= before) {
            return
        }

        AppPrefs.setCommandProfile(
            this,
            acousticCommandStore.summary()
        )

        if (count >= 3) {
            if (
                commandTrainingIndex <
                LearnedVoiceCommand.values().lastIndex
            ) {
                commandTrainingIndex++
                AppPrefs.setStatus(
                    this,
                    commandTrainingStatus()
                )
                updateServiceNotification(
                    "Sledeća komanda: " +
                        currentTrainingCommand()
                            .spokenLabel
                )
                beepReady()
            } else {
                mode = Mode.WAIT_WAKE
                segmentQueue.clear()
                AppPrefs.setStatus(
                    this,
                    "Glasovne komande su naučene. " +
                        "ČEKAM: ‘HALO TELEFON’"
                )
                updateServiceNotification(
                    "ČEKAM: ‘HALO TELEFON’"
                )
                beepSuccess()
            }
        } else {
            AppPrefs.setStatus(
                this,
                "SNIMLJENO " +
                    command.spokenLabel +
                    " " +
                    count +
                    "/3 • RECI PONOVO"
            )
            updateServiceNotification(
                command.spokenLabel +
                    " " +
                    count +
                    "/3"
            )
            beepReady()
        }
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
                "SNIMLJENO " + count +
                    "/5 • RECI ‘HALO TELEFON’ PONOVO"
            )
            updateServiceNotification(
                "Wake trening " + count +
                    "/5 • reci ponovo ‘Halo telefon’"
            )
            beepReady()
        }
    }

    private fun handleNameForms(spokenForms: List<String>) {
        val forms = spokenForms
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (forms.isEmpty()) {
            pendingCandidates = emptyList()
            mode = Mode.WAIT_NAME
            AppPrefs.setStatus(this, "Nisam razumeo ime. Reci ponovo.")
            beepError()
            return
        }

        AppPrefs.setStatus(this, "TRAŽIM KONTAKT…")

        val contacts = getContacts()
        val ranked = ContactMatcher(learningStore)
            .rankBestOf(forms, contacts, 30)

        val best = ranked.firstOrNull()

        if (best == null || best.score < 0.58) {
            selectedContact = null
            selectedSpoken = ""
            pendingCandidates = emptyList()
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

        val usageStats =
            learningStore.statsFor(
                plausible.map {
                    it.contact.lookupKey
                }
            )

        val ordered = plausible
            .sortedWith(
                compareByDescending<ContactCandidate> {
                    usageStats[
                        it.contact.lookupKey
                    ]?.totalUses ?: 0
                }.thenByDescending {
                    usageStats[
                        it.contact.lookupKey
                    ]?.lastUsed ?: 0L
                }.thenByDescending {
                    it.score
                }
            )
            .take(5)
            .ifEmpty {
                listOf(best)
            }

        val selected =
            ordered.maxWithOrNull(
                compareBy<ContactCandidate> {
                    it.score
                }.thenBy {
                    usageStats[
                        it.contact.lookupKey
                    ]?.totalUses ?: 0
                }
            ) ?: ordered.first()

        selectedContact = selected.contact
        pendingCandidates = ordered

        // Keep the first/raw transcript as the learned alias. If Whisper
        // consistently hears a short Serbian name the same wrong way,
        // confirmation teaches that acoustic spelling to the chosen contact.
        selectedSpoken = forms.first()

        mode =
            if (ordered.size > 1) {
                Mode.WAIT_SELECT
            } else {
                Mode.WAIT_CONFIRM
            }

        AppPrefs.setStatus(
            this,
            if (mode == Mode.WAIT_SELECT) {
                selectionStatus()
            } else {
                "OZNAČEN: " +
                    selected.contact.displayName +
                    " • ZOVI / MOŽE / OK / OTKAŽI"
            }
        )

        updateServiceNotification(
            if (mode == Mode.WAIT_SELECT) {
                "PRVI / DRUGI / TREĆI / ČETVRTI / PETI • OTKAŽI"
            } else {
                "ZOVI / MOŽE / OK / OTKAŽI • HALO TELEFON = NOVO IME"
            }
        )

        showCandidatePicker(
            forms.joinToString(" / "),
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

    private fun buildShortNamePrompt(
        contacts: List<ContactPhone>
    ): String {
        // Do not query SQLite from a sort comparator. This prompt is only
        // a compact local vocabulary hint; contact frequency is applied later
        // by ContactMatcher/LearningStore.
        val orderedContacts =
            contacts.sortedBy {
                it.displayName.length
            }

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

    private fun recognizeNameForms(
        audio: FloatArray
    ): List<String> {
        val forms = ArrayList<String>(2)

        val primary = transcribeShort(
            audio,
            buildContactPrompt(),
            "ime"
        ).trim()

        if (primary.isNotBlank()) {
            forms.add(primary)

            // Show the raw result immediately. Contact loading and the
            // contact-aware fallback must not leave diagnostics at "Još ništa".
            AppPrefs.setLastHeard(
                this,
                primary
            )
        }

        AppPrefs.setNameDebug(
            this,
            "Primarno: " +
                if (primary.isBlank()) {
                    "(prazno)"
                } else {
                    primary
                } +
                " • proveravam imenik"
        )

        val contacts = getContacts()

        val primaryScore = if (primary.isBlank()) {
            0.0
        } else {
            ContactMatcher(learningStore)
                .rank(primary, contacts, 1)
                .firstOrNull()
                ?.score ?: 0.0
        }

        if (
            primary.isBlank() ||
            primaryScore < 0.60
        ) {
            val contactAware = transcribeShort(
                audio,
                buildShortNamePrompt(contacts),
                "ime + imenik"
            ).trim()

            if (
                contactAware.isNotBlank() &&
                contactAware !in forms
            ) {
                forms.add(contactAware)
            }
        }

        AppPrefs.setNameDebug(
            this,
            "Ime kandidati: " +
                if (forms.isEmpty()) {
                    "(prazno)"
                } else {
                    forms.joinToString(" / ")
                }
        )

        return forms
    }

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

        val numberDetails = ArrayList(
            ordered.map {
                it.contact.numberSummary(
                    compact = false
                )
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
                CandidateActivity.EXTRA_NUMBER_DETAILS,
                numberDetails
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

    @Suppress("DEPRECATION")
    private fun acquireInteractionScreenLock() {
        if (interactionScreenWakeLock?.isHeld == true) {
            return
        }

        val pm =
            getSystemService(
                PowerManager::class.java
            )

        interactionScreenWakeLock =
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "HaloTelefon:ActiveInteraction"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseInteractionScreenLock() {
        interactionScreenWakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching {
                    lock.release()
                }
            }
        }

        interactionScreenWakeLock = null
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
        releaseInteractionScreenLock()
        releaseScreenWakeLock()

        runCatching {
            unregisterReceiver(screenReceiver)
        }

        audioExecutor.shutdownNow()
        inferenceExecutor.shutdownNow()
        contactExecutor.shutdownNow()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}
