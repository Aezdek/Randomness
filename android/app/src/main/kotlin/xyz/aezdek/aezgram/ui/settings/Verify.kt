package xyz.aezdek.aezgram.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import xyz.aezdek.aezgram.Graph
import xyz.aezdek.aezgram.crypto.B64
import xyz.aezdek.aezgram.crypto.SafetyNumber
import xyz.aezdek.aezgram.ui.components.AezButton
import xyz.aezdek.aezgram.ui.components.Avatar
import xyz.aezdek.aezgram.ui.components.ButtonStyle
import xyz.aezdek.aezgram.ui.components.TopBar
import xyz.aezdek.aezgram.ui.components.VerifiedBadge
import xyz.aezdek.aezgram.ui.theme.Aez

/** A QR code drawn module by module, so it stays sharp at any size. Always dark on white, which every scanner reads. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    Canvas(modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp)).background(Color.White).padding(6.dp)) {
        val cells = matrix.width
        val cell = size.minDimension / cells
        for (y in 0 until cells) for (x in 0 until cells) {
            if (matrix[x, y]) drawRect(Color(0xFF0B0B0E), Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

/** Safety numbers and QR verification: proof the server hasn't swapped anyone's keys. */
@Composable
fun Verify(userId: String) {
    val context = LocalContext.current
    val state by Graph.client.state.collectAsState()
    val index by Graph.client.index.collectAsState()
    val me = state.me ?: return
    val keys = state.keys ?: return
    val user = state.users[userId]
    Column(Modifier.fillMaxSize()) {
        TopBar("Security code")
        if (user == null) return@Column
        val pin = state.pins[userId]
        val verified = pin?.verified == true
        val changed = pin?.changedFrom != null
        val digits = remember(user.signPk) { SafetyNumber.pair(keys.signPk, B64.decode(user.signPk)) }

        fun onScanned(text: String) {
            val (scannedId, signPk) = SafetyNumber.parseQr(text) ?: return Graph.toast("That isn't an AezGram code", error = true)
            when {
                scannedId != userId -> Graph.toast("That's someone else's code", error = true)
                B64.encode(signPk) != user.signPk -> Graph.toast("Keys don't match! Don't trust this account until you find out why.", error = true)
                else -> {
                    Graph.client.engine.acknowledgeKeyChange(userId, verified = true)
                    Graph.toast("${user.username} is verified")
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(userId, 84.dp, index, user.username)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(user.username, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Aez.colors.text)
                if (verified) { Spacer(Modifier.width(6.dp)); VerifiedBadge(18.dp) }
            }
            Text(
                when { verified -> "Verified"; changed -> "Their security code changed"; else -> "Not verified yet" },
                color = when { verified -> Aez.colors.success; changed -> Aez.colors.danger; else -> Aez.colors.text2 },
            )
            if (changed) Warning("${user.username}'s keys changed. That happens when they reset their keys. Compare codes in person before trusting new messages.")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Aez.colors.elev1).padding(vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DigitGrid(digits, columns = 3, fontSize = 21)
            }
            Text("Compare these numbers with ${user.username} in person or on a call, or scan each other's code. If they match, nobody is in the middle.",
                color = Aez.colors.text2, textAlign = TextAlign.Center, fontSize = 14.sp)
            QrCode(SafetyNumber.qrPayload(me.id, keys.signPk), Modifier.size(200.dp))
            Text("Your code", fontSize = 12.sp, color = Aez.colors.text3)
            AezButton("Scan their code", {
                GmsBarcodeScanning.getClient(context).startScan()
                    .addOnSuccessListener { barcode -> barcode.rawValue?.let(::onScanned) }
                    .addOnFailureListener { Graph.toast("The scanner isn't available on this phone", error = true) }
            }, Modifier.fillMaxWidth(), icon = Icons.Outlined.QrCodeScanner)
            AezButton(
                when { verified -> "Clear verification"; changed -> "Accept new code"; else -> "Mark as verified" },
                { Graph.client.engine.acknowledgeKeyChange(userId, verified = !verified) },
                Modifier.fillMaxWidth(), style = ButtonStyle.SECONDARY, icon = Icons.Outlined.VerifiedUser,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}
