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
        const val EXTRA_KEYS = "keys"
        const val EXTRA_USES = "uses"
        const val EXTRA_SCORES = "scores"
        const val EXTRA_SELECTED_INDEX = "selectedIndex"
        const val ACTION_CLOSE_PICKER = "rs.halotelefon.CLOSE_PICKER"
    }

    private val bg = Color.rgb(247, 247, 252)
    private val ink = Color.rgb(31, 31, 36)
    private val muted = Color.rgb(104, 104, 116)
    private val accent = Color.rgb(103, 80, 164)
    private val accentSoft = Color.rgb(238, 232, 255)
    private val green = Color.rgb(24, 121, 78)
    private val greenSoft = Color.rgb(229, 247, 237)

    private var spoken: String = ""
    private var names = arrayListOf<String>()
    private var numbers = arrayListOf<String>()
    private var keys = arrayListOf<String>()
    private var uses = arrayListOf<Int>()
    private var scores = DoubleArray(0)
    private var selectedIndex = 0

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_PICKER) finishAndRemoveTask()
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val filter = IntentFilter(ACTION_CLOSE_PICKER)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(closeReceiver, filter)
        }

        loadIntent(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadIntent(intent)
        render()
    }

    private fun loadIntent(intent: Intent) {
        spoken = intent.getStringExtra(EXTRA_SPOKEN).orEmpty()
        names = intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf()
        numbers = intent.getStringArrayListExtra(EXTRA_NUMBERS) ?: arrayListOf()
        keys = intent.getStringArrayListExtra(EXTRA_KEYS) ?: arrayListOf()
        uses = intent.getIntegerArrayListExtra(EXTRA_USES) ?: arrayListOf()
        scores = intent.getDoubleArrayExtra(EXTRA_SCORES) ?: DoubleArray(0)
        selectedIndex = intent.getIntExtra(EXTRA_SELECTED_INDEX, 0)
            .coerceIn(0, (names.size - 1).coerceAtLeast(0))
    }

    private fun render() {
        if (names.isEmpty()) {
            finish()
            return
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Potvrdi kontakt"
            textSize = 31f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = if (spoken.isBlank()) {
                "Izaberi kontakt, pa potvrdi glasom."
            } else {
                "Prepoznato: „" + spoken + "“"
            }
            textSize = 16f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = "Reci: MOŽE, OK ili ZOVI"
            textSize = 20f
            setTextColor(green)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        })

        root.addView(TextView(this).apply {
            text = "Ako nije pravi kontakt, samo izgovori drugo ime. Lista će se promeniti i ponovo čekati potvrdu."
            textSize = 14f
            setTextColor(muted)
            setPadding(0, 0, 0, dp(18))
        })

        names.indices.forEach { index ->
            val isSelected = index == selectedIndex
            val usage = uses.getOrNull(index) ?: 0
            val score = scores.getOrNull(index) ?: 0.0
            val name = names[index]
            val number = numbers.getOrNull(index).orEmpty()
            val key = keys.getOrNull(index).orEmpty()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                background = candidateBackground(isSelected)
                elevation = if (isSelected) dp(5).toFloat() else dp(1).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectedIndex = index
                    sendSelection(key, name, number)
                    render()
                }
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            top.addView(TextView(this).apply {
                text = (index + 1).toString()
                textSize = 20f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (isSelected) Color.WHITE else accent)
                background = rounded(if (isSelected) accent else accentSoft, 99f)
            }, LinearLayout.LayoutParams(dp(46), dp(46)))

            top.addView(TextView(this).apply {
                text = name
                textSize = 24f
                setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(14), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(top)

            val digits = number.filter(Char::isDigit)
            val displayNumber = if (digits.length > 4) "••• " + digits.takeLast(4) else number
            val frequency = if (usage == 0) "Još nije birano" else "Birano " + usage + "×"
            card.addView(TextView(this).apply {
                text = frequency + "   •   " + displayNumber + "   •   " + "%.0f".format(score * 100) + "%"
                textSize = 14f
                setTextColor(muted)
                setPadding(dp(60), dp(7), 0, 0)
            })

            if (isSelected) {
                card.addView(TextView(this).apply {
                    text = "✓ IZABRAN  •  čekam glasovnu potvrdu"
                    textSize = 14f
                    setTextColor(accent)
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(60), dp(8), 0, 0)
                })
            }

            root.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }

        root.addView(Button(this).apply {
            text = "Otkaži"
            isAllCaps = false
            textSize = 16f
            setTextColor(muted)
            background = rounded(Color.WHITE, 18f)
            setOnClickListener { finishAndRemoveTask() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        ).apply { topMargin = dp(6) })

        setContentView(scroll)
    }

    private fun sendSelection(key: String, name: String, number: String) {
        if (key.isBlank() || name.isBlank() || number.isBlank()) return
        startService(
            Intent(this, VoiceDialService::class.java)
                .setAction(VoiceDialService.ACTION_SELECT_CANDIDATE)
                .putExtra(VoiceDialService.EXTRA_LOOKUP_KEY, key)
                .putExtra(VoiceDialService.EXTRA_NAME, name)
                .putExtra(VoiceDialService.EXTRA_NUMBER, number)
        )
    }

    private fun candidateBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            setColor(if (selected) accentSoft else Color.WHITE)
            cornerRadius = dp(22).toFloat()
            setStroke(dp(if (selected) 4 else 1), if (selected) accent else Color.rgb(228, 228, 234))
        }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        runCatching { unregisterReceiver(closeReceiver) }
        super.onDestroy()
    }
}
