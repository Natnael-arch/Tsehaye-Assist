package com.example.voicelauncher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest

/** ANDROID_ID is scoped to the signing key, so it survives reinstall only while that key is unchanged. */
class DeviceIdentity(private val context: Context) {

    private val prefs = context.getSharedPreferences("tsehay_identity", Context.MODE_PRIVATE)

    val deviceId: String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    val signatureHash: String = signingCertSha256()

    val appVersion: String = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"

    fun token(): String = prefs.getString(KEY_TOKEN, "").orEmpty()

    fun saveToken(token: String) {
        if (token.isEmpty()) return
        prefs.edit().putString(KEY_TOKEN, token).apply()
        Log.i(TAG, "Relay token stored (${token.length} chars)")
    }

    private fun signingCertSha256(): String {
        return try {
            val pm = context.packageManager
            val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo.apkContentsSigners.first().toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures.first().toByteArray()
            }
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the signing certificate", e)
            ""
        }
    }

    private companion object {
        const val TAG = "DeviceIdentity"
        const val KEY_TOKEN = "relay_token"
    }
}
