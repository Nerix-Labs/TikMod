package com.mts.tikmod.stories

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mts.tikmod.MainActivity
import com.mts.tikmod.Prefs
import com.mts.tikmod.R
import com.mts.tikmod.utils.L
import com.mts.tikmod.utils.XLog
import org.json.JSONObject

/**
 * "@creator deleted a story": one notification per creator when stories you have in the archive get deleted.
 * The story's cover is shown when TikMod already has it. Tapping opens the archive.
 */
object StoryNotifier {
    private const val CHANNEL = "tikmod_stories"

    fun deleted(ctx: Context, entries: List<JSONObject>) {
        if (entries.isEmpty() || !Prefs.bool(ctx, Prefs.KEY_STORY_DELETED_NOTIFY)) return
        runCatching {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, L.t(R.string.channel_stories), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = L.t(R.string.channel_stories_desc)
                })
            }
            entries.groupBy { it.optString("handle").lowercase() }.forEach { (handle, list) ->
                val first = list.first()
                val who = first.optString("nick").ifBlank { "@$handle" }
                val id = ("story_del_$handle").hashCode()
                val open = PendingIntent.getActivity(
                    ctx, id, Intent(ctx, MainActivity::class.java).putExtra("tab", "stories")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val title = L.p(R.plurals.notif_stories_deleted, list.size, who, list.size)
                val text = L.p(R.plurals.notif_stories_deleted_text, list.size)
                val b = NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_delete)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(open).setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                val thumb = StoryStore.thumb(ctx, first)?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
                if (thumb != null) b.setLargeIcon(thumb).setStyle(NotificationCompat.BigPictureStyle().bigPicture(thumb).setSummaryText(text))
                else b.setStyle(NotificationCompat.BigTextStyle().bigText(text))
                nm.notify(id, b.build())
            }
        }.onFailure { XLog.log("Stories: deleted notification failed: ${it.message}") }
    }
}
