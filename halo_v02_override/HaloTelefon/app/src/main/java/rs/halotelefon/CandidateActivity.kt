package rs.halotelefon

import android.app.Activity
import android.app.NotificationManager
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
    }

    private val bg = Color.rgb(247, 247, 252)
    private val ink = Color.rgb(31, 31, 36)
    private val muted = Color.rgb(104, 104, 116)
    private val accent = Color.rgb(103, 80, 164)
    private val accentSoft = Color.rgb(238, 232, 255)
    private val green = Color.rgb(24, 121, 78)
    private val greenSoft = Color.rgb(229, 247, 237)

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

        val names = intent.getStringArrayListExtra(EXTRA_NAMES).orEmpty()
        val numbers = intent.getStringArrayListExtra(EXTRA_NUMBERS).orEmpty()
        val keys = intent.getStringArrayListExtra(EXTRA_KEYS).orEmpty()
        val uses = intent.getIntegerArrayListExtra(EXTRA_USES).orEmpty()
        val scores = intent.getDoubleArrayExtra(EXTRA_SCORES) ?: DoubleArray(0)
        val spoken = intent.getStringExtra(EXTRA_SPOKEN).orEmpty()

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
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Koga želiš da pozoveš?"
            textSize = 30f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = if (spoken.isBlank()) "Izaberi kontakt" else "Prepoznato: „" + spoken + "“"
            textSize = 16f
            setTextColor(muted)
            setPadding(0, dp(7), 0, dp(20))
        })

        names.indices.forEach { index ->
            val usage = uses.getOrNull(index) ?: 0
            val score = scores.getOrNull(index) ?: 0.0
            val name = names[index]
            val number = numbers.getOrNull(index).orEmpty()
            val key = keys.getOrNull(index).orEmpty()

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = rounded(if (index == 0 && usage > 0) greenSoft else Color.WHITE, 22f)
                elevation = dp(2).toFloat()
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    choose(spoken, key, name, number)
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
                setTextColor(if (index == 0 && usage > 0) green else accent)
                background = rounded(if (index == 0 && usage > 0) Color.WHITE else accentSoft, 99f)
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
            val frequency = if (usage == 0) "Nije još birano" else "Birano " + usage + "×"
            card.addView(TextView(this).apply {
                text = frequency + "   •   " + displayNumber + "   •   " + "%.0f".format(score * 100) + "%"
                textSize = 14f
                setTextColor(muted)
                setPadding(dp(60), dp(7), 0, 0)
            })

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

    private fun choose(spoken: String, key: String, name: String, number: String) {
        if (key.isBlank() || number.isBlank()) return

        val store = LearningStore(this)
        try {
            store.record(spoken, key)
        } finally {
            store.close()
        }

        getSystemService(NotificationManager::class.java).cancel(1002)
        AppPrefs.setLastMatch(this, "Izabrano: " + name)
        AppPrefs.setStatus(this, "Pozivam " + name)
        CallPlacer.call(this, number)
        finishAndRemoveTask()
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
