package xyz.aezdek.aezgram.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RingVolume
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import xyz.aezdek.aezgram.Graph
import xyz.aezdek.aezgram.client.Connection
import xyz.aezdek.aezgram.protocol.OkResponse
import xyz.aezdek.aezgram.protocol.TestRingRequest
import xyz.aezdek.aezgram.ui.components.AezButton
import xyz.aezdek.aezgram.ui.components.ButtonStyle
import xyz.aezdek.aezgram.ui.components.TopBar
import xyz.aezdek.aezgram.ui.theme.Aez
import xyz.aezdek.aezgram.ui.theme.Brand

/** What incoming calls need on this phone. Re-read whenever the app resumes, since every fix happens in system settings. */
class ReadinessState(
    val notifications: Boolean,
    val battery: Boolean,
    val fullScreen: Boolean,
    val samsung: Boolean?,
) {
    val all get() = notifications && battery && fullScreen && samsung != false
}

private val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

private fun readiness(context: Context) = ReadinessState(
    notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
    battery = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName),
    fullScreen = Graph.notifier.canUseFullScreen(),
    samsung = if (isSamsung) Graph.prefs.samsungNeverSleeping else null,
)

/** [key] forces a re-read, e.g. after a permission dialog that doesn't pause the activity. */
@Composable
fun rememberReadiness(key: Any? = null): ReadinessState {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    return remember(tick, key) { readiness(context) }
}

private fun Context.open(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

private fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

@SuppressLint("BatteryLife")
@Composable
fun Readiness() {
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val ready = rememberReadiness(refresh)
    val samsungDone = ready.samsung == true
    var ringIn by remember { mutableStateOf<Int?>(null) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(Modifier.fillMaxSize()) {
        TopBar("Calls and ringing")
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(if (ready.all) Aez.colors.elev1 else tint(Aez.colors.danger)).padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(Brand.gradient), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.RingVolume, null, tint = androidx.compose.ui.graphics.Color.White)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (ready.all) "This phone will ring" else "Calls may not ring", style = MaterialTheme.typography.titleMedium, color = Aez.colors.text)
                    Text(
                        if (ready.all) "Even when it's locked and AezGram is in the background. Try a test ring to be sure."
                        else "Fix the items below once. Android and One UI stop background apps from ringing unless you allow it.",
                        fontSize = 13.sp, color = Aez.colors.text2,
                    )
                }
            }

            Check(
                "Notifications allowed", ready.notifications,
                "Incoming calls and messages arrive as notifications.",
                "Allow",
            ) {
                if (Build.VERSION.SDK_INT >= 33 && !ready.notifications) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName), appDetails(context))
            }
            Check(
                "Battery: unrestricted", ready.battery,
                "Keeps the connection to your server open in the background, so calls arrive within a second.",
                "Allow",
            ) {
                context.open(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
            Check(
                "Full-screen calls", ready.fullScreen,
                "Lets an incoming call take over the lock screen. Android 14 and later switch this off for apps installed outside the Play Store.",
                "Allow",
            ) {
                if (Build.VERSION.SDK_INT >= 34) {
                    context.open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")), appDetails(context))
                }
            }
            if (isSamsung) {
                Check(
                    "Samsung: never sleeping", samsungDone,
                    "One UI puts unused apps to sleep and cuts their connection. In the battery settings that open: Background usage limits → " +
                        "Never sleeping apps → + → AezGram. Then come back and tick this.",
                    "Open",
                    secondary = if (samsungDone) "Undo" else "Done",
                    onSecondary = { Graph.prefs.samsungNeverSleeping = !samsungDone; refresh++ },
                ) {
                    context.open(
                        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                        appDetails(context),
                    )
                }
            }
            Check(
                "Connected to your server", state.connection == Connection.ONLINE,
                "AezGram keeps one quiet connection open (the ongoing \"Connected\" notification). It reconnects by itself after a network change.",
                null,
            ) {}

            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Aez.colors.elev1).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Test ring", style = MaterialTheme.typography.titleMedium, color = Aez.colors.text)
                Text("Your server rings this phone 20 seconds after you tap. Lock the screen straight away: it should ring like a call.",
                    fontSize = 13.sp, color = Aez.colors.text2)
                val seconds = ringIn
                AezButton(if (seconds != null) "Ringing in $seconds s… lock your phone" else "Ring me in 20 seconds", {
                    Graph.launch {
                        Graph.client.api.post<OkResponse, TestRingRequest>("/push/test-ring", TestRingRequest(20))
                        for (s in 20 downTo 1) {
                            ringIn = s
                            delay(1_000)
                        }
                        ringIn = null
                    }
                }, Modifier.fillMaxWidth(), style = ButtonStyle.BRAND, enabled = seconds == null)
            }
            Text("If a test ring doesn't come through, check each item again. Some phones also need AezGram allowed under \"Autostart\" or \"Background activity\".",
                fontSize = 12.sp, color = Aez.colors.text3)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Check(
    title: String,
    ok: Boolean,
    body: String,
    action: String?,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    onAction: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Aez.colors.elev1).padding(16.dp)) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, if (ok) "Done" else "Not done",
            tint = if (ok) Aez.colors.success else Aez.colors.text3, modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Aez.colors.text)
            Text(body, fontSize = 13.sp, color = Aez.colors.text2, lineHeight = 18.sp)
            if (!ok || secondary != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ok && action != null) AezButton(action, onAction, small = true)
                    if (secondary != null) AezButton(secondary, onSecondary, small = true, style = ButtonStyle.SECONDARY)
                }
            }
        }
    }
}
