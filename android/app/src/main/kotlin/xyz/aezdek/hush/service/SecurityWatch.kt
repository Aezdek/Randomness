package xyz.aezdek.hush.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.protocol.AdminAlertDto
import xyz.aezdek.hush.protocol.SessionDto

/**
 * Security news for this account and its server, raised as notifications whatever else is muted:
 * - a device that signed in since the last one this phone asked about ("Was this you?");
 * - for admins, what's wrong with the server (backups, disk, an update), once per problem.
 * The server says when to look (a 'session' or 'admin' frame on the WebSocket); [ConnectionService] also looks now and then.
 */
object SecurityWatch {
    suspend fun checkSignIns() {
        val client = Graph.client
        if (!client.signedIn) return
        val sessions = client.api.get<List<SessionDto>>("/auth/sessions")
        val prefs = Graph.prefs
        // The first time on this phone, sign-ins before its own are not news.
        if (prefs.signInsSeen == 0L) prefs.signInsSeen = sessions.firstOrNull { it.current }?.createdAt ?: System.currentTimeMillis()
        val fresh = sessions.filter { !it.current && it.createdAt > prefs.signInsSeen }.sortedByDescending { it.createdAt }
        val newest = fresh.firstOrNull() ?: return
        val device = if (fresh.size > 1) "${newest.deviceName} (and ${fresh.size - 1} more)" else newest.deviceName
        Graph.notifier.signIn(newest.id, device, newest.createdAt)
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
        val session = intent.getStringExtra(EXTRA_SESSION) ?: return
        val at = intent.getLongExtra(EXTRA_AT, 0)
        Graph.notifier.cancel(Notifier.ID_SECURITY_SIGN_IN)
        val pending = goAsync()
        Graph.scope.launch {
            try {
                if (at > Graph.prefs.signInsSeen) Graph.prefs.signInsSeen = at
                if (intent.action == NOT_ME) {
                    val ok = runCatching { Graph.client.signOutDevice(session) }.isSuccess
                    Graph.notifier.security(
                        Notifier.ID_SECURITY_SIGN_IN,
                        if (ok) "That device was signed out" else "Couldn't sign that device out",
                        if (ok) "Change your password now, so it can't sign in again. Tap to open Hush." else "Open Hush and sign it out in Settings → Encryption and devices.",
                        if (ok) "settings:account" else "settings:security",
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val NOT_ME = "xyz.aezdek.hush.SIGN_IN_NOT_ME"
        const val WAS_ME = "xyz.aezdek.hush.SIGN_IN_WAS_ME"
        const val EXTRA_SESSION = "session"
        const val EXTRA_AT = "at"
    }
}
