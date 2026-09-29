package rs.halotelefon

import android.content.ContentResolver
import android.provider.ContactsContract

data class ContactPhone(
    val lookupKey: String,
    val displayName: String,
    val number: String
)

class ContactRepository(private val resolver: ContentResolver) {
    fun load(): List<ContactPhone> {
        val out = ArrayList<ContactPhone>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val lookupIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (cursor.moveToNext()) {
                val key = cursor.getString(lookupIndex) ?: continue
                val name = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim().orEmpty()
                if (name.isBlank() || number.isBlank()) continue
                out += ContactPhone(key, name, number)
            }
        }

        // Android/Samsung accounts can expose the same visible contact more than once
        // (Google, SIM, Samsung account). Collapse exact visible-name + number duplicates.
        return out.distinctBy {
            val normalizedName = SerbianNormalizer.normalize(it.displayName)
            val normalizedNumber = it.number.filter(Char::isDigit).takeLast(12)
            "$normalizedName|$normalizedNumber"
        }
    }
}
