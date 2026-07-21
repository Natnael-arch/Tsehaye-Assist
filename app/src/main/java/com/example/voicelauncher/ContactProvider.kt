package com.example.voicelauncher

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

data class Contact(
    val name: String,
    val phoneNumber: String,
    val normalizedName: String
)

class ContactProvider(private val context: Context) {

    fun fetchContacts(): List<Contact> {
        val contacts = mutableListOf<Contact>()
        val contentResolver = context.contentResolver
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {
            val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

            while (it.moveToNext()) {
                val name = it.getString(nameIndex) ?: continue
                val number = it.getString(numberIndex) ?: continue
                val normalized = normalizeName(name)
                contacts.add(Contact(name, number, normalized))
            }
        }

        Log.d("ContactProvider", "Fetched ${contacts.size} contacts")
        return contacts
    }

    private fun normalizeName(name: String): String {
        // Lowercase and remove anything that isn't a word character or a space
        // This keeps both Amharic characters and Latin characters
        return name.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .trim()
    }
}
