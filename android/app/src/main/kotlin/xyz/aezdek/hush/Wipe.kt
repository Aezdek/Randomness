package xyz.aezdek.hush

import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Takes Hush's account off this phone at once: the session, the keys, every message and photo it had decrypted, and
 * what it remembered about hidden chats. Nothing on the server changes, so signing in again brings everything back.
 * Used by the duress PIN and the quick settings tile.
 */
object Wipe {
    suspend fun now(context: Context) {
        // Tell the server, so this phone's session can't be used again; but don't wait on a bad connection.
        runCatching { withTimeoutOrNull(4_000) { Graph.client.signOut() } }
        runCatching { Graph.client.signOut(tellServer = false) }
        runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        runCatching { Graph.notifier.cancelAll() }
        Graph.prefs.forgetPrivate()
    }
}
