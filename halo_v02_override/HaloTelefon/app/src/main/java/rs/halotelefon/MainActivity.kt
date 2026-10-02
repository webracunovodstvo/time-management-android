package rs.halotelefon

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var statusOverlay: TextView
    private lateinit var statusDetail: TextView
    private lateinit var mainButton: Button
    private lateinit var livePill: TextView

    private val bg = Color.rgb(245, 247, 251)
    private val surface = Color.WHITE
    private val ink = Color.rgb(17, 24, 39)
    private val muted = Color.rgb(107, 114, 128)
    private val line = Color.rgb(229, 231, 235)
    private val accent = Color.rgb(79, 70, 229)
    private val accentDark = Color.rgb(55, 48, 163)
    private val accentSoft = Color.rgb(238, 242, 255)
    private val green = Color.rgb(5, 150, 105)
    private val greenSoft = Color.rgb(236, 253, 245)

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        prefs = AppPrefs.prefs(this)

        if (prefs.getInt("ui_migration", 0) < 9) {
            prefs.edit()
                .putBoolean(AppPrefs.KEY_KEEP_AWAKE, true)
                .putInt("ui_migration", 9)
                .apply()
        }

        prefs.registerOnSharedPreferenceChangeListener(this)
        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }
        scroll.addView(root)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            TextView(this).apply {
                text = "☎"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = gradient(
                    intArrayOf(accent, accentDark),
                    18f
                )
            },
            LinearLayout.LayoutParams(
                dp(54),
                dp(54)
            )
        )

        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }

        titleWrap.addView(
            TextView(this).apply {
                text = "Halo Telefon"
                textSize = 29f
                setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        titleWrap.addView(
            TextView(this).apply {
                text = "Halo telefon → ime → lista → dodir za poziv"
                textSize = 13f
                setTextColor(muted)
                setPadding(0, dp(2), 0, 0)
            }
        )

        header.addView(
            titleWrap,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(header)

        livePill = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
        }

        root.addView(
            livePill,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(14)
            }
        )

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(22))
            background = gradient(
                intArrayOf(accentDark, accent),
                28f
            )
            elevation = dp(6).toFloat()
        }

        statusCard.addView(
            TextView(this).apply {
                text = "STATUS"
                textSize = 11f
                letterSpacing = 0.12f
                setTextColor(Color.argb(205, 255, 255, 255))
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        statusOverlay = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        }
        statusCard.addView(statusOverlay)

        statusDetail = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.argb(220, 255, 255, 255))
            setPadding(0, dp(12), 0, 0)
        }
        statusCard.addView(statusDetail)

        root.addView(
            statusCard,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(16)
            }
        )

        mainButton = Button(this).apply {
            isAllCaps = false
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, 18f)
            elevation = dp(2).toFloat()

            setOnClickListener {
                val running =
                    prefs.getBoolean(
                        AppPrefs.KEY_SERVICE_RUNNING,
                        false
                    )

                if (running) {
                    startService(
                        Intent(
                            this@MainActivity,
                            VoiceDialService::class.java
                        ).setAction(
                            VoiceDialService.ACTION_STOP
                        )
                    )
                } else if (ensurePermissions()) {
                    startForegroundService(
                        Intent(
                            this@MainActivity,
                            VoiceDialService::class.java
                        )
                    )
                }
            }
        }

        root.addView(
            mainButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(58)
            ).apply {
                bottomMargin = dp(10)
            }
        )

        root.addView(
            Button(this).apply {
                text = "Podešavanja i učenje glasa"
                isAllCaps = false
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ink)
                background = outlined(
                    surface,
                    line,
                    18f
                )
                setOnClickListener {
                    startActivity(
                        Intent(
                            this@MainActivity,
                            SettingsActivity::class.java
                        )
                    )
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(54)
            )
        )

        val guide = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(17), dp(18), dp(16))
            background = outlined(
                surface,
                line,
                22f
            )
        }

        guide.addView(
            TextView(this).apply {
                text = "Kako radi"
                textSize = 17f
                setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        guide.addView(stepRow("1", "Reci „Halo telefon“", "Aktivira slušanje imena."))
        guide.addView(stepRow("2", "Reci samo ime", "Na primer: „Petar“."))
        guide.addView(stepRow("3", "Skroluj i dodirni kontakt", "Dodir odmah pokreće poziv."))

        root.addView(
            guide,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(18)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "Sve radi lokalno na telefonu. Srpski model i imenik ostaju na uređaju."
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(muted)
                setPadding(dp(12), dp(18), dp(12), 0)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "v0.24"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(156, 163, 175))
                setPadding(dp(8), dp(16), dp(8), 0)
            }
        )

        return scroll
    }

    private fun stepRow(
        number: String,
        title: String,
        subtitle: String
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)

            addView(
                TextView(this@MainActivity).apply {
                    text = number
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(accent)
                    setTypeface(typeface, Typeface.BOLD)
                    background = rounded(accentSoft, 99f)
                },
                LinearLayout.LayoutParams(
                    dp(36),
                    dp(36)
                )
            )

            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, 0, 0)

                    addView(
                        TextView(this@MainActivity).apply {
                            text = title
                            textSize = 15f
                            setTextColor(ink)
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    )

                    addView(
                        TextView(this@MainActivity).apply {
                            text = subtitle
                            textSize = 12.5f
                            setTextColor(muted)
                            setPadding(0, dp(2), 0, 0)
                        }
                    )
                },
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )
        }

    private fun refresh() {
        val running =
            prefs.getBoolean(
                AppPrefs.KEY_SERVICE_RUNNING,
                false
            )

        val raw =
            prefs.getString(
                AppPrefs.KEY_STATUS,
                "Spreman"
            ) ?: "Spreman"

        statusOverlay.text =
            humanStatus(running, raw)

        statusDetail.text =
            if (!running) {
                "Pokreni slušanje, pa za svaki novi poziv reci „Halo telefon“."
            } else {
                raw
            }

        livePill.text =
            if (running) {
                "●  SLUŠANJE AKTIVNO"
            } else {
                "●  SPREMNO"
            }

        livePill.setTextColor(
            if (running) green else muted
        )

        livePill.background =
            rounded(
                if (running) greenSoft else surface,
                99f
            )

        mainButton.text =
            if (running) {
                "Zaustavi slušanje"
            } else {
                "Pokreni Halo Telefon"
            }

        mainButton.background =
            rounded(
                if (running) {
                    Color.rgb(31, 41, 55)
                } else {
                    accent
                },
                18f
            )
    }

    private fun humanStatus(
        running: Boolean,
        raw: String
    ): String {
        if (!running) {
            return "Spreman"
        }

        return when {
            raw.contains("TRAŽIM", true) ->
                "Tražim kontakt…"

            raw.contains("PREPOZNAJEM", true) ->
                "Prepoznajem…"

            raw.contains("SLUŠAM IME", true) ||
                raw.contains("Čujem ime", true) ->
                "Slušam ime…"

            raw.contains("RECI IME", true) ||
                raw.contains("Posle tona", true) ->
                "Reci ime"

            raw.contains("Lista je otvorena", true) ->
                "Izaberi kontakt"

            raw.contains("Pozivam", true) ->
                raw

            raw.contains("Otkazano", true) ||
                raw.contains("Istekao izbor", true) ->
                "Otkazano"

            raw.contains("HALO TELEFON", true) ||
                raw.contains("Halo telefon", true) ->
                "Čekam „Halo telefon“"

            else ->
                raw.substringBefore(".")
                    .take(52)
        }
    }

    private fun ensurePermissions(): Boolean {
        val missing =
            requiredPermissions.filter {
                checkSelfPermission(it) !=
                    PackageManager.PERMISSION_GRANTED
            }

        if (missing.isNotEmpty()) {
            requestPermissions(
                missing.toTypedArray(),
                42
            )

            Toast.makeText(
                this,
                "Dozvoli mikrofon, kontakte i pozivanje, pa pokušaj ponovo.",
                Toast.LENGTH_LONG
            ).show()

            return false
        }

        return true
    }

    private fun gradient(
        colors: IntArray,
        radiusDp: Float
    ): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            colors
        ).apply {
            cornerRadius =
                dp(radiusDp.toInt()).toFloat()
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

    private fun outlined(
        fill: Int,
        stroke: Int,
        radiusDp: Float
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
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
        if (
            key == AppPrefs.KEY_STATUS ||
            key == AppPrefs.KEY_SERVICE_RUNNING
        ) {
            runOnUiThread {
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }
}
