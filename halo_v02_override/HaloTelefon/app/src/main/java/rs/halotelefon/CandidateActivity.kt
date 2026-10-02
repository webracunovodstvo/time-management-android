package rs.halotelefon

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
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
    private val greenSoft = Color.rgb(236, 253, 245)

    private var spoken: String = ""
    private var names = arrayListOf<String>()
    private var numbers = arrayListOf<String>()
    private var numberDetails = arrayListOf<String>()
    private var keys = arrayListOf<String>()
    private var sessionId: String = ""
    private var selectedIndex: Int = -1

    private val closeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action ==
                    ACTION_CLOSE_PICKER
                ) {
                    finishAndRemoveTask()
                }
            }
        }

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

        val filter =
            IntentFilter(
                ACTION_CLOSE_PICKER
            )

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                closeReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(
                closeReceiver,
                filter
            )
        }

        loadIntent()
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadIntent()
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

        selectedIndex = -1
    }

    private fun render(
        restoreScrollY: Int = 0
    ) {
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
                orientation =
                    LinearLayout.VERTICAL
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
                orientation =
                    LinearLayout.HORIZONTAL
                gravity =
                    Gravity.CENTER_VERTICAL
            }

        top.addView(
            TextView(this).apply {
                text = "☎"
                textSize = 20f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background =
                    rounded(
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
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(12),
                    0,
                    0,
                    0
                )
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
                setPadding(
                    0,
                    dp(2),
                    0,
                    0
                )
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
                text =
                    if (
                        selectedIndex in
                        names.indices
                    ) {
                        "Izabran je „" +
                            names[selectedIndex] +
                            "“. Reci OKEJ za poziv."
                    } else {
                        "Skroluj, dodirni odgovarajući kontakt, pa reci OKEJ."
                    }

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
                background =
                    rounded(
                        greenSoft,
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
            val selected =
                index == selectedIndex

            val name =
                names[index]

            val number =
                numbers.getOrNull(index)
                    .orEmpty()

            val details =
                numberDetails
                    .getOrNull(index)
                    .orEmpty()

            val key =
                keys.getOrNull(index)
                    .orEmpty()

            val card =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                    gravity =
                        Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(16),
                        dp(15),
                        dp(16),
                        dp(15)
                    )

                    background =
                        candidateBackground(
                            selected
                        )

                    elevation =
                        if (selected) {
                            dp(4).toFloat()
                        } else {
                            dp(1).toFloat()
                        }

                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        if (
                            key.isBlank() ||
                            number.isBlank()
                        ) {
                            return@setOnClickListener
                        }

                        val y =
                            scroll.scrollY

                        selectedIndex =
                            index

                        sendSelection(
                            key,
                            name,
                            number
                        )

                        render(y)
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
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        if (selected) {
                            Color.WHITE
                        } else {
                            accent
                        }
                    )

                    background =
                        rounded(
                            if (selected) {
                                accent
                            } else {
                                accentSoft
                            },
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
                    orientation =
                        LinearLayout.VERTICAL
                    setPadding(
                        dp(12),
                        0,
                        0,
                        0
                    )
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
                    setPadding(
                        0,
                        dp(4),
                        0,
                        0
                    )
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
                    text =
                        if (selected) {
                            "IZABRAN"
                        } else {
                            "Izaberi"
                        }

                    textSize = 12f
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )

                    setTextColor(
                        if (selected) {
                            accent
                        } else {
                            muted
                        }
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
                background =
                    outlined(
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

        if (restoreScrollY > 0) {
            scroll.post {
                scroll.scrollTo(
                    0,
                    restoreScrollY
                )
            }
        }
    }

    private fun sendSelection(
        key: String,
        name: String,
        number: String
    ) {
        startService(
            Intent(
                this,
                VoiceDialService::class.java
            )
                .setAction(
                    VoiceDialService.ACTION_SELECT_CANDIDATE
                )
                .putExtra(
                    VoiceDialService.EXTRA_LOOKUP_KEY,
                    key
                )
                .putExtra(
                    VoiceDialService.EXTRA_NAME,
                    name
                )
                .putExtra(
                    VoiceDialService.EXTRA_NUMBER,
                    number
                )
                .putExtra(
                    VoiceDialService.EXTRA_SESSION_ID,
                    sessionId
                )
                .putExtra(
                    VoiceDialService.EXTRA_SPOKEN,
                    spoken
                )
        )
    }

    private fun candidateBackground(
        selected: Boolean
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(
                if (selected) {
                    Color.rgb(
                        250,
                        250,
                        255
                    )
                } else {
                    surface
                }
            )

            cornerRadius =
                dp(19).toFloat()

            setStroke(
                dp(
                    if (selected) {
                        2
                    } else {
                        1
                    }
                ),
                if (selected) {
                    accent
                } else {
                    line
                }
            )
        }

    private fun maskedNumber(
        raw: String
    ): String {
        val digits =
            raw.filter(Char::isDigit)

        return if (
            digits.length > 4
        ) {
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
                dp(
                    radiusDp.toInt()
                ).toFloat()
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
                dp(
                    radiusDp.toInt()
                ).toFloat()
        }

    private fun dp(value: Int): Int =
        (
            value *
                resources
                    .displayMetrics
                    .density +
                0.5f
        ).toInt()

    override fun onDestroy() {
        runCatching {
            unregisterReceiver(
                closeReceiver
            )
        }
        super.onDestroy()
    }
}
