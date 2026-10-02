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

class SettingsActivity : Activity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var keepAwakeSwitch: Switch
    private lateinit var wakeValue: TextView
    private lateinit var commandValue: TextView
    private lateinit var heardValue: TextView
    private lateinit var debugValue: TextView
    private lateinit var matchValue: TextView
    private var fullScreenButton: Button? = null

    private val bg = Color.rgb(245, 247, 251)
    private val surface = Color.WHITE
    private val ink = Color.rgb(17, 24, 39)
    private val muted = Color.rgb(107, 114, 128)
    private val line = Color.rgb(229, 231, 235)
    private val accent = Color.rgb(79, 70, 229)
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
        prefs.registerOnSharedPreferenceChangeListener(this)

        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(30))
        }

        scroll.addView(root)

        root.addView(
            TextView(this).apply {
                text = "‹  Nazad"
                textSize = 15f
                setTextColor(accent)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(14))
                setOnClickListener { finish() }
            }
        )

        root.addView(
            TextView(this).apply {
                text = "Podešavanja"
                textSize = 30f
                setTextColor(ink)
                setTypeface(typeface, Typeface.BOLD)
            }
        )

        root.addView(
            TextView(this).apply {
                text = "Nauči glas aplikaciji i proveri šta trenutno prepoznaje."
                textSize = 14f
                setTextColor(muted)
                setPadding(0, dp(5), 0, dp(18))
            }
        )

        val wakeCard = card()
        wakeCard.addView(sectionTag("AKTIVACIJA", accentSoft, accent))
        wakeCard.addView(sectionTitle("Halo telefon"))
        wakeCard.addView(
            sectionText(
                "Snimi svoj izgovor 5 puta. Ovaj profil se koristi samo za aktiviranje aplikacije."
            )
        )

        wakeCard.addView(
            primaryButton(
                "Nauči „Halo telefon“"
            ) {
                startTraining(
                    VoiceDialService.ACTION_TRAIN_WAKE
                )
            },
            buttonParams()
        )

        wakeValue = infoValue()
        wakeCard.addView(wakeValue)

        root.addView(wakeCard, cardParams())

        val commandCard = card()
        commandCard.addView(sectionTag("GLASOVNI IZBOR", greenSoft, green))
        commandCard.addView(sectionTitle("Kratke komande"))
        commandCard.addView(
            sectionText(
                "Nauči OTKAŽI, PRVI, DRUGI, TREĆI, ČETVRTI i PETI. " +
                    "Svaku reč izgovori 3 puta, normalnim tempom."
            )
        )

        commandCard.addView(
            primaryButton(
                "Nauči glasovne komande"
            ) {
                startTraining(
                    VoiceDialService.ACTION_TRAIN_COMMANDS
                )
            },
            buttonParams()
        )

        commandValue = infoValue()
        commandCard.addView(commandValue)

        commandCard.addView(
            TextView(this).apply {
                text = "Savet: reci kratku reč jasno, ali bez razvlačenja. v0.22 prihvata i kraće izgovore."
                textSize = 12.5f
                setTextColor(muted)
                setPadding(0, dp(10), 0, 0)
            }
        )

        root.addView(commandCard, cardParams())

        val screenCard = card()
        screenCard.addView(sectionTag("EKRAN", accentSoft, accent))
        screenCard.addView(sectionTitle("Rad tokom glasovne radnje"))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, 0)
        }

        row.addView(
            TextView(this).apply {
                text = "Drži aplikaciju aktivnom dok biraš kontakt ili potvrđuješ poziv."
                textSize = 13f
                setTextColor(muted)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        keepAwakeSwitch = Switch(this).apply {
            isChecked =
                prefs.getBoolean(
                    AppPrefs.KEY_KEEP_AWAKE,
                    true
                )

            setOnCheckedChangeListener { _, checked ->
                prefs.edit()
                    .putBoolean(
                        AppPrefs.KEY_KEEP_AWAKE,
                        checked
                    )
                    .apply()
            }
        }

        row.addView(keepAwakeSwitch)
        screenCard.addView(row)

        if (Build.VERSION.SDK_INT >= 34) {
            fullScreenButton =
                secondaryButton(
                    "Omogući prikaz preko zaključanog ekrana"
                ) {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:" + packageName)
                        )
                    )
                }

            screenCard.addView(
                fullScreenButton,
                buttonParams().apply {
                    topMargin = dp(12)
                }
            )
        }

        root.addView(screenCard, cardParams())

        val testCard = card()
        testCard.addView(sectionTag("TEST", accentSoft, accent))
        testCard.addView(sectionTitle("Prepoznavanje imena"))
        testCard.addView(
            sectionText(
                "Preskače „Halo telefon“ i odmah sluša ime. Koristi za proveru mikrofona i Whisper prepoznavanja."
            )
        )

        testCard.addView(
            secondaryButton(
                "Testiraj ime kontakta"
            ) {
                startTraining(
                    VoiceDialService.ACTION_TEST_NAME
                )
            },
            buttonParams()
        )

        root.addView(testCard, cardParams())

        val diagCard = card()
        diagCard.addView(sectionTag("DIJAGNOSTIKA", accentSoft, accent))
        diagCard.addView(sectionTitle("Šta aplikacija vidi"))

        heardValue = detailRow(diagCard, "Poslednje prepoznato")
        debugValue = detailRow(diagCard, "Obrada")
        matchValue = detailRow(diagCard, "Kontakt")

        root.addView(diagCard, cardParams())

        root.addView(
            TextView(this).apply {
                text = "Halo Telefon  •  v0.22"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(156, 163, 175))
                setPadding(0, dp(8), 0, 0)
            }
        )

        return scroll
    }

    private fun sectionTag(
        label: String,
        fill: Int,
        textColor: Int
    ): TextView =
        TextView(this).apply {
            text = label
            textSize = 10.5f
            letterSpacing = 0.08f
            setTextColor(textColor)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(fill, 99f)
        }

    private fun sectionTitle(
        value: String
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = 20f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
        }

    private fun sectionText(
        value: String
    ): TextView =
        TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(12))
        }

    private fun infoValue(): TextView =
        TextView(this).apply {
            textSize = 12.5f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }

    private fun primaryButton(
        label: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, 16f)
            setOnClickListener { action() }
        }

    private fun secondaryButton(
        label: String,
        action: () -> Unit
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ink)
            background = outlined(
                surface,
                line,
                16f
            )
            setOnClickListener { action() }
        }

    private fun startTraining(
        action: String
    ) {
        if (!ensurePermissions()) {
            return
        }

        val intent =
            Intent(
                this,
                VoiceDialService::class.java
            ).setAction(action)

        if (
            prefs.getBoolean(
                AppPrefs.KEY_SERVICE_RUNNING,
                false
            )
        ) {
            startService(intent)
        } else {
            startForegroundService(intent)
        }
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(17),
                dp(18),
                dp(17)
            )
            background = outlined(
                surface,
                line,
                22f
            )
            elevation = dp(1).toFloat()
        }

    private fun cardParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(13)
        }

    private fun buttonParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        )

    private fun detailRow(
        parent: LinearLayout,
        title: String,
        reuse: TextView?
    ): TextView {
        parent.addView(
            TextView(this).apply {
                text = title.uppercase()
                textSize = 10.5f
                letterSpacing = 0.06f
                setTextColor(muted)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, dp(3))
            }
        )

        return (reuse ?: TextView(this)).also { value ->
            value.textSize = 13.5f
            value.setTextColor(ink)

            if (value.parent == null) {
                parent.addView(value)
            }
        }
    }

    private fun detailRow(
        parent: LinearLayout,
        title: String
    ): TextView =
        detailRow(parent, title, null)

    private fun refresh() {
        keepAwakeSwitch.isChecked =
            prefs.getBoolean(
                AppPrefs.KEY_KEEP_AWAKE,
                true
            )

        wakeValue.text =
            prefs.getString(
                AppPrefs.KEY_LAST_WAKE,
                "Wake profil još nije napravljen"
            )

        commandValue.text =
            prefs.getString(
                AppPrefs.KEY_COMMAND_PROFILE,
                "Komande još nisu naučene"
            )

        heardValue.text =
            prefs.getString(
                AppPrefs.KEY_LAST_HEARD,
                "Još ništa"
            )

        debugValue.text =
            prefs.getString(
                AppPrefs.KEY_NAME_DEBUG,
                "Još nema testa"
            )

        matchValue.text =
            prefs.getString(
                AppPrefs.KEY_LAST_MATCH,
                "Još nema izbora"
            )

        refreshFullScreenPermission()
    }

    private fun refreshFullScreenPermission() {
        if (Build.VERSION.SDK_INT < 34) {
            return
        }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        fullScreenButton?.visibility =
            if (manager.canUseFullScreenIntent()) {
                View.GONE
            } else {
                View.VISIBLE
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
                43
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

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences?,
        key: String?
    ) {
        runOnUiThread {
            refresh()
        }
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }
}
