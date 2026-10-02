package rs.halotelefon

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

class CandidateActivity : Activity() {

    companion object {
        const val EXTRA_SPOKEN = "spoken"
        const val EXTRA_NAMES = "names"
        const val EXTRA_NUMBERS = "numbers"
        const val EXTRA_NUMBER_DETAILS = "numberDetails"
        const val EXTRA_KEYS = "keys"
        const val EXTRA_SESSION_ID = "sessionId"

        const val ACTION_CLOSE_PICKER =
            "rs.halotelefon.CLOSE_PICKER"

        // Kept for source compatibility with older service code. v0.23 does
        // not use voice selection anymore.
        const val ACTION_VOICE_SELECTION =
            "rs.halotelefon.VOICE_SELECTION"

        const val EXTRA_VOICE_INDEX =
            "voiceSelectionIndex"
    }

    private val bg = Color.rgb(245, 247, 251)
    private val surface = Color.WHITE
    private val ink = Color.rgb(17, 24, 39)
    private val muted = Color.rgb(107, 114, 128)
    private val line = Color.rgb(229, 231, 235)
    private val accent = Color.rgb(79, 70, 229)
    private val accentSoft = Color.rgb(238, 242, 255)
    private val green = Color.rgb(5, 150, 105)

    private var spoken: String = ""
    private var names = arrayListOf<String>()
    private var numbers = arrayListOf<String>()
    private var numberDetails = arrayListOf<String>()
    private var keys = arrayListOf<String>()
    private var sessionId: String = ""
    @Volatile private var callStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        window.statusBarColor = bg
        window.navigationBarColor = bg

        loadIntent()
        cancelCandidateNotification()
        render()
    }

    override fun onNewIntent(
        intent: android.content.Intent
    ) {
        super.onNewIntent(intent)

        val previousSession = sessionId
        setIntent(intent)
        loadIntent()

        if (
            sessionId.isNotBlank() &&
            sessionId != previousSession
        ) {
            callStarted = false
        }

        cancelCandidateNotification()
        render()
    }

    private fun loadIntent() {
        spoken =
            intent.getStringExtra(
                EXTRA_SPOKEN
            ).orEmpty()

        names =
            intent.getStringArrayListExtra(
                EXTRA_NAMES
            ) ?: arrayListOf()

        numbers =
            intent.getStringArrayListExtra(
                EXTRA_NUMBERS
            ) ?: arrayListOf()

        numberDetails =
            intent.getStringArrayListExtra(
                EXTRA_NUMBER_DETAILS
            ) ?: arrayListOf()

        keys =
            intent.getStringArrayListExtra(
                EXTRA_KEYS
            ) ?: arrayListOf()

        sessionId =
            intent.getStringExtra(
                EXTRA_SESSION_ID
            ).orEmpty()
    }

    private fun render() {
        if (names.isEmpty()) {
            finish()
            return
        }

        val scroll =
            ScrollView(this).apply {
                setBackgroundColor(bg)
                isFillViewport = true
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(18),
                    dp(18),
                    dp(18),
                    dp(28)
                )
            }

        scroll.addView(root)

        val top =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        top.addView(
            TextView(this).apply {
                text = "☎"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(
                    accent,
                    16f
                )
            },
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            )
        )

        val titleWrap =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

        titleWrap.addView(
            TextView(this).apply {
                text =
                    if (spoken.isBlank()) {
                        "Izaberi kontakt"
                    } else {
                        "Kontakti za „$spoken“"
                    }
                textSize = 25f
                setTextColor(ink)
                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }
        )

        titleWrap.addView(
            TextView(this).apply {
                text =
                    names.size.toString() +
                        if (names.size == 1) {
                            " rezultat"
                        } else {
                            " rezultata"
                        }
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(2), 0, 0)
            }
        )

        top.addView(
            titleWrap,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(top)

        root.addView(
            TextView(this).apply {
                text = "Skroluj i dodirni kontakt koji želiš da pozoveš."
                textSize = 14f
                setTextColor(green)
                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
                setPadding(
                    dp(14),
                    dp(11),
                    dp(14),
                    dp(11)
                )
                background = rounded(
                    Color.rgb(236, 253, 245),
                    16f
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(14)
            }
        )

        names.indices.forEach { index ->
            val name = names[index]
            val number =
                numbers.getOrNull(index)
                    .orEmpty()
            val details =
                numberDetails.getOrNull(index)
                    .orEmpty()

            val card =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(16),
                        dp(15),
                        dp(16),
                        dp(15)
                    )
                    background = outlined(
                        surface,
                        line,
                        19f
                    )
                    elevation = dp(1).toFloat()
                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        callContact(
                            name,
                            number
                        )
                    }
                }

            card.addView(
                TextView(this).apply {
                    text =
                        name
                            .trim()
                            .firstOrNull()
                            ?.uppercase()
                            ?: "?"
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )
                    background = rounded(
                        accentSoft,
                        99f
                    )
                },
                LinearLayout.LayoutParams(
                    dp(44),
                    dp(44)
                )
            )

            val textWrap =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }

            textWrap.addView(
                TextView(this).apply {
                    text = name
                    textSize = 19f
                    setTextColor(ink)
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )
                }
            )

            textWrap.addView(
                TextView(this).apply {
                    text =
                        if (details.isBlank()) {
                            maskedNumber(number)
                        } else {
                            details
                        }
                    textSize = 12.5f
                    setTextColor(muted)
                    setPadding(0, dp(4), 0, 0)
                }
            )

            card.addView(
                textWrap,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            card.addView(
                TextView(this).apply {
                    text = "Pozovi"
                    textSize = 13f
                    setTextColor(accent)
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )
                }
            )

            root.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(10)
                }
            )
        }

        root.addView(
            Button(this).apply {
                text = "Zatvori"
                isAllCaps = false
                textSize = 15f
                setTextColor(muted)
                background = outlined(
                    surface,
                    line,
                    17f
                )
                setOnClickListener {
                    finishAndRemoveTask()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                topMargin = dp(4)
            }
        )

        setContentView(scroll)
    }

    private fun callContact(
        name: String,
        number: String
    ) {
        if (number.isBlank() || callStarted) {
            return
        }

        // Consume this picker session before leaving the activity. This blocks
        // queued/double taps from submitting more than one Telecom call.
        callStarted = true

        if (
            checkSelfPermission(
                Manifest.permission.CALL_PHONE
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            callStarted = false
            Toast.makeText(
                this,
                "Nema dozvole za pozivanje.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        cancelCandidateNotification()

        AppPrefs.setLastMatch(
            this,
            "Poziv: $name"
        )
        AppPrefs.setStatus(
            this,
            "ČEKAM: ‘HALO TELEFON’"
        )

        val requestId =
            if (sessionId.isNotBlank()) {
                sessionId
            } else {
                "picker-" +
                    SystemClock.elapsedRealtimeNanos()
            }

        val placed =
            runCatching {
                CallPlacer.call(
                    this,
                    number,
                    requestId
                )
            }.getOrElse {
                false
            }

        if (!placed) {
            AppPrefs.setStatus(
                this,
                "Poziv nije ponovljen. ČEKAM: ‘HALO TELEFON’"
            )
        }

        finishAndRemoveTask()
    }

    private fun cancelCandidateNotification() {
        runCatching {
            getSystemService(
                NotificationManager::class.java
            ).cancel(
                VoiceDialService.CANDIDATE_NOTIFICATION_ID
            )
        }
    }

    private fun maskedNumber(
        raw: String
    ): String {
        val digits =
            raw.filter(Char::isDigit)

        return if (digits.length > 4) {
            "Broj: ••• " +
                digits.takeLast(4)
        } else {
            raw
        }
    }

    private fun rounded(
        color: Int,
        radiusDp: Float
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius =
                dp(radiusDp.toInt())
                    .toFloat()
        }

    private fun outlined(
        fill: Int,
        stroke: Int,
        radiusDp: Float
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(
                dp(1),
                stroke
            )
            cornerRadius =
                dp(radiusDp.toInt())
                    .toFloat()
        }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density +
                0.5f
        ).toInt()
}
