package xyz.aezdek.hush.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.protocol.AdminAlertDto

/**
 * Security news for this account and its server, raised as notifications whatever else is muted:
 * - a device that signed in since the last one this phone asked about ("Was this you?");
 * - for admins, what's wrong with the server (backups, disk, an update), once per problem.
 * The server says when to look (a 'session' or 'admin' frame on the WebSocket); [ConnectionService] also looks now and then.
 */
object SecurityWatch {
    suspend fun checkSignIns() {
        val client = Graph.client
        val me = client.state.value.me?.id ?: return
        val prefs = Graph.prefs
        // The first time on this phone, sign-ins before its own are not news.
        if (prefs.signInsSeen(me) == 0L) prefs.setSignInsSeen(me, client.ownSignInAt() ?: System.currentTimeMillis())
        val fresh = client.newSignIns(prefs.signInsSeen(me))
        val newest = fresh.firstOrNull() ?: return
        val device = if (fresh.size > 1) "${newest.deviceName} (and ${fresh.size - 1} more)" else newest.deviceName
        Graph.notifier.signIn(me, newest.sessionId, device, newest.at, stillIn = fresh.any { it.active })
    }

    suspend fun checkAdminAlerts() {
        val client = Graph.client
        if (!client.signedIn || client.state.value.me?.isAdmin != true) return
        val alerts = client.api.get<List<AdminAlertDto>>("/admin/alerts")
        val prefs = Graph.prefs
        val told = prefs.adminAlertsTold
        for (alert in alerts.filter { it.id !in told }) {
            Graph.notifier.security(Notifier.ID_ADMIN_ALERT + alert.kind.hashCode().and(0xFF), alert.title, alert.text, "settings:admin")
        }
        // Remember only what is still current, so a problem that comes back is told again.
        prefs.adminAlertsTold = alerts.map { it.id }.toSet()
    }
}

/** The actions on the "New sign-in" notification. Not exported: only Hush's own notification can send these. */
class SecurityActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val user = intent.getStringExtra(EXTRA_USER) ?: return
        val at = intent.getLongExtra(EXTRA_AT, 0)
        Graph.notifier.cancel(Notifier.ID_SECURITY_SIGN_IN)
        val pending = goAsync()
        Graph.scope.launch {
            try {
                // Only for the account the alert was about, and only if it is still the one signed in here.
                if (Graph.client.state.value.me?.id != user) return@launch
                val since = Graph.prefs.signInsSeen(user)
                if (intent.action == NOT_ME) {
                    // Every sign-in the alert covered that is still in, not just the newest.
                    val ok = runCatching {
                        Graph.client.newSignIns(since).filter { it.active && it.at <= at }.forEach { Graph.client.signOutDevice(it.sessionId) }
                    }.isSuccess
                    Graph.notifier.security(
                        Notifier.ID_SECURITY_SIGN_IN,
                        if (ok) "Signed out. Now change your password" else "Couldn't sign that device out",
                        if (ok) "Whoever it was knew your password. Change it, and your keys with it, so they can't come back or read what they copied. Tap to open Hush."
                        else "Open Hush and sign it out in Settings → Security and encryption.",
                        if (ok) "settings:account" else "settings:security",
                    )
                }
                if (at > since) Graph.prefs.setSignInsSeen(user, at)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val NOT_ME = "xyz.aezdek.hush.SIGN_IN_NOT_ME"
        const val WAS_ME = "xyz.aezdek.hush.SIGN_IN_WAS_ME"
        const val EXTRA_USER = "user"
        const val EXTRA_AT = "at"
    }
}
