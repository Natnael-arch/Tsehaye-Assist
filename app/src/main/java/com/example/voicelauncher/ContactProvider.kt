package com.example.voicelauncher

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

/**
 * Digits only, then the last 9 digits, so +251716886327, 0716886327 and 716886327 are one number.
 */
fun normalizePhoneNumber(raw: String): String =
    raw.filter { it.isDigit() }.takeLast(9)

const val NO_CONTACT_ID = -1L

data class Contact(
    val name: String,
    val phoneNumber: String,
    val normalizedName: String,
    val contactId: Long = NO_CONTACT_ID,
    val normalizedNumber: String = normalizePhoneNumber(phoneNumber)
)

/**
 * The data/phones view returns one row per phone number per raw contact, so the same person
 * stored in Google, SIM and local accounts arrives as several rows. Android has already merged
 * them into one aggregated contact, which is what CONTACT_ID identifies.
 */
object ContactDedupe {

    fun byContact(rows: List<Contact>): List<Contact> {
        val seen = HashSet<String>()
        return rows.filter { seen.add(groupKey(it)) }
    }

    private fun groupKey(c: Contact): String =
        if (c.contactId != NO_CONTACT_ID) "id:${c.contactId}|${c.normalizedNumber}"
        else "name:${c.normalizedName}|${c.normalizedNumber}"
}

class ContactProvider(private val context: Context) {

    private val usageStore = UsageStore(context)

    fun fetchContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val contentResolver = context.contentResolver
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID
            ),
            null,
            null,
            null
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val contactIdIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val number = it.getString(numberIndex) ?: continue
                val contactId = if (contactIdIndex >= 0 && !it.isNull(contactIdIndex))
                    it.getLong(contactIdIndex) else NO_CONTACT_ID
                contacts.add(Contact(name, number, normalizeName(name), contactId))
            }
        }

        val deduped = ContactDedupe.byContact(contacts)
        Log.d("ContactProvider", "Fetched ${contacts.size} contacts, ${deduped.size} after dedupe")
        return deduped
    }

    fun fetchContactNames(): List<String> {
        val starredIds = starredContactIds()
        val entries = fetchContacts().map { contact ->
            VocabEntry(
                name = contact.name,
                starred = contact.contactId in starredIds,
                usageCount = usageStore.countFor(
                    usageKeyFor(contact.contactId, contact.normalizedName, contact.normalizedNumber)
                )
            )
        }
        val ranked = VocabOrder.rank(entries)
        Log.d("ContactProvider", "[VOCAB-ORDER] ${ranked.take(20)}")
        return ranked
    }

    private fun starredContactIds(): Set<Long> {
        val ids = mutableSetOf<Long>()
        val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.STARRED)
        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        cursor?.use {
            val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
            val starIndex = it.getColumnIndex(ContactsContract.Contacts.STARRED)
            if (idIndex < 0 || starIndex < 0) {
                Log.w("ContactProvider", "STARRED column unreadable, ranking without starred contacts")
                return emptySet()
            }
            while (it.moveToNext()) {
                if (it.isNull(idIndex)) continue
                // The provider hands STARRED back as an integer column on some ROMs and text on others
                val starred = it.getString(starIndex)
                if (!starred.isNullOrBlank() && starred != "0") ids.add(it.getLong(idIndex))
            }
        }
        Log.d("ContactProvider", "Starred contacts: ${ids.size}")
        return ids
    }

    private fun normalizeName(name: String): String {
        // Lowercase and remove anything that isn't a word character or a space
        // This keeps both Amharic characters and Latin characters
        return name.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .trim()
    }
}
