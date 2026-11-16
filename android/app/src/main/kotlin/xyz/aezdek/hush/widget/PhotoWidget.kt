package xyz.aezdek.hush.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.widget.RemoteViews
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.R
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.disguise.Disguise
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.MediaRef
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.MessageType

/**
 * A home screen widget with the newest photo someone sent you in a chat, decrypted on this phone like everything
 * else (the home screen only gets the picture, never a key). View-once and vanish-mode photos never show here, nor
 * photos from hidden chats; while Hush is disguised or signed out the widget is empty.
 */
class PhotoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    companion object {
        @Volatile
        private var shown: String? = null

        /** The newest photo that may be shown, and who sent it. */
        private fun newest(): Pair<Entity, MediaRef>? {
            val state = Graph.client.state.value
            val me = state.me?.id ?: return null
            val hidden = Graph.prefs.hiddenThreads.value
            return Graph.client.index.value.messages.asSequence()
                .filter { (thread, _) -> thread !in hidden }
                .flatMap { it.value.asSequence() }
                .filter { it.dto.authorId != me && it.dto.audience == Audience.THREAD }
                .mapNotNull { m ->
                    val p = m.payloadAs<MessagePayload>()?.takeIf { it.type == MessageType.MEDIA } ?: return@mapNotNull null
                    p.media.firstOrNull { it.mime.startsWith("image/") }?.let { m to it }
                }
                .maxByOrNull { it.first.dto.receivedAt }
        }

        private fun draw(context: Context, bitmap: Bitmap?, caption: String) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, PhotoWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_photo)
            if (bitmap != null) views.setImageViewBitmap(R.id.widget_image, bitmap) else views.setImageViewResource(R.id.widget_image, android.R.color.transparent)
            views.setTextViewText(R.id.widget_text, caption)
            views.setViewVisibility(R.id.widget_text, if (caption.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
            views.setOnClickPendingIntent(R.id.widget_root, Graph.notifier.openIntent("inbox", 31))
            manager.updateAppWidget(ids, views)
        }

        /** Draws the widget again if there is one: called when messages change, and when the disguise does. */
        fun refresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app) ?: return
            if (manager.getAppWidgetIds(ComponentName(app, PhotoWidget::class.java)).isEmpty()) return
            Graph.scope.launch {
                var waited = 0
                while (!Graph.booted.value && waited++ < 100) delay(100)
                runCatching {
                    val found = if (Disguise.isOn) null else newest()
                    if (found == null) {
                        shown = null
                        draw(app, null, "")
                        return@runCatching
                    }
                    val (message, ref) = found
                    if (shown == ref.blobId) return@runCatching
                    // Small enough to hand to the home screen in one piece.
                    val bitmap = Graph.media.bitmap(ref, 512)
                    val state = Graph.client.state.value
                    draw(app, bitmap, Graph.client.index.value.nameIn(message.dto.threadId, message.dto.authorId, state.users).take(40))
                    shown = ref.blobId
                }
            }
        }
    }
}
