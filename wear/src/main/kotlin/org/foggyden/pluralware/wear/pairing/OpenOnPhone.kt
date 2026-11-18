package org.foggyden.pluralware.wear.pairing

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.concurrent.futures.await
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlin.coroutines.cancellation.CancellationException

/** The link the phone app opens on; see its manifest. */
const val PHONE_APP_LINK = "pluralware://open"

/**
 * Opens PluralWare on the paired phone, or, if that fails (it isn't installed
 * there, say), its Play Store page. Returns whether the phone was reached.
 */
suspend fun openOnPhone(context: Context): Boolean {
    val helper = RemoteActivityHelper(context)
    suspend fun open(link: String): Boolean = try {
        helper.startRemoteActivity(
            Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse(link)),
        ).await()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
    return open(PHONE_APP_LINK) || open("market://details?id=${context.packageName.removeSuffix(".debug")}")
}
