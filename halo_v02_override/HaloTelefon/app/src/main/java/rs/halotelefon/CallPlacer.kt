package rs.halotelefon

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.telecom.TelecomManager

object CallPlacer {
    private var lastSessionId: String? = null
    private var lastFallbackCallAtMs: Long = 0L

    @Synchronized
    fun call(
        context: Context,
        number: String,
        sessionId: String? = null
    ): Boolean {
        if (number.isBlank()) {
            return false
        }

        val now =
            SystemClock.elapsedRealtime()

        val normalizedSession =
            sessionId
                ?.trim()
                ?.takeIf {
                    it.isNotEmpty()
                }

        if (normalizedSession != null) {
            if (lastSessionId == normalizedSession) {
                return false
            }

            // The same contact-picker interaction may dial only once.
            lastSessionId =
                normalizedSession
        } else {
            // Legacy callers do not have a session id. Collapse duplicated
            // PendingIntent/click dispatches occurring within a few seconds.
            if (
                now - lastFallbackCallAtMs <
                3_000L
            ) {
                return false
            }

            lastFallbackCallAtMs =
                now
        }

        return try {
            val telecom =
                context.getSystemService(
                    TelecomManager::class.java
                )

            telecom.placeCall(
                Uri.fromParts(
                    "tel",
                    number,
                    null
                ),
                Bundle()
            )

            true
        } catch (t: Throwable) {
            if (
                normalizedSession != null &&
                lastSessionId ==
                normalizedSession
            ) {
                lastSessionId = null
            }

            throw t
        }
    }
}
