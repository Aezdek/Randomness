package xyz.aezdek.aezgram.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Laptop
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import xyz.aezdek.aezgram.BuildConfig
import xyz.aezdek.aezgram.Graph
import xyz.aezdek.aezgram.client.Entity
import xyz.aezdek.aezgram.crypto.SafetyNumber
import xyz.aezdek.aezgram.data.ThemeMode
import xyz.aezdek.aezgram.protocol.AdminStatus
import xyz.aezdek.aezgram.protocol.FollowState
import xyz.aezdek.aezgram.protocol.IdList
import xyz.aezdek.aezgram.protocol.InviteDto
import xyz.aezdek.aezgram.protocol.SessionDto
import xyz.aezdek.aezgram.protocol.SettingsPayload
import xyz.aezdek.aezgram.protocol.StoryPayload
import xyz.aezdek.aezgram.ui.LocalNav
import xyz.aezdek.aezgram.ui.Screen
import xyz.aezdek.aezgram.ui.components.AezButton
import xyz.aezdek.aezgram.ui.components.AezField
import xyz.aezdek.aezgram.ui.components.Avatar
import xyz.aezdek.aezgram.ui.components.ButtonStyle
import xyz.aezdek.aezgram.ui.components.Chip
import xyz.aezdek.aezgram.ui.components.ConfirmDialog
import xyz.aezdek.aezgram.ui.components.MediaImage
import xyz.aezdek.aezgram.ui.components.SectionLabel
import xyz.aezdek.aezgram.ui.components.Spinner
import xyz.aezdek.aezgram.ui.components.TopBar
import xyz.aezdek.aezgram.ui.components.VerifiedBadge
import xyz.aezdek.aezgram.ui.components.ago
import xyz.aezdek.aezgram.ui.messages.ToggleRow
import xyz.aezdek.aezgram.ui.profile.GridTile
import xyz.aezdek.aezgram.ui.theme.Aez
import xyz.aezdek.aezgram.ui.theme.Brand

private class Section(val id: String, val title: String, val icon: ImageVector)

private val SECTIONS = listOf(
    Section("notifications", "Notifications", Icons.Outlined.Notifications),
    Section("archive", "Archive", Icons.Outlined.Archive),
    Section("privacy", "Account privacy", Icons.Outlined.Lock),
    Section("close-friends", "Close friends", Icons.Outlined.StarBorder),
    Section("blocked", "Blocked", Icons.Outlined.Block),
    Section("security", "Security and encryption", Icons.Outlined.Shield),
    Section("account", "Password and account", Icons.Outlined.Key),
    Section("appearance", "Appearance and app lock", Icons.Outlined.Palette),
    Section("updates", "App updates", Icons.Outlined.SystemUpdate),
    Section("admin", "Server and invites", Icons.Outlined.Dns),
)

@Composable
fun Settings(section: String?) {
    val found = SECTIONS.firstOrNull { it.id == section }
    if (found == null) {
        SettingsHome()
        return
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(found.title)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (found.id) {
                "notifications" -> NotificationsSection()
                "archive" -> ArchiveSection()
                "privacy" -> PrivacySection()
                "close-friends" -> CloseFriendsSection()
                "blocked" -> BlockedSection()
                "security" -> SecuritySection()
                "account" -> AccountSection()
                "appearance" -> AppearanceSection()
                "updates" -> UpdatesSection()
                "admin" -> AdminSection()
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun SettingsHome() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val ready = rememberReadiness()
    var signingOut by remember { mutableStateOf(false) }
    fun open(id: String) = nav.go(Screen.Settings(id))
    Column(Modifier.fillMaxSize()) {
        TopBar("Settings and activity")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Aez.colors.elev1).clickable { nav.go(Screen.Profile(me.id)) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(me.id, 54.dp, index, me.username)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(me.username, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Aez.colors.text)
                    Text(if (me.isAdmin) "Admin · end-to-end encrypted" else "End-to-end encrypted", fontSize = 13.sp, color = Aez.colors.text2)
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Aez.colors.text3)
            }
            Group("How you use AezGram") {
                NavRow(Icons.Outlined.Notifications, "Notifications") { open("notifications") }
                NavRow(Icons.Outlined.Call, "Calls and ringing", if (ready.all) "Ready" else "Needs attention", warn = !ready.all) { nav.go(Screen.Readiness) }
                NavRow(Icons.Outlined.Archive, "Archive") { open("archive") }
            }
            Group("Who can see your content") {
                NavRow(Icons.Outlined.Lock, "Account privacy", if (me.isPrivate) "Private" else "Public") { open("privacy") }
                NavRow(Icons.Outlined.StarBorder, "Close friends", index.settings.closeFriends.size.takeIf { it > 0 }?.toString()) { open("close-friends") }
                NavRow(Icons.Outlined.Block, "Blocked") { open("blocked") }
            }
            Group("Your keys") {
                NavRow(Icons.Outlined.Shield, "Security and encryption") { open("security") }
                NavRow(Icons.Outlined.Key, "Password and account") { open("account") }
            }
            Group("This phone") {
                NavRow(Icons.Outlined.Palette, "Appearance and app lock") { open("appearance") }
                NavRow(Icons.Outlined.SystemUpdate, "App updates", BuildConfig.VERSION_NAME) { open("updates") }
            }
            if (me.isAdmin) {
                Group("Server") { NavRow(Icons.Outlined.Dns, "Server and invites") { open("admin") } }
            }
            Group(null) {
                NavRow(Icons.AutoMirrored.Outlined.Logout, "Sign out", danger = true, chevron = false) { signingOut = true }
            }
            Text(
                "AezGram ${BuildConfig.VERSION_NAME}\nYour posts, stories and messages are encrypted on this phone. The server only ever stores ciphertext.",
                fontSize = 12.sp, color = Aez.colors.text3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp).navigationBarsPadding(),
            )
        }
    }
    if (signingOut) {
        ConfirmDialog("Sign out?", "Your account and everything in it stay on the server. Sign back in any time with your password.", "Sign out",
            onConfirm = { signingOut = false; Graph.launch { Graph.client.signOut() } }, onDismiss = { signingOut = false })
    }
}

/** A titled, rounded group of rows. */
@Composable
internal fun Group(title: String?, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SectionLabel(title, Modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
    else Spacer(Modifier.height(22.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Aez.colors.elev1).padding(vertical = 4.dp), content = content)
}

@Composable
internal fun NavRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    warn: Boolean = false,
    danger: Boolean = false,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) Aez.colors.danger else Aez.colors.text, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, fontSize = 15.sp, color = if (danger) Aez.colors.danger else Aez.colors.text, modifier = Modifier.weight(1f))
        if (warn) Box(Modifier.padding(end = 8.dp).size(8.dp).clip(CircleShape).background(Aez.colors.danger))
        if (value != null) Text(value, fontSize = 14.sp, color = Aez.colors.text2)
        if (chevron) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Aez.colors.text3, modifier = Modifier.size(20.dp))
    }
}

/** A rounded card with an optional title and explanation. */
@Composable
internal fun Card(title: String? = null, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Aez.colors.elev1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, color = Aez.colors.text)
        if (note != null) Text(note, fontSize = 13.sp, color = Aez.colors.text2, lineHeight = 18.sp)
        content()
    }
}

private fun saveSettings(change: (SettingsPayload) -> SettingsPayload) = Graph.launch { Graph.client.composer.saveSettings(change) }

// ------------------------------------------------------------------------------------------------------------ sections

@Composable
private fun NotificationsSection() {
    val nav = LocalNav.current
    val index by Graph.client.index.collectAsState()
    val s = index.settings
    Card(note = "Notifications come from your own server over the app's background connection. They never contain message text: the app decrypts it on this phone.") {
        NavRow(Icons.Outlined.Call, "Calls and ringing checklist") { nav.go(Screen.Readiness) }
    }
    Card("Notify me about") {
        ToggleRow(Icons.Outlined.ChatBubbleOutline, "Messages and calls", s.notifyMessages) { saveSettings { it.copy(notifyMessages = !it.notifyMessages) } }
        ToggleRow(Icons.Outlined.ChatBubbleOutline, "Comments and mentions", s.notifyComments) { saveSettings { it.copy(notifyComments = !it.notifyComments) } }
        ToggleRow(Icons.Outlined.FavoriteBorder, "Likes", s.notifyLikes) { saveSettings { it.copy(notifyLikes = !it.notifyLikes) } }
        ToggleRow(Icons.Outlined.PersonAdd, "New followers and requests", s.notifyFollows) { saveSettings { it.copy(notifyFollows = !it.notifyFollows) } }
        ToggleRow(Icons.Outlined.AutoStories, "Story replies and reactions", s.notifyStories) { saveSettings { it.copy(notifyStories = !it.notifyStories) } }
    }
    Text("These switches follow your account to the web app too.", fontSize = 12.sp, color = Aez.colors.text3)
}

@Composable
private fun PrivacySection() {
    val state by Graph.client.state.collectAsState()
    val me = state.me ?: return
    Card {
        ToggleRow(Icons.Outlined.Lock, "Private account", me.isPrivate,
            "Only approved followers see your posts, reels and stories. Your name and photo stay visible to members.") {
            Graph.launch { Graph.client.updateMe(isPrivate = !me.isPrivate) }
        }
        ToggleRow(Icons.Outlined.Visibility, "Show activity status", me.showActivity,
            "People you can see know when you're online. Turning this off hides theirs from you too.") {
            Graph.launch { Graph.client.updateMe(showActivity = !me.showActivity) }
        }
    }
}

@Composable
private fun CloseFriendsSection() {
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val close = index.settings.closeFriends
    val followers = state.users.values.filter { it.followsMe == FollowState.FOLLOWING && !it.deleted }.sortedBy { it.username }
    Card(note = "Close Friends stories have a green ring. Only people on this list see them, and nobody is told they're on it.") {
        if (followers.isEmpty()) Text("People who follow you can be added here.", color = Aez.colors.text2)
        followers.forEach { user ->
            val on = user.id in close
            Row(Modifier.fillMaxWidth().clickable {
                saveSettings { s -> s.copy(closeFriends = if (user.id in s.closeFriends) s.closeFriends - user.id else s.closeFriends + user.id) }
            }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(user.id, 44.dp, index, user.username)
                Spacer(Modifier.width(12.dp))
                Text(user.username, fontWeight = FontWeight.SemiBold, color = Aez.colors.text, modifier = Modifier.weight(1f))
                Icon(if (on) Icons.Filled.Star else Icons.Outlined.StarBorder, if (on) "Remove from close friends" else "Add to close friends",
                    tint = if (on) Brand.closeFriends else Aez.colors.text3, modifier = Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun BlockedSection() {
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    var ids by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(state.users) { ids = Graph.attempt { Graph.client.api.get<IdList>("/blocks").ids } ?: ids }
    Card(note = "Blocked people can't find your profile, see your posts or stories, or message you. They aren't told.") {
        val list = ids
        when {
            list == null -> Spinner(Modifier.align(Alignment.CenterHorizontally))
            list.isEmpty() -> Text("You haven't blocked anyone.", color = Aez.colors.text2)
            else -> list.forEach { id ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(id, 44.dp, index, state.users[id]?.username)
                    Spacer(Modifier.width(12.dp))
                    Text(state.users[id]?.username ?: "Someone", fontWeight = FontWeight.SemiBold, color = Aez.colors.text, modifier = Modifier.weight(1f))
                    AezButton("Unblock", { Graph.launch { Graph.client.composer.unblock(id) } }, style = ButtonStyle.SECONDARY, small = true)
                }
            }
        }
    }
}

@Composable
private fun SecuritySection() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val keys = state.keys ?: return
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<SessionDto>?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }
    var resetStatus by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        sessions = Graph.attempt { Graph.client.api.get<List<SessionDto>>("/auth/sessions") } ?: sessions
    }
    LaunchedEffect(Unit) { load() }

    Card("Your security code") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            QrCode(SafetyNumber.qrPayload(me.id, keys.signPk), Modifier.size(132.dp))
            Spacer(Modifier.width(16.dp))
            DigitGrid(SafetyNumber.code(keys.signPk), columns = 2, fontSize = 15)
        }
        Text("Everything you post and send is encrypted on this phone for exactly the people who may see it. " +
            "The server stores only ciphertext; it can see who follows and messages whom, and when.", fontSize = 13.sp, color = Aez.colors.text2)
    }
    Card("Contacts", "Compare codes with someone in person or on a call, or scan theirs, to prove the server hasn't swapped their keys.") {
        val people = state.users.values.filter { !it.deleted && it.id != me.id }.sortedBy { it.username }
        if (people.isEmpty()) Text("Nobody yet.", color = Aez.colors.text2)
        people.forEach { user ->
            val pin = state.pins[user.id]
            Row(Modifier.fillMaxWidth().clickable { nav.go(Screen.Verify(user.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(user.id, 40.dp, index, user.username)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(user.username, fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                        if (pin?.verified == true) { Spacer(Modifier.width(4.dp)); VerifiedBadge() }
                    }
                    Text(
                        when { pin?.changedFrom != null -> "Security code changed"; pin?.verified == true -> "Verified"; else -> "Not verified" },
                        fontSize = 12.sp, color = if (pin?.changedFrom != null) Aez.colors.danger else Aez.colors.text2,
                    )
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = Aez.colors.text3)
            }
        }
    }
    Card("Where you're signed in") {
        val list = sessions
        if (list == null) Spinner(Modifier.align(Alignment.CenterHorizontally))
        list?.forEach { session ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(Aez.colors.elev2), contentAlignment = Alignment.Center) {
                    Icon(if (session.platform == "android") Icons.Outlined.Smartphone else Icons.Outlined.Laptop, null, tint = Aez.colors.text, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(session.deviceName, fontWeight = FontWeight.SemiBold, color = Aez.colors.text)
                    Text(if (session.current) "This phone" else "Active ${ago(session.lastSeenAt)} ago", fontSize = 12.sp, color = Aez.colors.text2)
                }
                if (!session.current) {
                    AezButton("Sign out", {
                        scope.launch {
                            Graph.attempt { Graph.client.api.delete<Unit>("/auth/sessions/${session.id}") }
                            load()
                        }
                    }, style = ButtonStyle.SECONDARY, small = true)
                }
            }
        }
    }
    Card("Reset encryption keys", "Only if you think your keys or password leaked. You get a new identity; everything you can read is re-sealed to it, and your contacts are told your code changed.") {
        AezField(password, { password = it }, "Password", password = true)
        AezButton(resetStatus ?: "Reset keys", { confirmReset = true }, Modifier.fillMaxWidth(), style = ButtonStyle.DANGER,
            enabled = password.isNotEmpty() && resetStatus == null, busy = resetStatus != null)
    }
    if (confirmReset) {
        ConfirmDialog("Reset your keys?", "Contacts will see a security code change and should verify you again.", "Reset keys",
            onConfirm = {
                confirmReset = false
                scope.launch {
                    resetStatus = "Starting…"
                    val done = Graph.attempt { Graph.client.resetKeys(password) { resetStatus = it } }
                    resetStatus = null
                    password = ""
                    if (done != null) Graph.toast("New keys in place")
                }
            },
            onDismiss = { confirmReset = false })
    }
}

/** Safety-number digits in groups of five. */
@Composable
internal fun DigitGrid(digits: String, columns: Int, fontSize: Int = 20) {
    val groups = SafetyNumber.grouped(digits)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, color = Aez.colors.text, letterSpacing = 1.sp) }
            }
        }
    }
}

@Composable
private fun AccountSection() {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    Card("Change password", "Your keys are re-sealed under the new password and your other devices are signed out. " +
        "If an old copy of the server's data leaked, the old password still opens that copy: after a suspected leak, reset your keys instead.") {
        AezField(current, { current = it }, "Current password", password = true)
        AezField(next, { next = it }, "New password", password = true, supporting = "At least 10 characters. It also protects your encryption keys.")
        AezButton("Change password", {
            scope.launch {
                busy = true
                if (Graph.attempt { Graph.client.changePassword(current, next) } != null) {
                    Graph.toast("Password changed")
                    current = ""
                    next = ""
                }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = current.isNotEmpty() && next.length >= 10 && !busy, busy = busy)
    }
    Card("Delete account", "Deletes your account and everything you posted, for everyone. It may survive in encrypted backups for up to 30 days.") {
        AezField(deletePassword, { deletePassword = it }, "Password", password = true)
        AezButton("Delete my account", { confirmDelete = true }, Modifier.fillMaxWidth(), style = ButtonStyle.DANGER, enabled = deletePassword.isNotEmpty())
    }
    if (confirmDelete) {
        ConfirmDialog("Delete your account?", "This can't be undone.", "Delete forever",
            onConfirm = { confirmDelete = false; Graph.launch { Graph.client.deleteAccount(deletePassword) } },
            onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun ArchiveSection() {
    val nav = LocalNav.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    var stories by remember { mutableStateOf(true) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Stories", selected = stories) { stories = true }
        Chip("Posts", selected = !stories) { stories = false }
    }
    val shown: List<Entity> = if (stories) index.myStoryArchive else index.posts.filter { it.dto.authorId == me.id && it.dto.archived }
    if (shown.isEmpty()) {
        Text(if (stories) "Your stories are kept here after they disappear from your profile." else "Posts you archive are hidden from everyone but you, and appear here.",
            color = Aez.colors.text2)
        return
    }
    // A fixed-height grid inside the scrolling column: archives stay small (a few hundred items at most).
    val rows = (shown.size + 2) / 3
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxWidth().height((rows * (if (stories) 190 else 128)).dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp), userScrollEnabled = false,
    ) {
        items(shown, key = { it.id }) { entity ->
            if (stories) {
                val p = entity.payloadAs<StoryPayload>()
                Box(Modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)).clickable { nav.go(Screen.Stories(listOf(me.id))) }) {
                    if (p?.media != null) MediaImage(p.media?.poster ?: p.media, Modifier.fillMaxSize(), maxEdge = 320)
                    else Box(Modifier.fillMaxSize().background(Brand.gradient), contentAlignment = Alignment.Center) {
                        Text(p?.text?.text.orEmpty(), color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 5, modifier = Modifier.padding(6.dp))
                    }
                    Text(
                        java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(entity.dto.createdAt)),
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x66000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            } else {
                GridTile(entity)
            }
        }
    }
}

@Composable
private fun AppearanceSection() {
    val context = LocalContext.current
    val theme by Graph.prefs.theme.collectAsState()
    val appLock by Graph.prefs.appLock.collectAsState()
    Card("Theme") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Automatic", selected = theme == ThemeMode.SYSTEM) { Graph.prefs.setTheme(ThemeMode.SYSTEM) }
            Chip("Dark", selected = theme == ThemeMode.DARK) { Graph.prefs.setTheme(ThemeMode.DARK) }
            Chip("Light", selected = theme == ThemeMode.LIGHT) { Graph.prefs.setTheme(ThemeMode.LIGHT) }
        }
    }
    Card {
        ToggleRow(Icons.Outlined.Fingerprint, "App lock", appLock, "Ask for your fingerprint, face or screen lock when AezGram opens.") {
            val can = BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            ) == BiometricManager.BIOMETRIC_SUCCESS
            if (!appLock && !can) Graph.toast("Set up a screen lock on this phone first", error = true)
            else Graph.prefs.setAppLock(!appLock)
        }
    }
}

@Composable
private fun AdminSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AdminStatus?>(null) }
    var invites by remember { mutableStateOf<List<InviteDto>?>(null) }
    suspend fun load() {
        status = Graph.attempt { Graph.client.api.get<AdminStatus>("/admin/status") } ?: status
        invites = Graph.attempt { Graph.client.api.get<List<InviteDto>>("/admin/invites") } ?: invites
    }
    LaunchedEffect(Unit) { load() }
    val s = status
    Card("Server") {
        if (s == null) Spinner(Modifier.align(Alignment.CenterHorizontally))
        else {
            Stat("Version", s.version)
            Stat("Members", "${s.users} of ${s.maxUsers}")
            Stat("Stored", Formatter.formatShortFileSize(context, s.dataBytes))
            Stat("Free disk", Formatter.formatShortFileSize(context, s.diskFreeBytes), warn = s.uploadsBlocked)
            if (s.uploadsBlocked) Warning("Uploads are paused until the server has more free disk space.")
            val backupAge = s.lastBackupAt?.let { System.currentTimeMillis() - it }
            Stat("Last backup", s.lastBackupAt?.let { "${ago(it)} ago" } ?: "Never", warn = backupAge == null || backupAge > 48 * 3_600_000L || s.lastBackupOk == false)
            if (s.lastBackupOk == false || backupAge == null || backupAge > 48 * 3_600_000L) {
                Warning(s.backupMessage ?: "No backup in the last 48 hours. See RUNBOOK.md on the server, \"Backups\".")
            }
        }
    }
    Card("Invites", "Each code lets one person join, within 30 days. AezGram holds up to ${s?.maxUsers ?: 10} members.") {
        AezButton("New invite", {
            scope.launch {
                Graph.attempt { Graph.client.api.post<InviteDto>("/admin/invites") }?.let { shareInvite(context, it.code) }
                load()
            }
        }, Modifier.fillMaxWidth(), icon = Icons.Outlined.PersonAdd, enabled = s == null || s.users < s.maxUsers)
        invites?.sortedByDescending { it.createdAt }?.forEach { invite ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(invite.code, fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = if (invite.usedBy != null) Aez.colors.text3 else Aez.colors.text)
                    Text(
                        when {
                            invite.usedBy != null -> "Used"
                            invite.expiresAt < System.currentTimeMillis() -> "Expired"
                            else -> "Made ${ago(invite.createdAt)} ago"
                        },
                        fontSize = 12.sp, color = Aez.colors.text2,
                    )
                }
                if (invite.usedBy == null && invite.expiresAt > System.currentTimeMillis()) {
                    Icon(Icons.Outlined.Share, "Share", tint = Aez.colors.text, modifier = Modifier.clip(CircleShape).clickable { shareInvite(context, invite.code) }.padding(8.dp).size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Aez.colors.text2, modifier = Modifier.weight(1f))
        Text(value, color = if (warn) Aez.colors.danger else Aez.colors.text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun Warning(text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Aez.colors.danger.copy(alpha = 0.12f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.WarningAmber, null, tint = Aez.colors.danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.sp, color = Aez.colors.text)
    }
}

private fun shareInvite(context: Context, code: String) {
    val text = "Join me on AezGram: ${BuildConfig.SERVER_URL}\nInvite code: $code"
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("AezGram invite", text))
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share invite")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
