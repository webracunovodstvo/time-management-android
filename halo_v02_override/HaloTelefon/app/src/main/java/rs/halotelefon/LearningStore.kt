package rs.halotelefon

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ContactUsageStats(
    val totalUses: Int,
    val lastUsed: Long
)

class LearningStore(
    context: Context
) : SQLiteOpenHelper(
    context,
    "learning.db",
    null,
    1
) {
    companion object {
        private const val PHONETIC_PREFIX = "__ph__"
    }

    override fun onCreate(
        db: SQLiteDatabase
    ) {
        db.execSQL(
            """
            CREATE TABLE learned_matches (
                spoken TEXT NOT NULL,
                contact_lookup_key TEXT NOT NULL,
                uses INTEGER NOT NULL DEFAULT 0,
                last_used INTEGER NOT NULL,
                PRIMARY KEY (spoken, contact_lookup_key)
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) = Unit

    fun uses(
        spokenRaw: String,
        lookupKey: String
    ): Int =
        usesForSpoken(spokenRaw)[lookupKey] ?: 0

    fun usesForSpoken(
        spokenRaw: String
    ): Map<String, Int> {
        val spoken =
            SerbianNormalizer.normalize(spokenRaw)
        val phonetic =
            phoneticKey(spokenRaw)

        if (
            spoken.isBlank() &&
            phonetic.isBlank()
        ) {
            return emptyMap()
        }

        val storedForms =
            buildList {
                if (spoken.isNotBlank()) {
                    add(spoken)
                }
                if (phonetic.isNotBlank()) {
                    add(PHONETIC_PREFIX + phonetic)
                }
            }
                .distinct()

        if (storedForms.isEmpty()) {
            return emptyMap()
        }

        val placeholders =
            storedForms.joinToString(",") { "?" }

        val result =
            LinkedHashMap<String, Int>()

        readableDatabase.rawQuery(
            """
            SELECT contact_lookup_key, MAX(uses)
            FROM learned_matches
            WHERE spoken IN ($placeholders)
            GROUP BY contact_lookup_key
            """.trimIndent(),
            storedForms.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                result[c.getString(0)] =
                    c.getInt(1)
            }
        }

        return result
    }

    fun totalUses(
        lookupKey: String
    ): Int =
        statsFor(listOf(lookupKey))[lookupKey]
            ?.totalUses ?: 0

    fun lastUsed(
        lookupKey: String
    ): Long =
        statsFor(listOf(lookupKey))[lookupKey]
            ?.lastUsed ?: 0L

    fun statsFor(
        lookupKeys: Collection<String>
    ): Map<String, ContactUsageStats> {
        val keys =
            lookupKeys
                .filter { it.isNotBlank() }
                .distinct()

        if (keys.isEmpty()) {
            return emptyMap()
        }

        val result =
            LinkedHashMap<String, ContactUsageStats>()

        // Candidate lists contain at most 30 contacts, so one compact IN
        // query replaces dozens of per-contact SQLite queries.
        keys.chunked(400).forEach { chunk ->
            val placeholders =
                chunk.joinToString(",") { "?" }

            val args =
                arrayOf(PHONETIC_PREFIX + "%") +
                    chunk.toTypedArray()

            readableDatabase.rawQuery(
                """
                SELECT contact_lookup_key,
                       COALESCE(SUM(uses), 0),
                       COALESCE(MAX(last_used), 0)
                FROM learned_matches
                WHERE spoken NOT LIKE ?
                  AND contact_lookup_key IN ($placeholders)
                GROUP BY contact_lookup_key
                """.trimIndent(),
                args
            ).use { c ->
                while (c.moveToNext()) {
                    result[c.getString(0)] =
                        ContactUsageStats(
                            totalUses = c.getInt(1),
                            lastUsed = c.getLong(2)
                        )
                }
            }
        }

        return result
    }

    fun record(
        spokenRaw: String,
        lookupKey: String
    ) {
        val spoken =
            SerbianNormalizer.normalize(spokenRaw)

        if (spoken.isBlank()) return

        increment(
            spoken,
            lookupKey
        )

        val phonetic =
            phoneticKey(spokenRaw)

        if (phonetic.isNotBlank()) {
            increment(
                PHONETIC_PREFIX + phonetic,
                lookupKey
            )
        }
    }

    private fun phoneticKey(
        raw: String
    ): String =
        SerbianPhonetics.key(raw)

    private fun queryUses(
        storedSpoken: String,
        lookupKey: String
    ): Int {
        readableDatabase.query(
            "learned_matches",
            arrayOf("uses"),
            "spoken=? AND contact_lookup_key=?",
            arrayOf(
                storedSpoken,
                lookupKey
            ),
            null,
            null,
            null
        ).use { c ->
            return if (c.moveToFirst()) {
                c.getInt(0)
            } else {
                0
            }
        }
    }

    private fun increment(
        storedSpoken: String,
        lookupKey: String
    ) {
        val current =
            queryUses(
                storedSpoken,
                lookupKey
            )

        val cv = ContentValues().apply {
            put(
                "spoken",
                storedSpoken
            )
            put(
                "contact_lookup_key",
                lookupKey
            )
            put(
                "uses",
                current + 1
            )
            put(
                "last_used",
                System.currentTimeMillis()
            )
        }

        writableDatabase.insertWithOnConflict(
            "learned_matches",
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
