package xyz.aezdek.aezprint.navigation

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Maps "Open with", share and notification intents to a screen. */
object IncomingIntents {

    fun destinationFor(intent: Intent): Any? {
        val data = intent.data
        return when {
            intent.action == Intent.ACTION_VIEW && data?.scheme == "aezprint" && data.host == "job" ->
                data.lastPathSegment?.let(::JobDestination)
            intent.action == Intent.ACTION_VIEW && data != null && data.scheme in setOf("content", "file") ->
                PrintDestination(uri = data.toString())
            intent.action == Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { PrintDestination(uri = it.toString()) }
            else -> null
        }
    }
}
