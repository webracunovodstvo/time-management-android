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
    }

    private val bg = Color.rgb(247, 247, 252)
    private val ink = Color.rgb(31, 31, 36)
    private val muted = Color.rgb(104, 104, 116)
    private val accent = Color.rgb(103, 80, 164)
    private val accentSoft = Color.rgb(238, 232, 255)
    private val green = Color.rgb(24, 121, 78)
    private val greenSoft = Color.rgb(229, 247, 237)

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
                if (
                    intent?.action ==
                    ACTION_CLOSE_PICKER
                ) {
                    finishAndRemoveTask()
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
            IntentFilter(ACTION_CLOSE_PICKER)

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
                orientation =
                    LinearLayout.VERTICAL
                setPadding(
                    dp(18),
                    dp(20),
                    dp(18),
                    dp(26)
                )
            }

        scroll.addView(root)

        statusOverlay =
            TextView(this).apply {
                textSize = 23f
                gravity = Gravity.CENTER
                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
                setTextColor(accent)
                setPadding(
                    dp(14),
                    dp(16),
                    dp(14),
                    dp(16)
                )
                background =
                    rounded(
                        accentSoft,
                        20f
                    )
            }

        root.addView(
            statusOverlay,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        )

        refreshStatusOverlay()

        root.addView(
            TextView(this).apply {
                text = "Potvrdi kontakt"
                textSize = 29f
                setTextColor(ink)
                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
            }
        )

        root.addView(
            TextView(this).apply {
                text =
                    if (spoken.isBlank()) {
                        "Izaberi kontakt."
                    } else {
                        "Prepoznato: „" +
                            spoken +
                            "“"
                    }

                textSize = 15f
                setTextColor(muted)
                setPadding(
                    0,
                    dp(6),
                    0,
                    dp(8)
                )
            }
        )

        root.addView(
            TextView(this).apply {
                text =
                    "ZOVI / MOŽE / OK / OTKAŽI  •  HALO TELEFON = NOVO IME"
                textSize = 18f
                setTextColor(green)
                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
                setPadding(
                    0,
                    dp(3),
                    0,
                    dp(16)
                )
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
                    orientation =
                        LinearLayout.VERTICAL
                    gravity =
                        Gravity.CENTER_VERTICAL
                    setPadding(
                        dp(18),
                        dp(15),
                        dp(18),
                        dp(15)
                    )

                    background =
                        candidateBackground(
                            isSelected
                        )

                    elevation =
                        if (isSelected) {
                            dp(5).toFloat()
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

            val top =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                    gravity =
                        Gravity.CENTER_VERTICAL
                }

            top.addView(
                TextView(this).apply {
                    text =
                        (index + 1).toString()
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )
                    setTextColor(
                        if (isSelected) {
                            Color.WHITE
                        } else {
                            accent
                        }
                    )
                    background =
                        rounded(
                            if (isSelected) {
                                accent
                            } else {
                                accentSoft
                            },
                            99f
                        )
                },
                LinearLayout.LayoutParams(
                    dp(46),
                    dp(46)
                )
            )

            top.addView(
                TextView(this).apply {
                    text = name
                    textSize = 23f
                    setTextColor(ink)
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )
                    setPadding(
                        dp(14),
                        0,
                        0,
                        0
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            card.addView(top)

            val frequency =
                if (usage == 0) {
                    "Još nije birano"
                } else {
                    "Birano " +
                        usage +
                        "×"
                }

            card.addView(
                TextView(this).apply {
                    text =
                        frequency +
                            "   •   " +
                            "%.0f".format(
                                score * 100
                            ) +
                            "%"

                    textSize = 14f
                    setTextColor(muted)
                    setPadding(
                        dp(60),
                        dp(7),
                        0,
                        0
                    )
                }
            )

            val details =
                numberDetails.getOrNull(index)
                    ?.takeIf { it.isNotBlank() }
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

                        "✓ Broj: " +
                            displayNumber
                    }

            card.addView(
                TextView(this).apply {
                    text = details
                    textSize = 15f
                    setTextColor(
                        if (details.contains("Mobilni")) {
                            green
                        } else {
                            ink
                        }
                    )
                    setTypeface(
                        typeface,
                        Typeface.BOLD
                    )
                    setPadding(
                        dp(60),
                        dp(8),
                        0,
                        0
                    )
                }
            )

            if (isSelected) {
                card.addView(
                    TextView(this).apply {
                        text =
                            "✓ IZABRAN  •  čeka samo potvrdu / otkazivanje"
                        textSize = 14f
                        setTextColor(accent)
                        setTypeface(
                            typeface,
                            Typeface.BOLD
                        )
                        setPadding(
                            dp(60),
                            dp(8),
                            0,
                            0
                        )
                    }
                )
            }

            root.addView(
                card,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(11)
                }
            )
        }

        root.addView(
            Button(this).apply {
                text = "Otkaži"
                isAllCaps = false
                textSize = 16f
                setTextColor(muted)
                background =
                    rounded(
                        Color.WHITE,
                        18f
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
                    "TRAŽIM KONTAKT… SAČEKAJ"

                raw.contains(
                    "PREPOZNAJEM",
                    true
                ) ->
                    "PREPOZNAJEM… SAČEKAJ"

                raw.contains(
                    "SLUŠAM",
                    true
                ) ->
                    "SLUŠAM…"

                raw.contains(
                    "Proveravam",
                    true
                ) ||
                    raw.contains(
                        "PROVERAVAM",
                        true
                    ) ->
                    "PROVERAVAM KOMANDU…"

                raw.contains(
                    "OZNAČEN",
                    true
                ) ||
                    raw.contains(
                        "Izabrano",
                        true
                    ) ->
                    "ČEKAM POTVRDU"

                raw.contains(
                    "Pozivam",
                    true
                ) ->
                    raw.uppercase()

                else ->
                    "ČEKAM POTVRDU"
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
                    accentSoft
                } else {
                    Color.WHITE
                }
            )

            cornerRadius =
                dp(22).toFloat()

            setStroke(
                dp(
                    if (selected) {
                        4
                    } else {
                        1
                    }
                ),
                if (selected) {
                    accent
                } else {
                    Color.rgb(
                        228,
                        228,
                        234
                    )
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
                dp(radiusDp.toInt())
                    .toFloat()
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
