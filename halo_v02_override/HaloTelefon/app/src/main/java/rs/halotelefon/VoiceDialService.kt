package rs.halotelefon

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.whispercpp.whisper.WhisperContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoiceDialService : Service() {
    companion object {
        const val ACTION_STOP = "rs.halotelefon.STOP"
        const val ACTION_TRAIN_WAKE = "rs.halotelefon.TRAIN_WAKE"
        private const val CHANNEL_ID = "halo_voice"
        private const val NOTIFICATION_ID = 1001
        private const val CANDIDATE_NOTIFICATION_ID = 1002
    }

    private enum class Mode { WAIT_WAKE, WAIT_NAME, TRAIN_WAKE }

    @Volatile private var mode = Mode.WAIT_WAKE
    @Volatile private var running = false
    @Volatile private var trainRemaining = 0

    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor()
    private val segmentQueue = ArrayBlockingQueue<FloatArray>(3)
    private lateinit var recorder: AudioRecord
    private lateinit var whisper: WhisperContext
    private lateinit var acousticWakeStore: AcousticWakeStore
    private lateinit var learningStore: LearningStore
    private var wakeLock: PowerManager.WakeLock? = null
    private var tone: ToneGenerator? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    @Volatile private var ignoreAudioUntilMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acousticWakeStore = AcousticWakeStore(this)
        learningStore = LearningStore(this)
        tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val wasRunning = running
        if (!running) startVoiceEngine()

        if (intent?.action == ACTION_TRAIN_WAKE) {
            acousticWakeStore.clear()
            mode = Mode.TRAIN_WAKE
            trainRemaining = 5
            segmentQueue.clear()
            AppPrefs.setLastWake(this, "Novi lokalni audio profil: 0/5")
            AppPrefs.setStatus(this, if (wasRunning) "Trening: reci ‘Halo telefon’ 1/5" else "Pokrećem model za trening…")
            if (wasRunning) beepReady()
        }
        return START_NOT_STICKY
    }

    private fun startVoiceEngine() {
        startForeground(NOTIFICATION_ID, serviceNotification("Pokrećem lokalni model…"))
        running = true
        AppPrefs.setRunning(this, true)
        AppPrefs.setStatus(this, "Učitavam Base Whisper…")

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HaloTelefon:Voice").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L)
        }

        inferenceExecutor.execute {
            try {
                val model = ModelManager.ensureModel(this)
                whisper = WhisperContext(model.absolutePath)
                initRecorder()
                if (mode == Mode.TRAIN_WAKE) {
                    AppPrefs.setStatus(this, "Trening: reci ‘Halo telefon’ 1/5")
                    updateServiceNotification("Trening lokalnog wake profila")
                } else {
                    AppPrefs.setStatus(this, if (acousticWakeStore.isReady()) "Aktivno. Reci ‘Halo telefon’." else "Prvo nauči ‘Halo telefon’ 5x.")
                    updateServiceNotification(if (acousticWakeStore.isReady()) "Slušam: ‘Halo telefon’" else "Čeka lokalni wake trening")
                }
                startRecordingLoop()
                if (mode == Mode.TRAIN_WAKE) beepReady()
                startInferenceLoop()
            } catch (t: Throwable) {
                AppPrefs.setStatus(this, "Greška: ${t.message}")
                stopSelf()
            }
        }
    }

    private fun initRecorder() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            error("Nema dozvole za mikrofon")
        }
        val sampleRate = 16_000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate * 2)
        )
        require(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord nije inicijalizovan" }

        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }
        }
        runCatching {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(recorder.audioSessionId)?.apply { enabled = true }
            }
        }
    }

    private fun startRecordingLoop() {
        audioExecutor.execute {
            val frameShort = ShortArray(320)
            val segmenter = VoiceSegmenter()
            recorder.startRecording()
            while (running && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = recorder.read(frameShort, 0, frameShort.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                if (SystemClock.elapsedRealtime() < ignoreAudioUntilMs) {
                    segmenter.reset()
                    continue
                }
                val frame = FloatArray(read) { i -> frameShort[i] / 32768f }
                val maxSeconds = if (mode == Mode.WAIT_NAME) 4.5 else 3.0
                val segment = segmenter.accept(frame, maxSeconds)
                if (segment != null && segment.size >= 4_800) {
                    segmentQueue.offer(segment)
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
                    val text = try { whisper.transcribe(audio, "sr").trim() } catch (_: Throwable) { "" }
                    if (text.isBlank()) {
                        fail("Nisam razumeo ime. Reci ponovo ‘Halo telefon’.")
                        mode = Mode.WAIT_WAKE
                        updateServiceNotification("Slušam: ‘Halo telefon’")
                        continue
                    }
                    AppPrefs.setLastHeard(this, text)
                    handleName(text)
                }
            }
        }
    }

    private fun handleWake(audio: FloatArray) {
        if (!acousticWakeStore.isReady()) {
            AppPrefs.setStatus(this, "Wake profil nije naučen. Pritisni ‘Nauči moj izgovor’. ")
            AppPrefs.setLastWake(this, "Wake profil: ${acousticWakeStore.count()}/5")
            return
        }
        val result = runCatching { acousticWakeStore.match(audio) }.getOrNull() ?: return
        AppPrefs.setLastWake(
            this,
            "Wake ${result.confidence}% • d=${"%.2f".format(result.distance)} / ${"%.2f".format(result.threshold)}"
        )
        if (!result.matched) return

        mode = Mode.WAIT_NAME
        segmentQueue.clear()
        AppPrefs.setStatus(this, "Prepoznato ‘Halo telefon’. Slušam ime…")
        updateServiceNotification("Slušam ime kontakta")
        beepReady()
    }

    private fun handleWakeTraining(audio: FloatArray) {
        val before = acousticWakeStore.count()
        val count = try {
            acousticWakeStore.addSample(audio)
        } catch (t: Throwable) {
            AppPrefs.setStatus(this, t.message ?: "Ponovi ‘Halo telefon’.")
            beepError()
            return
        }
        if (count <= before) return

        trainRemaining = (5 - count).coerceAtLeast(0)
        AppPrefs.setLastWake(this, "Lokalni audio profil: $count/5")
        if (trainRemaining <= 0) {
            mode = Mode.WAIT_WAKE
            segmentQueue.clear()
            AppPrefs.setStatus(this, "Trening završen. Reci ‘Halo telefon’.")
            updateServiceNotification("Slušam: ‘Halo telefon’")
            beepSuccess()
        } else {
            AppPrefs.setStatus(this, "Snimljeno $count/5. Reci ‘Halo telefon’ ponovo.")
            beepReady()
        }
    }

    private fun handleName(text: String) {
        mode = Mode.WAIT_WAKE
        updateServiceNotification("Slušam: ‘Halo telefon’")
        AppPrefs.setStatus(this, "Tražim kontakt…")

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            fail("Nema dozvole za kontakte")
            return
        }

        val contacts = ContactRepository(contentResolver).load()
        val ranked = ContactMatcher(learningStore).rank(text, contacts, 3)
        val best = ranked.getOrNull(0)
        val second = ranked.getOrNull(1)

        if (best == null || best.score < 0.64) {
            fail("Nisam našao dovoljno sličan kontakt za: $text")
            return
        }

        val margin = best.score - (second?.score ?: 0.0)
        val learnedStrong = best.learnedUses >= 1 && best.score >= 0.88
        val strong = best.score >= 0.90 && margin >= 0.08

        if (learnedStrong || strong) {
            if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                fail("Nema dozvole za pozivanje")
                return
            }
            AppPrefs.setLastMatch(this, "${best.contact.displayName} (${"%.0f".format(best.score * 100)}%)")
            AppPrefs.setStatus(this, "Pozivam ${best.contact.displayName}")
            beepSuccess()
            CallPlacer.call(this, best.contact.number)
            return
        }

        AppPrefs.prefs(this).edit().putString(AppPrefs.KEY_PENDING_SPOKEN, text).apply()
        AppPrefs.setLastMatch(
            this,
            ranked.joinToString(" • ") { "${it.contact.displayName} ${"%.0f".format(it.score * 100)}%" }
        )
        AppPrefs.setStatus(this, "Nisam potpuno siguran. Izaberi kontakt iz obaveštenja.")
        postCandidateNotification(text, ranked.take(2))
        beepError()
    }

    private fun postCandidateNotification(spoken: String, candidates: List<ContactCandidate>) {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Koji kontakt si rekao?")
            .setContentText(candidates.joinToString(" ili ") { it.contact.displayName })
            .setAutoCancel(true)

        candidates.forEachIndexed { index, candidate ->
            val intent = Intent(this, CandidateReceiver::class.java).apply {
                putExtra("lookupKey", candidate.contact.lookupKey)
                putExtra("number", candidate.contact.number)
                putExtra("name", candidate.contact.displayName)
                putExtra("spoken", spoken)
            }
            val pi = PendingIntent.getBroadcast(
                this,
                700 + index,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(Notification.Action.Builder(0, candidate.contact.displayName, pi).build())
        }
        getSystemService(NotificationManager::class.java).notify(CANDIDATE_NOTIFICATION_ID, builder.build())
    }

    private fun fail(message: String) {
        AppPrefs.setStatus(this, message)
        beepError()
    }

    private fun beepReady() {
        ignoreAudioUntilMs = SystemClock.elapsedRealtime() + 320
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }
    private fun beepSuccess() {
        ignoreAudioUntilMs = SystemClock.elapsedRealtime() + 320
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
    }
    private fun beepError() {
        ignoreAudioUntilMs = SystemClock.elapsedRealtime() + 360
        tone?.startTone(ToneGenerator.TONE_PROP_NACK, 220)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Glasovno pozivanje",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mikrofon sluša lokalnu wake frazu. Audio se ne šalje na internet."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun serviceNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, VoiceDialService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Halo Telefon je aktivan")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(0, "Zaustavi", stop).build())
            .build()
    }

    private fun updateServiceNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, serviceNotification(text))
    }

    override fun onDestroy() {
        running = false
        AppPrefs.setRunning(this, false)
        AppPrefs.setStatus(this, "Isključeno")
        try { if (::recorder.isInitialized) recorder.stop() } catch (_: Throwable) {}
        try { noiseSuppressor?.release() } catch (_: Throwable) {}
        try { automaticGainControl?.release() } catch (_: Throwable) {}
        try { if (::recorder.isInitialized) recorder.release() } catch (_: Throwable) {}
        try { if (::whisper.isInitialized) whisper.close() } catch (_: Throwable) {}
        try { learningStore.close() } catch (_: Throwable) {}
        tone?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        audioExecutor.shutdownNow()
        inferenceExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
