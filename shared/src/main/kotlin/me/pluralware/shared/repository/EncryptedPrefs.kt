package me.pluralware.shared.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Opens the app's [EncryptedSharedPreferences] files, the home of every secret it keeps. */
internal object EncryptedPrefs {
    private const val TAG = "EncryptedPrefs"

    /**
     * Opens [file], discarding it if it can't be decrypted. That happens when
     * the file arrived without its Keystore key — a device-to-device transfer,
     * or a restore — and the only way forward is to start over. Losing what it
     * held (the user pairs or shares again) beats a crash on every launch.
     */
    fun open(context: Context, file: String): SharedPreferences =
        try {
            create(context, file)
        } catch (e: Exception) {
            Log.w(TAG, "$file unreadable; discarding it", e)
            context.deleteSharedPreferences(file)
            create(context, file)
        }

    private fun create(context: Context, file: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            file,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
