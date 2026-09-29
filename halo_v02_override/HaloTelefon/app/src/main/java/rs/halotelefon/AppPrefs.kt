package rs.halotelefon

import android.content.Context

object AppPrefs {
    private const val FILE = "halo_telefon"
    const val KEY_STATUS = "status"
    const val KEY_LAST_HEARD = "last_heard"
    const val KEY_LAST_MATCH = "last_match"
    const val KEY_LAST_WAKE = "last_wake"
    const val KEY_NAME_DEBUG = "name_debug"
    const val KEY_SERVICE_RUNNING = "service_running"
    const val KEY_PENDING_SPOKEN = "pending_spoken"
    const val KEY_WAKE_ALIASES = "wake_aliases"

    fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setStatus(context: Context, value: String) {
        prefs(context).edit().putString(KEY_STATUS, value).apply()
    }

    fun setLastHeard(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_HEARD, value).apply()
    }

    fun setLastMatch(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_MATCH, value).apply()
    }

    fun setLastWake(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_WAKE, value).apply()
    }

    fun setNameDebug(context: Context, value: String) {
        prefs(context).edit().putString(KEY_NAME_DEBUG, value).apply()
    }

    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_SERVICE_RUNNING, running).apply()
    }
}
