package rs.halotelefon

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat

class HaloCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            HaloCarScreen(carContext)
    }
}

private class HaloCarScreen(carContext: CarContext) : Screen(carContext) {
    private var state = CarVoiceController.State()
    private val controller = CarVoiceController(carContext) { newState ->
        state = newState
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()

        if (state.candidates.isEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle(state.status)
                    .addText("Dodirni mikrofon i reci ime kontakta.")
                    .build()
            )
        } else {
            state.candidates.forEachIndexed { index, candidate ->
                val selected = index == state.selectedIndex
                val row = Row.Builder()
                    .setTitle((if (selected) "✓ " else "") + candidate.name)
                    .addText(
                        "Birano " + candidate.uses + "×  •  " +
                            "poklapanje " + "%.0f".format(candidate.score * 100) + "%"
                    )

                if (selected) {
                    row.addText("IZABRAN • reci MOŽE, OK ili ZOVI")
                }
                list.addItem(row.build())
            }

            list.setOnSelectedListener { index ->
                controller.select(index)
            }
            list.setSelectedIndex(state.selectedIndex.coerceIn(0, state.candidates.lastIndex))
        }

        val micIcon = CarIcon.Builder(
            IconCompat.createWithResource(
                carContext,
                android.R.drawable.ic_btn_speak_now
            )
        ).build()

        val micAction = Action.Builder()
            .setIcon(micIcon)
            .setBackgroundColor(CarColor.BLUE)
            .setOnClickListener {
                controller.listen()
            }
            .build()

        return ListTemplate.Builder()
            .setTitle(
                if (state.candidates.isEmpty()) "Halo Telefon"
                else "Potvrdi kontakt"
            )
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .addAction(micAction)
            .build()
    }
}
