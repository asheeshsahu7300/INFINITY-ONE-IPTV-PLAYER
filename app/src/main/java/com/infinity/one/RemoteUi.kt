package com.infinity.one

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/** State of the "Phone search" display (button at the bottom of the sidebar, card with QR code above everything). */
object RemoteUi {
    var show by mutableStateOf(false)
}

/**
 * Card with a large QR code. The server keeps running when the card is closed (the phone stays connected).
 */
@Composable
fun RemoteOverlay(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    var ipRefresh by remember { mutableIntStateOf(0) }
    val ip = remember(ipRefresh) { PhoneImport.localIp(ctx) }
    val server = remember { RemoteHub.start(ctx) }
    var showIpDialog by remember { mutableStateOf(false) }
    var tempIp by remember(showIpDialog) { mutableStateOf(PhoneImport.getCustomIp(ctx) ?: ip ?: "") }
    val detectedList = remember(showIpDialog) { PhoneImport.detectedIps(ctx) }

    fun close() { RemoteUi.show = false }
    BackHandler { if (showIpDialog) showIpDialog = false else close() }
    LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
    Box(
        modifier.fillMaxSize().background(Color(0xCC000000))
            // Back closes the card with ONE press, whatever has the focus (the BackHandler alone needed two presses on the Fire TV)
            .onPreviewKeyEvent { ev ->
                if (ev.key == Key.Back) {
                    if (ev.type == KeyEventType.KeyDown) {
                        if (showIpDialog) showIpDialog = false else close()
                    }
                    true
                } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xFF0F1730)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Mit dem Handy suchen", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            if (ip == null) Text("Das Gerät ist nicht mit einem WLAN verbunden.", color = Color(0xFFFFB86B), fontSize = 17.sp)
            else if (server == null) Text("Der Server konnte nicht gestartet werden.", color = Color(0xFFFFB86B), fontSize = 17.sp)
            else {
                val addr = "http://$ip:${server.port}/r?code=${server.code}"
                QrImage(addr, 280.dp)
                Text("Mit der Handy-Kamera scannen", color = Muted, fontSize = 16.sp)
                Text("http://$ip:${server.port}/r", color = Accent2, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Code", color = Muted, fontSize = 16.sp)
                    Text(server.code, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                }
                Text(
                    "Suche und starte Filme, Serien und Sender am Handy. Handy und Fernseher müssen im selben WLAN sein.",
                    color = Muted, fontSize = 14.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton("Schließen", Modifier.focusRequester(focus), light = true) { close() }
                PillButton("IP anpassen", light = false, icon = AIcon.Edit) { showIpDialog = true }
            }
        }
    }

    if (showIpDialog) {
        Dialog(
            onDismissRequest = { showIpDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(100); runCatching { dialogFocus.requestFocus() } }
            Box(
                Modifier.fillMaxSize().background(Color(0xD9000000)).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showIpDialog = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.widthIn(max = 580.dp)
                        .clip(RoundedCornerShape(DS.RadiusM))
                        .background(Color(0xFF0F1730))
                        .padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("WLAN-Adresse (IP)", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Lokale IP-Adresse für Handys im selben WLAN (z. B. am PC im Emulator: 192.168.1.3)",
                        color = Muted, fontSize = 14.sp, lineHeight = 20.sp
                    )

                    Field(
                        value = tempIp,
                        onChange = { tempIp = it },
                        hint = "z. B. 192.168.1.3",
                        modifier = Modifier.fillMaxWidth().focusRequester(dialogFocus)
                    )

                    // Quick presets
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PillButton("192.168.1.3", light = tempIp == "192.168.1.3", raw = true) {
                            tempIp = "192.168.1.3"
                        }
                        if (detectedList.isNotEmpty()) {
                            detectedList.filter { it != "192.168.1.3" }.take(2).forEach { det ->
                                PillButton(det, light = tempIp == det, raw = true) {
                                    tempIp = det
                                }
                            }
                        }
                        PillButton("Auto-Erkennung", light = tempIp.isBlank()) {
                            tempIp = ""
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            PillButton("Speichern", Modifier.fillMaxWidth(), light = true) {
                                PhoneImport.setCustomIp(ctx, tempIp.trim().ifEmpty { null })
                                ipRefresh++
                                showIpDialog = false
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            PillButton("Abbrechen", Modifier.fillMaxWidth()) {
                                showIpDialog = false
                            }
                        }
                    }
                }
            }
        }
    }
}
