package xyz.aezdek.hush.ui.messages

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import xyz.aezdek.hush.client.sendArrived
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import xyz.aezdek.hush.Graph
import xyz.aezdek.hush.client.AppState
import xyz.aezdek.hush.client.Entity
import xyz.aezdek.hush.client.Index
import xyz.aezdek.hush.client.albumMedia
import xyz.aezdek.hush.client.liveLocations
import xyz.aezdek.hush.client.planAnswers
import xyz.aezdek.hush.protocol.Audience
import xyz.aezdek.hush.protocol.LiveLocation
import xyz.aezdek.hush.protocol.MessagePayload
import xyz.aezdek.hush.protocol.Plan
import xyz.aezdek.hush.protocol.PlanAnswer
import xyz.aezdek.hush.ui.components.AezButton
import xyz.aezdek.hush.ui.components.AezField
import xyz.aezdek.hush.ui.components.Avatar
import xyz.aezdek.hush.ui.components.MediaImage
import xyz.aezdek.hush.ui.components.since
import xyz.aezdek.hush.ui.theme.Aez
import java.text.DateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

// ------------------------------------------------------------------------------------------------------------- plans

private val ANSWERS = listOf(
    Triple(PlanAnswer.GOING, "Going", Icons.Outlined.Check),
    Triple(PlanAnswer.MAYBE, "Maybe", Icons.AutoMirrored.Outlined.HelpOutline),
    Triple(PlanAnswer.NO, "Can't go", Icons.Outlined.Close),
)

/** A plan in a chat: what, when, where, and who's going. One answer each; tapping it again takes it back. */
@Composable
internal fun PlanCard(message: Entity, plan: Plan, index: Index, state: AppState, accent: Color, modifier: Modifier) {
    val me = state.me ?: return
    // A date no calendar holds (only a modified app sends one) isn't drawn, as on the web.
    if (!xyz.aezdek.hush.client.Display.plausibleTime(plan.at)) {
        Text("A plan that couldn't be shown", fontSize = 13.sp, color = Aez.colors.text3, modifier = modifier.padding(8.dp))
        return
    }
    val answers = index.planAnswers(message.id)
    val mine = answers[me.id]
    val going = answers.filterValues { it == PlanAnswer.GOING }.keys.toList()
    val maybe = answers.filterValues { it == PlanAnswer.MAYBE }.keys.toList()
    val past = plan.at < System.currentTimeMillis() - 3 * 60 * 60 * 1000L
    val day = Instant.ofEpochMilli(plan.at).atZone(ZoneId.systemDefault())
    Column(
        modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(Aez.colors.bubbleOther)
            .border(1.dp, Aez.colors.border, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                Modifier.width(46.dp).clip(RoundedCornerShape(12.dp)).background(Aez.colors.bg).border(1.dp, Aez.colors.border, RoundedCornerShape(12.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    day.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()).uppercase(),
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(Aez.colors.danger).padding(vertical = 2.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text("${day.dayOfMonth}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Aez.colors.text, modifier = Modifier.padding(bottom = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(xyz.aezdek.hush.client.Display.text(plan.title, 100).ifBlank { "Plan" }, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Aez.colors.text)
                Meta(Icons.Outlined.CalendarMonth, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(plan.at)))
                plan.place?.takeIf { it.isNotBlank() }?.let { Meta(Icons.Outlined.Place, xyz.aezdek.hush.client.Display.text(it, 100)) }
                if (going.isNotEmpty() || maybe.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        for (id in going.take(4)) Avatar(id, 20.dp, index, state.users[id]?.username)
                        if (going.isNotEmpty()) Text("  ${going.size} going", fontSize = 12.sp, color = Aez.colors.text)
                        if (maybe.isNotEmpty()) Text("  ${maybe.size} maybe", fontSize = 12.sp, color = Aez.colors.text2)
                    }
                }
            }
        }
        if (!past) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((answer, label, icon) in ANSWERS) {
                    val on = mine == answer
                    val shape = RoundedCornerShape(50)
                    Row(
                        Modifier.weight(1f).clip(shape).then(if (on) Modifier.background(accent) else Modifier.border(1.dp, Aez.colors.borderStrong, shape))
                            .clickable { Graph.launch { Graph.client.composer.answerPlan(message, answer) } }.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (on) Color.White else Aez.colors.text, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else Aez.colors.text, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun Meta(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Aez.colors.text2, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 13.sp, color = Aez.colors.text2)
    }
}

/** Making a plan: what, a day and a time from the system pickers, and where. */
@Composable
internal fun PlanSheet(onDismiss: () -> Unit, onSend: (Plan) -> Unit) {
    val zone = ZoneId.systemDefault()
    val tomorrow = remember { java.time.LocalDate.now(zone).plusDays(1) }
    var title by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(tomorrow) }
    var time by remember { mutableStateOf(LocalTime.of(19, 0)) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    val at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Plan", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
            AezField(title, { title = it.take(100) }, "What's the plan?")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AezButton(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at)), { pickingDate = true }, Modifier.weight(1f), style = xyz.aezdek.hush.ui.components.ButtonStyle.SECONDARY, icon = Icons.Outlined.CalendarMonth)
                AezButton(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)), { pickingTime = true }, Modifier.weight(1f), style = xyz.aezdek.hush.ui.components.ButtonStyle.SECONDARY)
            }
            AezField(place, { place = it.take(100) }, "Where (optional)")
            AezButton("Send plan", {
                onSend(Plan(title.trim(), at, place.trim().ifEmpty { null }))
                onDismiss()
            }, Modifier.fillMaxWidth(), enabled = title.isNotBlank())
            Text("Everyone in the chat can say if they're going. Like everything here, the plan is end-to-end encrypted.", fontSize = 12.sp, color = Aez.colors.text3)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton({
                    picker.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(picker) }
    }
    if (pickingTime) {
        val context = LocalContext.current
        val picker = rememberTimePickerState(time.hour, time.minute, android.text.format.DateFormat.is24HourFormat(context))
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            confirmButton = {
                TextButton({
                    time = LocalTime.of(picker.hour, picker.minute)
                    pickingTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickingTime = false }) { Text("Cancel") } },
            text = { TimePicker(picker) },
        )
    }
}

// ---------------------------------------------------------------------------------------------------- live location

private fun mapsUri(l: LiveLocation): Uri = Uri.parse("geo:${l.lat},${l.lng}?q=${l.lat},${l.lng}")

private fun minutesLeft(until: Long): String {
    // Nobody can share for more than 8 hours; a longer claim shows as 8.
    val m = ((until - System.currentTimeMillis()).coerceIn(0, 8 * 60 * 60_000L) / 60_000L)
    return if (m >= 60) "${m / 60} h ${if (m % 60 > 0) "${m % 60} min" else ""}".trim() else "$m min"
}

/** Someone's live location: a stylised map tile (nothing is fetched from a map service), the coordinates, and how long is left. */
@Composable
internal fun LocationCard(message: Entity, location: LiveLocation, mine: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    if (!xyz.aezdek.hush.client.Display.plausible(location)) {
        Text("A location that couldn't be shown", fontSize = 13.sp, color = Aez.colors.text3, modifier = modifier.padding(8.dp))
        return
    }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(message.id) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    val live = remember(tick, location.until) { location.until > System.currentTimeMillis() }
    val grid = Aez.colors.borderStrong
    val dot = Color(0xFF6EA8FF)
    Column(modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(Aez.colors.bubbleOther)) {
        Box(
            Modifier.fillMaxWidth().height(110.dp).background(if (Aez.colors.isDark) Color(0xFF1C2925) else Color(0xFFE3EFE7))
                .drawBehind {
                    val step = 28.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawLine(grid, Offset(x, 0f), Offset(x, size.height), 1f)
                        x += step
                    }
                    var y = 0f
                    while (y < size.height) {
                        drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                        y += step
                    }
                    drawLine(Color(0x59FFB547), Offset(0f, size.height * 0.85f), Offset(size.width, size.height * 0.15f), 10.dp.toPx())
                    if (live) drawCircle(dot.copy(alpha = 0.25f), 24.dp.toPx(), center)
                    drawCircle(Color.White, 14.dp.toPx(), center)
                    drawCircle(dot, 11.dp.toPx(), center)
                }
                .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, mapsUri(location))) } },
        ) {
            Text(
                "%.4f, %.4f".format(location.lat, location.lng), fontSize = 11.sp, color = Aez.colors.text2,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(50)).background(Aez.colors.glass).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (live) "Live location" else "Location sharing ended", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Aez.colors.text)
            Text(
                (if (live) "Updated ${since(location.updatedAt)} · ${minutesLeft(location.until)} left" else "No longer shared") +
                    (location.accuracy?.takeIf { it > 0 && it <= 100_000 }?.let { " · within ${it.toInt()} m" } ?: ""),
                fontSize = 12.sp, color = Aez.colors.text2,
            )
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AezButton("Open in maps", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, mapsUri(location))) } },
                    small = true, style = xyz.aezdek.hush.ui.components.ButtonStyle.SECONDARY)
                if (mine && live) {
                    AezButton("Stop sharing", { Graph.launch { Graph.client.composer.stopLocation(message) } },
                        small = true, style = xyz.aezdek.hush.ui.components.ButtonStyle.DANGER)
                }
            }
        }
    }
}

private val LOCATION_CHOICES = listOf("15 minutes" to 15 * 60_000L, "1 hour" to 60 * 60_000L, "8 hours" to 8 * 60 * 60_000L)

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** The best provider this phone has switched on, or null. */
private fun provider(manager: LocationManager): String? = listOf(
    if (android.os.Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null,
    LocationManager.GPS_PROVIDER,
    LocationManager.NETWORK_PROVIDER,
).filterNotNull().firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Location? {
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val provider = provider(manager) ?: return null
    return withTimeoutOrNull(20_000) {
        suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            androidx.core.location.LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                if (cont.isActive) cont.resumeWith(Result.success(location))
            }
        }
    } ?: runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
}

/** Starts sharing where you are with a chat for a set time. Asks for location permission first if it needs to. */
@Composable
internal fun LocationSheet(threadId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var choice by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var places by remember { mutableStateOf(Graph.prefs.places) }
    var arriveAt by remember { mutableStateOf<String?>(null) }
    var newPlace by remember { mutableStateOf("") }
    fun share() {
        busy = true
        scope.launch {
            val location = currentLocation(context)
            if (location == null) {
                Graph.toast("Couldn't find where you are. Is location turned on?", error = true)
            } else {
                Graph.attempt {
                    Graph.client.composer.shareLocation(threadId, location.latitude, location.longitude,
                        if (location.hasAccuracy()) location.accuracy else null, LOCATION_CHOICES[choice].second)
                    // "Say when I arrive": remembered on this phone until the share ends.
                    val others = Graph.prefs.arrivals.filter { it.threadId != threadId }
                    val spot = arriveAt?.let { name -> places[name]?.let { name to it } }
                    Graph.prefs.arrivals = if (spot == null) others
                        else others + xyz.aezdek.hush.data.Prefs.Arrival(threadId, System.currentTimeMillis() + LOCATION_CHOICES[choice].second, spot.first, spot.second.first, spot.second.second)
                    onDismiss()
                }
            }
            busy = false
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) share() else Graph.toast("Allow location for Hush to share where you are", error = true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Share live location", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
            Text(
                "The chat sees where you are, updated as you move while Hush is open, until the time is up. Then it's deleted from the server. Only the latest spot is ever stored, never a trail.",
                fontSize = 13.sp, color = Aez.colors.text2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOCATION_CHOICES.forEachIndexed { i, (label, _) ->
                    val on = choice == i
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).clip(shape).then(if (on) Modifier.background(Aez.colors.text) else Modifier.border(1.dp, Aez.colors.borderStrong, shape))
                            .clickable { choice = i }.padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = if (on) Aez.colors.bg else Aez.colors.text) }
                }
            }
            Text("Say when I arrive at", fontSize = 13.sp, color = Aez.colors.text2)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (name in listOf<String?>(null) + places.keys) {
                    xyz.aezdek.hush.ui.components.Chip(name ?: "Nowhere", selected = arriveAt == name) { arriveAt = name }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { AezField(newPlace, { newPlace = it.take(40) }, "Save where I am now as… (Home)") }
                AezButton("Save", {
                    val name = newPlace.trim()
                    scope.launch {
                        val here = if (hasLocation(context)) currentLocation(context) else null
                        if (here == null) Graph.toast("Couldn't find where you are. Allow location, then try again.", error = true)
                        else {
                            Graph.prefs.places = Graph.prefs.places + (name to (here.latitude to here.longitude))
                            places = Graph.prefs.places
                            arriveAt = name
                            newPlace = ""
                        }
                    }
                }, small = true, enabled = newPlace.isNotBlank())
            }
            AezButton(if (busy) "Finding you…" else "Share", {
                if (hasLocation(context)) share()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }, Modifier.fillMaxWidth(), enabled = !busy, icon = Icons.Outlined.Navigation)
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * While any of your own live locations is running and Hush is open, follows the phone's position and replaces the
 * message with each real move (at most every 20 seconds, or 25 metres). Put once at the root of the app.
 */
@SuppressLint("MissingPermission")
@Composable
fun LocationSharer() {
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me?.id ?: return
    val now = remember(index) { System.currentTimeMillis() }
    val active = state.threads.keys.flatMap { index.liveLocations(it, now) }.filter { it.dto.authorId == me }
    val key = active.joinToString(",") { it.id }
    var expired by remember { mutableLongStateOf(0L) }
    // Notice when the last one runs out, so following stops.
    LaunchedEffect(key) {
        val soonest = active.mapNotNull { it.payloadAs<MessagePayload>()?.location?.until }.minOrNull() ?: return@LaunchedEffect
        delay((soonest - System.currentTimeMillis()).coerceAtLeast(1000))
        expired = System.currentTimeMillis()
    }
    DisposableEffect(key, expired) {
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = manager?.let(::provider)
        if (key.isEmpty() || manager == null || provider == null || !hasLocation(context)) return@DisposableEffect onDispose {}
        val ids = key.split(',').toSet()
        var last: Location? = null
        var lastAt = 0L
        val listener = LocationListener { location ->
            // "Say when I arrive": the chat is told once, the first time this phone is within reach of the place.
            val watches = Graph.prefs.arrivals
            val reached = watches.filter { xyz.aezdek.hush.client.Places.distance(location.latitude, location.longitude, it.lat, it.lng) <= xyz.aezdek.hush.client.Places.ARRIVED_WITHIN }
            if (reached.isNotEmpty()) {
                Graph.prefs.arrivals = watches - reached.toSet()
                for (arrival in reached) Graph.launch { Graph.client.composer.sendArrived(arrival.threadId, arrival.place) }
            }
            val moved = last?.let { distanceMetres(it.latitude, it.longitude, location.latitude, location.longitude) } ?: Double.MAX_VALUE
            val since = System.currentTimeMillis() - lastAt
            if (last != null && since < 20_000 && moved < 25.0) return@LocationListener
            if (last != null && moved < 10.0 && since < 5 * 60_000) return@LocationListener
            last = location
            lastAt = System.currentTimeMillis()
            Graph.launch {
                val current = Graph.client.index.value
                for (message in current.messages.values.flatten().filter { it.id in ids }) {
                    Graph.client.composer.moveLocation(message, location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null)
                }
            }
        }
        runCatching { manager.requestLocationUpdates(provider, 15_000L, 10f, listener, Looper.getMainLooper()) }
        onDispose { runCatching { manager.removeUpdates(listener) } }
    }
}

// --------------------------------------------------------------------------------------------------------- the menu

internal enum class ChatExtra { FILE, PLAN, LOCATION, LIST, DAILY, COUNTDOWN, NUDGE, LATER, POLL }

/** The composer's "+": a plan, live location, and in groups a poll. */
@Composable
internal fun ExtrasSheet(group: Boolean, onDismiss: () -> Unit, onPick: (ChatExtra) -> Unit) {
    val items = buildList {
        add(Triple(ChatExtra.FILE, "File" to "Any file, up to 250 MB, sent as it is", Icons.Outlined.AttachFile))
        add(Triple(ChatExtra.PLAN, "Plan" to "A time and place, and who's going", Icons.Outlined.CalendarMonth))
        add(Triple(ChatExtra.LOCATION, "Live location" to "Where you are, for a set time", Icons.Outlined.Navigation))
        add(Triple(ChatExtra.LIST, "Shared list" to "Everyone adds items and ticks them off", Icons.Outlined.Checklist))
        add(Triple(ChatExtra.DAILY, "Question of the day" to "Answers show once everyone has answered", Icons.Outlined.QuestionAnswer))
        add(Triple(ChatExtra.COUNTDOWN, "Countdown" to "Days to a date, at the top of the chat", Icons.Outlined.Schedule))
        add(Triple(ChatExtra.NUDGE, "Nudge" to "No words: their phone just buzzes", Icons.Outlined.WavingHand))
        add(Triple(ChatExtra.LATER, "Send later" to "A message that arrives at a time you pick", Icons.Outlined.Schedule))
        if (group) add(Triple(ChatExtra.POLL, "Poll" to "Let everyone vote", Icons.Outlined.Poll))
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Aez.colors.elev1) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 12.dp)) {
            for ((extra, words, icon) in items) {
                Row(
                    Modifier.fillMaxWidth().clickable { onDismiss(); onPick(extra) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(Aez.colors.elev2), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = Aez.colors.text, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(words.first, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Aez.colors.text)
                        Text(words.second, fontSize = 13.sp, color = Aez.colors.text2)
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------------------------------------- albums

/** A shared album as a gallery: everyone's photos and videos, newest first. View-once photos stay out of it. */
@Composable
internal fun AlbumGallery(threadId: String, index: Index, state: AppState, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val items = remember(index, threadId) { index.albumMedia(threadId).filter { (m, _) -> m.dto.audience != Audience.ONCE } }
    var open by remember { mutableStateOf<Int?>(null) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (items.isEmpty()) "No photos yet" else "${items.size} photos and videos", fontSize = 13.sp, color = Aez.colors.text2, modifier = Modifier.weight(1f))
            AezButton("Add", onAdd, small = true, icon = Icons.Outlined.AddPhotoAlternate)
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, tint = Aez.colors.text3, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(10.dp))
                Text("Add the first photos. Everyone in the album can add theirs, and only the people in it can see them.",
                    fontSize = 14.sp, color = Aez.colors.text2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(items.size) { i ->
                    val ref = items[i].second
                    Box(Modifier.aspectRatio(1f).clickable { open = i }) {
                        MediaImage(if (ref.mime.startsWith("video/")) ref.poster ?: ref else ref, Modifier.fillMaxSize(), maxEdge = 480)
                        if (ref.mime.startsWith("video/")) Text("▶", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                    }
                }
            }
        }
    }
    open?.let { i ->
        val (message, ref) = items.getOrNull(i) ?: return@let
        androidx.compose.ui.window.Dialog({ open = null }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Color.Black).clickable { open = null }, verticalArrangement = Arrangement.Center) {
                MediaImage(if (ref.mime.startsWith("video/")) ref.poster ?: ref else ref, Modifier.fillMaxWidth().weight(1f),
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit)
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(message.dto.authorId, 28.dp, index, state.users[message.dto.authorId]?.username)
                    Spacer(Modifier.width(10.dp))
                    val who = if (message.dto.authorId == state.me?.id) "You" else index.nameIn(threadId, message.dto.authorId, state.users)
                    Text("$who · ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(message.dto.createdAt))}", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}
