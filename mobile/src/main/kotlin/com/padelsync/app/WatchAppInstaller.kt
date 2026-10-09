package com.padelsync.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Finds a watch connected to this phone that does not have PadelSync yet,
 * and opens the watch's Play Store at PadelSync on it.
 *
 * A Wear OS app can only be installed from the watch's own Play Store: a
 * phone app can neither carry it nor install it. The closest a phone can get
 * is to open the right page on the watch, so the player needs one tap there.
 *
 * The watch app announces itself with [WATCH_APP_CAPABILITY] (see
 * wear/src/main/res/values/wear.xml). Google Play services only answers
 * for an app with the same app ID and signing key as this one, which the
 * phone and watch apps share.
 *
 * Nothing here throws: a phone without Google Play services, or without any
 * watch, simply has no watch to offer the app to.
 */
class WatchAppInstaller(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A watch connected to this phone. */
    data class Watch(val id: String, val name: String)

    /**
     * A connected watch without PadelSync, other than those the player has
     * answered "Not now" for, or `null` if there is none.
     */
    suspend fun watchWithoutApp(): Watch? {
        return try {
            val connected = Wearable.getNodeClient(app).connectedNodes.awaitResult()
            if (connected.isEmpty()) return null
            val withApp = Wearable.getCapabilityClient(app)
                .getCapability(WATCH_APP_CAPABILITY, CapabilityClient.FILTER_ALL)
                .awaitResult()
                .nodes
                .map { it.id }
                .toSet()
            val dismissed = dismissed()
            connected
                .firstOrNull { it.id !in withApp && it.id !in dismissed }
                ?.let { Watch(it.id, it.displayName.ifBlank { "Your watch" }) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No Google Play services, no Wear OS pairing, or the watch went
            // out of reach while asking: there is nothing to offer.
            null
        }
    }

    /** Stops offering the app on [watch]. Another watch is still offered it. */
    fun dismiss(watch: Watch) {
        prefs.edit().putStringSet(KEY_DISMISSED, dismissed() + watch.id).apply()
    }

    /**
     * Opens PadelSync's page in the Play Store on [watch]. [onResult] is
     * called on the main thread with whether the watch received it.
     */
    fun openStoreOn(watch: Watch, onResult: (Boolean) -> Unit) {
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse("market://details?id=$storeAppId"))
        val main = ContextCompat.getMainExecutor(app)
        try {
            val sent = RemoteActivityHelper(app, main).startRemoteActivity(intent, watch.id)
            sent.addListener({ onResult(runCatching { sent.get() }.isSuccess) }, main)
        } catch (_: Exception) {
            onResult(false)
        }
    }

    // Copied, not live: the set from getStringSet must not be changed in place.
    private fun dismissed(): Set<String> = prefs.getStringSet(KEY_DISMISSED, null)?.toSet().orEmpty()

    /**
     * The app ID of the Play Store version. A test build carries ".test" on
     * its ID and is not on the Play Store; it opens the store version's page,
     * which is the one players install.
     */
    private val storeAppId: String = app.packageName.removeSuffix(TEST_BUILD_SUFFIX)

    private companion object {
        /** Must match the item in wear/src/main/res/values/wear.xml. */
        const val WATCH_APP_CAPABILITY = "padelsync_watch_app"
        const val TEST_BUILD_SUFFIX = ".test"
        const val PREFS = "padelsync_watch_app"
        const val KEY_DISMISSED = "dismissed_watches"
    }
}

/** Waits for a Google Play services task, as a coroutine. */
private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}
