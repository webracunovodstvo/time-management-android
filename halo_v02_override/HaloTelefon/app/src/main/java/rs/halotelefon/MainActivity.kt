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

    private val bg = Color.rgb(247, 247, 252)
    private val surface = Color.WHITE
    private val ink = Color.rgb(31, 31, 36)
    private val muted = Color.rgb(104, 104, 116)
    private val accent = Color.rgb(103, 80, 164)
    private val accentSoft = Color.rgb(238, 232, 255)
    private val green = Color.rgb(24, 121, 78)
    private val greenSoft = Color.rgb(229, 247, 237)

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

        if (prefs.getInt("ui_migration", 0) < 8) {
            prefs.edit()
                .putBoolean(AppPrefs.KEY_KEEP_AWAKE, true)
                .putInt("ui_migration", 8)
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
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(34))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Halo Telefon"
            textSize = 32f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "●  100% OFFLINE"
            textSize = 13f
            setTextColor(green)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(greenSoft, 99f)
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(10)
            bottomMargin = dp(24)
        })

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(30), dp(20), dp(28))
            background = rounded(surface, 28f)
            elevation = dp(2).toFloat()
        }

        statusOverlay = TextView(this).apply {
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
        }
        statusCard.addView(statusOverlay)

        statusDetail = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(14), 0, 0)
        }
        statusCard.addView(statusDetail)

        root.addView(statusCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(18) })

        mainButton = Button(this).apply {
            isAllCaps = false
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, 18f)
            setOnClickListener {
                val running = prefs.getBoolean(
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
        root.addView(mainButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(58)
        ).apply { bottomMargin = dp(12) })

        root.addView(Button(this).apply {
            text = "Podešavanja"
            isAllCaps = false
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            background = outlined(surface, accent, 18f)
            setOnClickListener {
                startActivity(
                    Intent(
                        this@MainActivity,
                        SettingsActivity::class.java
                    )
                )
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        ))

        root.addView(TextView(this).apply {
            text =
                "U mirovanju mikrofon sluša samo lokalnu wake frazu „Halo telefon“. " +
                    "Whisper se pokreće tek nakon wake fraze."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(dp(10), dp(22), dp(10), 0)
        })

        root.addView(TextView(this).apply {
            text = "v0.16  •  instant wake trening • kontakti sa više brojeva • mobilni prioritet"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(dp(8), dp(26), dp(8), 0)
        })

        return scroll
    }

    private fun refresh() {
        val running = prefs.getBoolean(
            AppPrefs.KEY_SERVICE_RUNNING,
            false
        )

        val raw = prefs.getString(
            AppPrefs.KEY_STATUS,
            "Spreman"
        ) ?: "Spreman"

        statusOverlay.text =
            humanStatus(running, raw)

        statusDetail.text =
            if (!running) {
                "Pritisni Pokreni. Zatim koristi „Halo telefon“ za svaki novi poziv."
            } else {
                raw
            }

        mainButton.text =
            if (running) {
                "Zaustavi slušanje"
            } else {
                "Pokreni Halo Telefon"
            }

        mainButton.background =
            rounded(
                if (running) {
                    Color.rgb(58, 58, 67)
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
        if (!running) return "SPREMNO"

        return when {
            raw.contains("TRAŽIM KONTAKT", true) ->
                "TRAŽIM KONTAKT…"

            raw.contains("PREPOZNAJEM", true) ->
                "PREPOZNAJEM…\nSAČEKAJ"

            raw.contains("SLUŠAM IME", true) ||
                raw.contains("Čujem ime", true) ->
                "SLUŠAM IME…"

            raw.contains("RECI IME", true) ||
                raw.contains("Posle tona", true) ->
                "RECI IME"

            raw.contains("PROVERAVAM", true) ||
                raw.contains("Proveravam", true) ->
                "PROVERAVAM KOMANDU…"

            raw.contains("OZNAČEN", true) ||
                raw.contains("Izabrano", true) ||
                raw.contains("ZOVI /", true) ->
                "ČEKAM POTVRDU"

            raw.contains("Pozivam", true) ->
                raw.uppercase()

            raw.contains("Otkazano", true) ||
                raw.contains("Istekao izbor", true) ->
                "OTKAZANO\nČEKAM: „HALO TELEFON“"

            raw.contains("HALO TELEFON", true) ||
                raw.contains("Halo telefon", true) ->
                "ČEKAM:\n„HALO TELEFON“"

            else ->
                raw.substringBefore(".")
                    .uppercase()
                    .take(48)
        }
    }

    private fun ensurePermissions(): Boolean {
        val missing = requiredPermissions.filter {
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
                "Dozvoli mikrofon, kontakte i pozivanje, pa pritisni ponovo.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }

        return true
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
            runOnUiThread { refresh() }
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
