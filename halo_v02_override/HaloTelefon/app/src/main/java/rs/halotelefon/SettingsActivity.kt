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
    private lateinit var heardValue: TextView
    private lateinit var debugValue: TextView
    private lateinit var matchValue: TextView
    private var fullScreenButton: Button? = null

    private val bg = Color.rgb(247, 247, 252)
    private val surface = Color.WHITE
    private val ink = Color.rgb(31, 31, 36)
    private val muted = Color.rgb(104, 104, 116)
    private val accent = Color.rgb(103, 80, 164)

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
            setPadding(dp(22), dp(24), dp(22), dp(32))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "‹  Nazad"
            textSize = 16f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(18))
            setOnClickListener { finish() }
        })

        root.addView(TextView(this).apply {
            text = "Podešavanja"
            textSize = 30f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "Wake fraza, rad sa ugašenim ekranom i dijagnostika"
            textSize = 14f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(20))
        })

        val wakeCard = card()
        wakeCard.addView(TextView(this).apply {
            text = "Wake fraza"
            textSize = 18f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        wakeCard.addView(TextView(this).apply {
            text = "Nauči aplikaciju kako izgovaraš „Halo telefon“."
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(12))
        })

        wakeCard.addView(Button(this).apply {
            text = "Nauči izgovor „Halo telefon“"
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(accent, 16f)
            setOnClickListener {
                if (ensurePermissions()) {
                    val intent = Intent(
                        this@SettingsActivity,
                        VoiceDialService::class.java
                    ).setAction(
                        VoiceDialService.ACTION_TRAIN_WAKE
                    )

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
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(50)
        ))

        root.addView(wakeCard, cardParams())

        val screenCard = card()

        screenCard.addView(TextView(this).apply {
            text = "Rad sa ugašenim ekranom"
            textSize = 18f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        row.addView(TextView(this).apply {
            text =
                "Wake lock radi samo dok je ekran ugašen. " +
                    "U idle režimu Whisper nije aktivan."
            textSize = 13f
            setTextColor(muted)
        }, LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        ))

        keepAwakeSwitch = Switch(this).apply {
            isChecked = prefs.getBoolean(
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
            fullScreenButton = Button(this).apply {
                text = "Omogući izbor preko zaključanog ekrana"
                isAllCaps = false
                textSize = 14f
                setTextColor(accent)
                setTypeface(typeface, Typeface.BOLD)
                background = outlined(
                    Color.WHITE,
                    accent,
                    15f
                )
                setOnClickListener {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse(
                                "package:" + packageName
                            )
                        )
                    )
                }
            }

            screenCard.addView(
                fullScreenButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48)
                ).apply {
                    topMargin = dp(12)
                }
            )
        }

        root.addView(screenCard, cardParams())

        val testCard = card()

        testCard.addView(TextView(this).apply {
            text = "Test prepoznavanja imena"
            textSize = 18f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        testCard.addView(TextView(this).apply {
            text =
                "Preskače „Halo telefon“ i odmah sluša ime. " +
                    "Koristi samo za dijagnostiku."
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(12))
        })

        testCard.addView(Button(this).apply {
            text = "Testiraj ime"
            isAllCaps = false
            textSize = 15f
            setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD)
            background = outlined(
                Color.WHITE,
                accent,
                15f
            )
            setOnClickListener {
                if (ensurePermissions()) {
                    val intent = Intent(
                        this@SettingsActivity,
                        VoiceDialService::class.java
                    ).setAction(
                        VoiceDialService.ACTION_TEST_NAME
                    )

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
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48)
        ))

        root.addView(testCard, cardParams())

        val diagCard = card()

        diagCard.addView(TextView(this).apply {
            text = "Dijagnostika"
            textSize = 18f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
        })

        wakeValue = detailRow(
            diagCard,
            "Wake profil"
        )
        heardValue = detailRow(
            diagCard,
            "Prepoznato ime"
        )
        debugValue = detailRow(
            diagCard,
            "Obrada"
        )
        matchValue = detailRow(
            diagCard,
            "Kontakt"
        )

        root.addView(diagCard, cardParams())

        root.addView(TextView(this).apply {
            text =
                "v0.18  •  zaključana potvrda • lokalno • bez INTERNET dozvole"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        })

        return scroll
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(18),
                dp(16),
                dp(18),
                dp(16)
            )
            background = rounded(
                surface,
                22f
            )
        }

    private fun cardParams() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(14)
        }

    private fun detailRow(
        parent: LinearLayout,
        title: String
    ): TextView {
        parent.addView(
            TextView(this).apply {
                text = title.uppercase()
                textSize = 11f
                setTextColor(muted)
                setTypeface(
                    typeface,
                    Typeface.BOLD
                )
                setPadding(
                    0,
                    dp(10),
                    0,
                    dp(3)
                )
            }
        )

        return TextView(this).also { value ->
            value.textSize = 14f
            value.setTextColor(ink)
            parent.addView(value)
        }
    }

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
        if (Build.VERSION.SDK_INT < 34) return

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        fullScreenButton?.visibility =
            if (
                manager.canUseFullScreenIntent()
            ) {
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
            setStroke(
                dp(1),
                stroke
            )
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
        runOnUiThread { refresh() }
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }
}
