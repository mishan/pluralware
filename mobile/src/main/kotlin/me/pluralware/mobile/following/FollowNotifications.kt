package me.pluralware.mobile.following

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.pluralware.mobile.MainActivity
import me.pluralware.mobile.R
import me.pluralware.shared.notify.SwitchPayload

/**
 * Posts a followed system's switch. One notification per system, replaced on
 * each switch (tagged by the follow's instance), so a backlog delivered after
 * being offline collapses to the current state (docs/notifications-design.md §4.2).
 */
object FollowNotifications {
    private const val CHANNEL_ID = "friends_fronters"
    private const val ID = 1

    fun show(context: Context, instance: String, payload: SwitchPayload) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val open = openFollowing(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(payload.system)
            .setContentText(payload.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .apply { payload.switchedAtInstant?.let { setWhen(it.toEpochMilli()).setShowWhen(true) } }
            .build()
        try {
            manager.notify(instance, ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post; nothing to do.
        }
    }

    /** The follow code for [system] changed; until they get the new one, nothing arrives. */
    fun showCodeChanged(context: Context, instance: String, system: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Send $system your new follow code")
            .setContentText("Your push service changed your address. Until they have the new code, their switches won't reach you.")
            .setStyle(NotificationCompat.BigTextStyle())
            .setContentIntent(openFollowing(context))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(instance, ID, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post; the screen still shows it.
        }
    }

    fun cancel(context: Context, instance: String) {
        NotificationManagerCompat.from(context).cancel(instance, ID)
    }

    private fun openFollowing(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_FOLLOWING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Friends' fronters",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Switches from systems you follow." }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
