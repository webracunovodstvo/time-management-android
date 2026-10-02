package rs.halotelefon

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

class CandidateActivity : Activity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        const val EXTRA_SPOKEN = "spoken"
        const val EXTRA_NAMES = "names"
        const val EXTRA_NUMBERS = "numbers"
        const val EXTRA_NUMBER_DETAILS = "numberDetails"
        const val EXTRA_KEYS = "keys"
        const val EXTRA_USES = "uses"
        const val EXTRA_SCORES = "scores"
        const val EXTRA_SELECTED_INDEX = "selectedIndex"

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
    private val red = Color.rgb(185, 28, 28)
    private val redSoft = Color.rgb(254, 242, 242)

    private lateinit var prefs: SharedPreferences
    private lateinit var statusOverlay: TextView

    private var spoken: String = ""
    private var names = arrayListOf<String>()
    private var numbers = arrayListOf<String>()
    private var numberDetails = arrayListOf<String>()
    private var keys = arrayListOf<String>()
    private var uses = arrayListOf<Int>()
    private var scores = DoubleArray(0)
    private var selectedIndex = 0

    private val closeReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {
                when (intent?.action) {
                    ACTION_CLOSE_PICKER ->
                        finishAndRemoveTask()

                    ACTION_VOICE_SELECTION -> {
                        val index =
                            intent.getIntExtra(
                                EXTRA_VOICE_INDEX,
                                -1
                            )

                        if (index in names.indices) {
                            selectedIndex = index
                            runOnUiThread {
                                render()
                            }
                        }
                    }
                }
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        prefs = AppPrefs.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(this)

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
            IntentFilter().apply {
                addAction(ACTION_CLOSE_PICKER)
                addAction(ACTION_VOICE_SELECTION)
            }

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

        loadIntent(intent)
        render()
    }

    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadIntent(intent)
        render()
    }

    private fun loadIntent(
        intent: Intent
    ) {
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

        uses =
            intent.getIntegerArrayListExtra(
                EXTRA_USES
            ) ?: arrayListOf()

        scores =
            intent.getDoubleArrayExtra(
                EXTRA_SCORES
            ) ?: DoubleArray(0)

        selectedIndex =
            intent.getIntExtra(
                EXTRA_SELECTED_INDEX,
                0
            ).coerceIn(
                0,
                (names.size - 1)
                    .coerceAtLeast(0)
            )
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
                    dp(26)
                )
            }

        scroll.addView(root)

        val topRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

        topRow.addView(
            TextView(this).apply {
                text = "☎"
                textSize = 19f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = rounded(accent, 15f)
            },
            LinearLayout.LayoutParams(
                dp(44),
                dp(44)
            )
        )

        val titleWrap =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

        titleWrap.addView(
            TextView(this).apply {
                text = "Koga zovemo?"
                textSize = 26f
                setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        titleWrap.addView(
            TextView(this).apply {
                text =
                    if (spoken.isBlank()) {
                        "Izaberi kontakt."
                    } else {
                        "Čuo sam: „$spoken“"
                    }
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(2), 0, 0)
            }
        )

        topRow.addView(
            titleWrap,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(topRow)

        statusOverlay =
            TextView(this).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                setPadding(
                    dp(12),
                    dp(9),
                    dp(12),
                    dp(9)
                )
                background = rounded(
                    accentSoft,
                    99f
                )
            }

        root.addView(
            statusOverlay,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(12)
            }
        )

        refreshStatusOverlay()

        val voiceHint =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(
                    dp(16),
                    dp(13),
                    dp(16),
                    dp(13)
                )
                background = rounded(
                    greenSoft,
                    18f
                )
            }

        voiceHint.addView(
            TextView(this).apply {
                text =
                    if (names.size > 1) {
                        "Reci PRVI, DRUGI, TREĆI, ČETVRTI ili PETI"
                    } else {
                        "Reci ZOVI, MOŽE ili OK"
                    }
                textSize = 14.5f
                setTextColor(green)
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        voiceHint.addView(
            TextView(this).apply {
                text = "Za prekid uvek možeš da kažeš OTKAŽI."
                textSize = 12.5f
                setTextColor(Color.rgb(4, 120, 87))
                setPadding(0, dp(3), 0, 0)
            }
        )

        root.addView(
            voiceHint,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(14)
            }
        )

        names.indices.forEach { index ->
            val isSelected =
                index == selectedIndex

            val usage =
                uses.getOrNull(index) ?: 0

            val score =
                scores.getOrNull(index) ?: 0.0

            val name = names[index]
            val number =
                numbers.getOrNull(index)
                    .orEmpty()

            val key =
                keys.getOrNull(index)
                    .orEmpty()

            val card =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(
                        dp(16),
                        dp(15),
                        dp(16),
                        dp(14)
                    )
                    background =
                        candidateBackground(
                            isSelected
                        )
                    elevation =
                        if (isSelected) {
                            dp(4).toFloat()
                        } else {
                            dp(1).toFloat()
                        }

                    isClickable = true
                    isFocusable = true

                    setOnClickListener {
                        selectedIndex = index

                        sendSelection(
                            key,
                            name,
                            number
                        )

                        render()
                    }
                }

            val head =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

            head.addView(
                TextView(this).apply {
                    text = (index + 1).toString()
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(
                        if (isSelected) {
                            Color.WHITE
                        } else {
                            accent
                        }
                    )
                    background = rounded(
                        if (isSelected) {
                            accent
                        } else {
                            accentSoft
                        },
                        99f
                    )
                },
                LinearLayout.LayoutParams(
                    dp(42),
                    dp(42)
                )
            )

            val nameWrap =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)
                }

            nameWrap.addView(
                TextView(this).apply {
                    text = name
                    textSize = 20f
                    setTextColor(ink)
                    setTypeface(typeface, Typeface.BOLD)
                }
            )

            nameWrap.addView(
                TextView(this).apply {
                    text =
                        when (index) {
                            0 -> "Reci „prvi“"
                            1 -> "Reci „drugi“"
                            2 -> "Reci „treći“"
                            3 -> "Reci „četvrti“"
                            else -> "Reci „peti“"
                        }
                    textSize = 12.5f
                    setTextColor(accent)
                    setPadding(0, dp(2), 0, 0)
                }
            )

            head.addView(
                nameWrap,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            if (isSelected) {
                head.addView(
                    TextView(this).apply {
                        text = "IZABRAN"
                        textSize = 10.5f
                        setTextColor(accent)
                        setTypeface(typeface, Typeface.BOLD)
                        setPadding(
                            dp(9),
                            dp(5),
                            dp(9),
                            dp(5)
                        )
                        background = rounded(
                            accentSoft,
                            99f
                        )
                    }
                )
            }

            card.addView(head)

            val details =
                numberDetails.getOrNull(index)
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: run {
                        val digits =
                            number.filter(
                                Char::isDigit
                            )

                        val displayNumber =
                            if (digits.length > 4) {
                                "••• " +
                                    digits.takeLast(4)
                            } else {
                                number
                            }

                        "Broj: $displayNumber"
                    }

            card.addView(
                TextView(this).apply {
                    text = details
                    textSize = 13.5f
                    setTextColor(
                        if (
                            details.contains(
                                "Mobilni",
                                true
                            )
                        ) {
                            green
                        } else {
                            muted
                        }
                    )
                    setPadding(
                        dp(54),
                        dp(9),
                        0,
                        0
                    )
                }
            )

            val usageText =
                if (usage <= 0) {
                    "Nije ranije biran"
                } else {
                    "Biran $usage×"
                }

            card.addView(
                TextView(this).apply {
                    text =
                        usageText +
                            "   •   podudaranje " +
                            "%.0f".format(
                                score * 100
                            ) +
                            "%"
                    textSize = 11.5f
                    setTextColor(
                        Color.rgb(
                            156,
                            163,
                            175
                        )
                    )
                    setPadding(
                        dp(54),
                        dp(5),
                        0,
                        0
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
                text = "Otkaži"
                isAllCaps = false
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(red)
                background = rounded(
                    redSoft,
                    17f
                )
                setOnClickListener {
                    cancelInteraction()
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

    private fun refreshStatusOverlay() {
        if (!::statusOverlay.isInitialized) {
            return
        }

        val raw =
            prefs.getString(
                AppPrefs.KEY_STATUS,
                ""
            ).orEmpty()

        statusOverlay.text =
            when {
                raw.contains(
                    "TRAŽIM KONTAKT",
                    true
                ) ->
                    "Tražim kontakt…"

                raw.contains(
                    "PREPOZNAJEM",
                    true
                ) ->
                    "Prepoznajem…"

                raw.contains(
                    "SLUŠAM",
                    true
                ) ->
                    "Slušam…"

                raw.contains(
                    "Proveravam",
                    true
                ) ||
                    raw.contains(
                        "PROVERAVAM",
                        true
                    ) ->
                    "Proveravam komandu…"

                raw.contains(
                    "IZABERI",
                    true
                ) ->
                    "Čekam glasovni izbor"

                raw.contains(
                    "OZNAČEN",
                    true
                ) ||
                    raw.contains(
                        "Izabrano",
                        true
                    ) ->
                    "Čekam potvrdu poziva"

                raw.contains(
                    "Pozivam",
                    true
                ) ->
                    raw

                else ->
                    "Čekam glasovnu komandu"
            }
    }

    private fun sendSelection(
        key: String,
        name: String,
        number: String
    ) {
        if (
            key.isBlank() ||
            name.isBlank() ||
            number.isBlank()
        ) {
            return
        }

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
        )
    }

    private fun cancelInteraction() {
        startService(
            Intent(
                this,
                VoiceDialService::class.java
            ).setAction(
                VoiceDialService.ACTION_CANCEL_INTERACTION
            )
        )

        finishAndRemoveTask()
    }

    @Deprecated(
        "Use OnBackPressedDispatcher on newer API"
    )
    override fun onBackPressed() {
        cancelInteraction()
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
                dp(20).toFloat()

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

    private fun rounded(
        color: Int,
        radiusDp: Float
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius =
                dp(radiusDp.toInt()).toFloat()
        }

    private fun dp(value: Int): Int =
        (
            value *
                resources.displayMetrics.density +
                0.5f
        ).toInt()

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        if (key == AppPrefs.KEY_STATUS) {
            runOnUiThread {
                refreshStatusOverlay()
            }
        }
    }

    override fun onDestroy() {
        runCatching {
            unregisterReceiver(
                closeReceiver
            )
        }

        prefs.unregisterOnSharedPreferenceChangeListener(
            this
        )

        super.onDestroy()
    }
}
