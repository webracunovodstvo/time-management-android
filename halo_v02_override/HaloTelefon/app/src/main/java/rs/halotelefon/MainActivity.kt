package rs.halotelefon

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var status: TextView
    private lateinit var wake: TextView
    private lateinit var heard: TextView
    private lateinit var match: TextView
    private lateinit var prefs: SharedPreferences

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs.prefs(this)
        prefs.registerOnSharedPreferenceChangeListener(this)
        setContentView(buildUi())
        refresh()
    }

    private fun buildUi(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "Halo Telefon"
            textSize = 34f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "100% lokalni srpski glasovni pozivi"
            textSize = 17f
            setPadding(0, 8, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "OFFLINE • aplikacija nema INTERNET dozvolu"
            setTextColor(Color.rgb(0, 110, 55))
            textSize = 14f
            setPadding(0, 0, 0, 36)
        })

        status = label(root, "Status", 20f)
        wake = label(root, "Wake profil / poslednji pokušaj", 15f)
        heard = label(root, "Poslednje prepoznato ime", 16f)
        match = label(root, "Kontakt", 16f)

        val start = button("Uključi glasovno pozivanje") {
            if (ensurePermissions()) {
                startForegroundService(Intent(this, VoiceDialService::class.java))
            }
        }
        root.addView(start)

        root.addView(button("Nauči moj izgovor ‘Halo telefon’ (5x)") {
            if (ensurePermissions()) {
                startForegroundService(
                    Intent(this, VoiceDialService::class.java).setAction(VoiceDialService.ACTION_TRAIN_WAKE)
                )
            }
        })

        root.addView(button("Zaustavi slušanje") {
            startService(Intent(this, VoiceDialService::class.java).setAction(VoiceDialService.ACTION_STOP))
        })

        root.addView(TextView(this).apply {
            text = "Kako radi\n\n1. Prvo pritisni ‘Nauči moj izgovor’ i pet puta izgovori samo: Halo telefon. Svaki prihvaćen uzorak mora da promeni brojač 1/5, 2/5…\n2. Uključi glasovno pozivanje. Wake fraza se tada poredi sa tvojim lokalnim audio profilom, bez pretvaranja u tekst.\n3. Kada čuješ kratak ton, reci ime ili ime i prezime. Tek tada se lokalno pokreće Base Whisper za srpski.\n4. Ako je pogodak siguran, telefon poziva. Ako nije, dobićeš dva izbora, a tvoj izbor se lokalno pamti.\n\nAplikacija nema INTERNET dozvolu. Posle restarta telefona slušanje moraš ponovo ručno uključiti zbog Android ograničenja za mikrofon u pozadini."
            textSize = 15f
            setPadding(0, 40, 0, 24)
            setLineSpacing(0f, 1.15f)
        })

        return scroll
    }

    private fun label(root: LinearLayout, title: String, size: Float): TextView {
        root.addView(TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 14, 0, 2)
        })
        return TextView(this).also { tv ->
            tv.textSize = size
            tv.setTextColor(Color.BLACK)
            tv.setPadding(0, 0, 0, 14)
            root.addView(tv)
        }
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 17f
        isAllCaps = false
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 14 }
    }

    private fun ensurePermissions(): Boolean {
        val missing = requiredPermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 42)
            Toast.makeText(this, "Dozvoli mikrofon, kontakte i pozivanje, pa pritisni ponovo.", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private fun refresh() {
        val running = prefs.getBoolean(AppPrefs.KEY_SERVICE_RUNNING, false)
        status.text = (if (running) "● " else "○ ") + prefs.getString(AppPrefs.KEY_STATUS, "Isključeno")
        wake.text = prefs.getString(AppPrefs.KEY_LAST_WAKE, "Wake profil još nije napravljen")
        heard.text = prefs.getString(AppPrefs.KEY_LAST_HEARD, "Još ništa")
        match.text = prefs.getString(AppPrefs.KEY_LAST_MATCH, "Još nema izbora")
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = refresh()

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }
}
