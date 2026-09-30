package rs.halotelefon

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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
    ): Int {
        val spoken =
            SerbianNormalizer.normalize(spokenRaw)
        val phonetic =
            phoneticKey(spokenRaw)

        val exactUses =
            queryUses(spoken, lookupKey)
        val phoneticUses =
            if (phonetic.isBlank()) 0
            else queryUses(
                PHONETIC_PREFIX + phonetic,
                lookupKey
            )

        return maxOf(
            exactUses,
            phoneticUses
        )
    }

    fun totalUses(
        lookupKey: String
    ): Int {
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(SUM(uses), 0)
            FROM learned_matches
            WHERE contact_lookup_key=?
              AND spoken NOT LIKE ?
            """.trimIndent(),
            arrayOf(
                lookupKey,
                PHONETIC_PREFIX + "%"
            )
        ).use { c ->
            return if (c.moveToFirst()) {
                c.getInt(0)
            } else {
                0
            }
        }
    }

    fun lastUsed(
        lookupKey: String
    ): Long {
        readableDatabase.rawQuery(
            """
            SELECT COALESCE(MAX(last_used), 0)
            FROM learned_matches
            WHERE contact_lookup_key=?
              AND spoken NOT LIKE ?
            """.trimIndent(),
            arrayOf(
                lookupKey,
                PHONETIC_PREFIX + "%"
            )
        ).use { c ->
            return if (c.moveToFirst()) {
                c.getLong(0)
            } else {
                0L
            }
        }
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
