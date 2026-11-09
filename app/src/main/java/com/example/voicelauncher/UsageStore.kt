package com.example.voicelauncher

import android.content.Context
import android.content.SharedPreferences

/**
 * Counts the calls and texts the user physically confirmed. The alternatives are unavailable on
 * this device: ContactsContract TIMES_CONTACTED and LAST_TIME_CONTACTED are all zero since API 29,
 * and the call log needs READ_CALL_LOG, which this app does not request.
 */
class UsageStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(key: String) {
        prefs.edit().putInt(key, countFor(key) + 1).apply()
    }

    fun countFor(key: String): Int = prefs.getInt(key, 0)

    companion object {
        private const val PREFS_NAME = "contact_usage"
    }
}
