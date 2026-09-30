package rs.halotelefon

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: SharedPreferences
    private lateinit var statusTitle: TextView
    private lateinit var statusSubtitle: TextView
    private lateinit var micBubble: TextView
    private lateinit var mainButton: Button
    private lateinit var detailPanel: LinearLayout
    private lateinit var wakeValue: TextView
    private lateinit var heardValue: TextView
    private lateinit var debugValue: TextView
    private lateinit var matchValue: TextView
    private lateinit var keepAwakeSwitch: Switch
    private var fullScreenButton: Button? = null

    private val bg = Color.rgb(247, 247, 252)
    private val surface = Color.WHITE
    private val ink = Color.rgb(31, 31, 36)
    private val muted = Color.rgb(104, 104, 116)
    private val accent = Color.rgb(103, 80, 164)
    private val accentSoft = Color.rgb(238, 232, 255)
    private val green = Color.rgb(24, 121, 78)
    private val greenSoft = Color.rgb(229, 247, 237)
    private val danger = Color.rgb(177, 45, 45)

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
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
            setPadding(dp(22), dp(26), dp(22), dp(36))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Halo Telefon"
            textSize = 31f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Srpski glasovni pozivi, potpuno lokalno"
            textSize = 15f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(14))
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
        ).apply { bottomMargin = dp(18) })

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            background = rounded(surface, 28f)
            elevation = dp(2).toFloat()
        }

        micBubble = TextView(this).apply {
            text = "●"
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(accent)
            background = rounded(accentSoft, 99f)
        }
        hero.addView(micBubble, LinearLayout.LayoutParams(dp(92), dp(92)))

        statusTitle = TextView(this).apply {
            textSize = 23f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6))
        }
        hero.addView(statusTitle)

        statusSubtitle = TextView(this).apply {
            textSize = 14f
            setTextColor(muted)
            gravity = Gravity.CENTER
        }
        hero.addView(statusSubtitle)

        root.addView(hero, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        mainButton = Button(this).apply {
            isAllCaps = false
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(18), 0, dp(18), 0)
            background = rounded(accent, 18f)
            setOnClickListener {
                val running = prefs.getBoolean(AppPrefs.KEY_SERVICE_RUNNING, false)
                if (running) {
                    startService(Intent(this@MainActivity, VoiceDialService::class.java)
                        .setAction(VoiceDialService.ACTION_STOP))
                } else if (ensurePermissions()) {
                    startForegroundService(Intent(this@MainActivity, VoiceDialService::class.java))
                }
            }
        }
        root.addView(mainButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
        ).apply { bottomMargin = dp(10) })

        val trainButton = Button(this).apply {
            text = "Nauči izgovor „Halo telefon“"
            isAllCaps = false
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            background = outlined(surface, accent, 18f)
            setOnClickListener {
                if (ensurePermissions()) {
                    startForegroundService(
                        Intent(this@MainActivity, VoiceDialService::class.java)
                            .setAction(VoiceDialService.ACTION_TRAIN_WAKE)
                    )
                }
            }
        }
        root.addView(trainButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { bottomMargin = dp(18) })

        val settingsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(surface, 22f)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        texts.addView(TextView(this).apply {
            text = "Rad sa ugašenim ekranom"
            textSize = 16f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = "Wake lock se uključuje samo dok je ekran ugašen, a gasi čim se ekran upali."
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(3), dp(8), 0)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        keepAwakeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(AppPrefs.KEY_KEEP_AWAKE, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(AppPrefs.KEY_KEEP_AWAKE, checked).apply()
                Toast.makeText(
                    this@MainActivity,
                    if (checked) "Rad sa ugašenim ekranom je uključen." else "Rad sa ugašenim ekranom može biti nepouzdan.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        row.addView(keepAwakeSwitch)
        settingsCard.addView(row)

        if (Build.VERSION.SDK_INT >= 34) {
            fullScreenButton = Button(this).apply {
                text = "Omogući izbor preko zaključanog ekrana"
                isAllCaps = false
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(accent)
                background = outlined(surface, accent, 16f)
                setOnClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + packageName)
                        )
                    )
                }
            }
            settingsCard.addView(fullScreenButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(12) })
        }

        root.addView(settingsCard, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        val detailsHeader = TextView(this).apply {
            text = "Detalji i dijagnostika  ▾"
            textSize = 15f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(12), dp(6), dp(12))
        }
        root.addView(detailsHeader)

        detailPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(16))
            background = rounded(surface, 22f)
            visibility = View.GONE
        }
        wakeValue = detailRow(detailPanel, "Wake profil")
        heardValue = detailRow(detailPanel, "Prepoznato ime")
        debugValue = detailRow(detailPanel, "Dijagnostika")
        matchValue = detailRow(detailPanel, "Kontakt")

        detailPanel.addView(Button(this).apply {
            text = "Testiraj ime bez „Halo telefon“"
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, 16f)
            setOnClickListener {
                if (ensurePermissions()) {
                    val intent = Intent(this@MainActivity, VoiceDialService::class.java)
                        .setAction(VoiceDialService.ACTION_TEST_NAME)
                    if (prefs.getBoolean(AppPrefs.KEY_SERVICE_RUNNING, false)) {
                        startService(intent)
                    } else {
                        startForegroundService(intent)
                    }
                }
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(16) })

        root.addView(detailPanel)

        detailsHeader.setOnClickListener {
            val open = detailPanel.visibility != View.VISIBLE
            detailPanel.visibility = if (open) View.VISIBLE else View.GONE
            detailsHeader.text = if (open) "Detalji i dijagnostika  ▴" else "Detalji i dijagnostika  ▾"
        }

        root.addView(TextView(this).apply {
            text = "v0.13  •  Srpski fonetski matching + fonetsko učenje • 100% lokalno."
            textSize = 12f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(24), dp(8), 0)
        })

        return scroll
    }

    private fun detailRow(parent: LinearLayout, title: String): TextView {
        parent.addView(TextView(this).apply {
            text = title.uppercase()
            textSize = 11f
            setTextColor(muted)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(3))
        })
        return TextView(this).also { value ->
            value.textSize = 15f
            value.setTextColor(ink)
            parent.addView(value)
        }
    }

    private fun refresh() {
        val running = prefs.getBoolean(AppPrefs.KEY_SERVICE_RUNNING, false)
        val rawStatus = prefs.getString(AppPrefs.KEY_STATUS, "Spreman") ?: "Spreman"

        val title = when {
            !running -> "Spreman za slušanje"
            rawStatus.contains("Čujem ime", true) -> "Čujem ime"
            rawStatus.contains("Prepoznajem ime", true) -> "Prepoznajem ime"
            rawStatus.contains("Pozivam", true) -> "Pozivam"
            rawStatus.contains("Halo telefon", true) -> "Slušam"
            else -> rawStatus.substringBefore(".").take(34)
        }

        statusTitle.text = title
        statusSubtitle.text = when {
            !running -> "Pritisni dugme ispod da aktiviraš glasovno buđenje."
            rawStatus.contains("Čujem ime", true) -> "Izgovori ime ili ime i prezime."
            rawStatus.contains("Prepoznajem ime", true) -> "Lokalni model obrađuje snimljeno ime."
            else -> rawStatus
        }

        micBubble.text = if (running) "●" else "○"
        micBubble.setTextColor(if (running) green else accent)
        micBubble.background = rounded(if (running) greenSoft else accentSoft, 99f)

        mainButton.text = if (running) "Zaustavi slušanje" else "Pokreni glasovno pozivanje"
        mainButton.background = rounded(if (running) Color.rgb(58, 58, 67) else accent, 18f)

        wakeValue.text = prefs.getString(AppPrefs.KEY_LAST_WAKE, "Wake profil još nije napravljen")
        heardValue.text = prefs.getString(AppPrefs.KEY_LAST_HEARD, "Još ništa")
        debugValue.text = prefs.getString(AppPrefs.KEY_NAME_DEBUG, "Još nema testa imena")
        matchValue.text = prefs.getString(AppPrefs.KEY_LAST_MATCH, "Još nema izbora")
        refreshFullScreenPermission()
    }

    private fun refreshFullScreenPermission() {
        if (Build.VERSION.SDK_INT < 34) return
        val manager = getSystemService(NotificationManager::class.java)
        fullScreenButton?.visibility =
            if (manager.canUseFullScreenIntent()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        refreshFullScreenPermission()
    }

    private fun ensurePermissions(): Boolean {
        val missing = requiredPermissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 42)
            Toast.makeText(
                this,
                "Dozvoli mikrofon, kontakte i pozivanje, pa pritisni ponovo.",
                Toast.LENGTH_LONG
            ).show()
            return false
        }
        return true
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun outlined(fill: Int, stroke: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(dp(1), stroke)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refresh()
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }
}
