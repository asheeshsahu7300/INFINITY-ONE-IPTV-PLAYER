package com.infinity.one

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.InputStream
import kotlin.math.abs

object SpeedTestManager {
    enum class State { IDLE, PING, DOWNLOAD, DONE, ERROR }

    var state by mutableStateOf(State.IDLE)
        private set
    var pingMs by mutableIntStateOf(0)
        private set
    var jitterMs by mutableIntStateOf(0)
        private set
    var speedMbps by mutableFloatStateOf(0f)
        private set
    var liveSpeedMbps by mutableFloatStateOf(0f)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var errorMsg by mutableStateOf<String?>(null)
        private set

    fun reset() {
        state = State.IDLE
        pingMs = 0
        jitterMs = 0
        speedMbps = 0f
        liveSpeedMbps = 0f
        progress = 0f
        errorMsg = null
    }

    suspend fun runTest() = withContext(Dispatchers.IO) {
        if (state == State.PING || state == State.DOWNLOAD) return@withContext
        state = State.PING
        errorMsg = null
        progress = 0.05f
        pingMs = 0
        jitterMs = 0
        speedMbps = 0f
        liveSpeedMbps = 0f

        val client = Net.withTimeouts(5, 12)
        val pings = ArrayList<Int>()

        // Step 1: Ping / Latency & Jitter test
        val pingUrls = listOf(
            "https://speed.cloudflare.com/__down?bytes=0",
            "https://speed.cloudflare.com/__down?bytes=0",
            "https://speed.cloudflare.com/__down?bytes=0",
            "https://speed.cloudflare.com/__down?bytes=0"
        )

        for ((idx, url) in pingUrls.withIndex()) {
            if (!isActive) return@withContext
            try {
                val t0 = System.nanoTime()
                val req = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "InfinityOne/SpeedTest")
                    .build()
                client.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val durMs = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                        pings.add(durMs)
                    }
                }
            } catch (e: Exception) {
                // Ignore single ping error
            }
            progress = 0.05f + (idx + 1) * 0.05f
        }

        if (pings.isNotEmpty()) {
            pingMs = pings.minOrNull() ?: pings.first()
            if (pings.size > 1) {
                val diffs = (1 until pings.size).map { abs(pings[it] - pings[it - 1]) }
                jitterMs = diffs.average().toInt()
            }
        }

        // Step 2: Download Speed test
        state = State.DOWNLOAD
        val downloadUrls = listOf(
            "https://speed.cloudflare.com/__down?bytes=25000000",
            "https://speed.cloudflare.com/__down?bytes=15000000"
        )

        var downloadSuccess = false
        for (dlUrl in downloadUrls) {
            if (!isActive) return@withContext
            try {
                val req = Request.Builder()
                    .url(dlUrl)
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", "InfinityOne/SpeedTest")
                    .build()

                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val body = res.body ?: return@use
                    val totalTarget = (body.contentLength().takeIf { it > 0 } ?: 25_000_000L).toFloat()
                    val input: InputStream = body.byteStream()
                    val buf = ByteArray(32 * 1024)
                    var bytesReceived = 0L
                    val startTime = System.nanoTime()
                    var lastUiUpdate = startTime
                    val speedSamples = ArrayList<Float>()

                    while (isActive) {
                        val n = input.read(buf)
                        if (n == -1) break
                        bytesReceived += n
                        val now = System.nanoTime()

                        if (now - lastUiUpdate >= 80_000_000L) { // update every 80ms
                            val elapsedSec = (now - startTime) / 1_000_000_000f
                            if (elapsedSec > 0.08f) {
                                val currentMbps = (bytesReceived * 8f) / (elapsedSec * 1_000_000f)
                                liveSpeedMbps = currentMbps
                                progress = (0.25f + (bytesReceived / totalTarget) * 0.75f).coerceIn(0.25f, 0.99f)
                                if (elapsedSec > 0.4f) {
                                    speedSamples.add(currentMbps)
                                }
                            }
                            lastUiUpdate = now
                        }

                        // Max test window: 7 seconds to keep experience snappy
                        if (now - startTime > 7_000_000_000L) break
                    }

                    val totalElapsedSec = (System.nanoTime() - startTime) / 1_000_000_000f
                    if (bytesReceived > 200_000L && totalElapsedSec > 0.2f) {
                        val finalSpeed = if (speedSamples.size >= 5) {
                            val sorted = speedSamples.sorted()
                            val trimmed = sorted.drop((sorted.size * 0.2f).toInt())
                            trimmed.average().toFloat()
                        } else {
                            (bytesReceived * 8f) / (totalElapsedSec * 1_000_000f)
                        }
                        speedMbps = finalSpeed
                        liveSpeedMbps = finalSpeed
                        downloadSuccess = true
                    }
                }
                if (downloadSuccess) break
            } catch (e: Exception) {
                // Try fallback URL
            }
        }

        progress = 1f
        if (downloadSuccess) {
            state = State.DONE
        } else {
            state = State.ERROR
            errorMsg = "Verbindungsfehler beim Speedtest"
        }
    }
}

@Composable
fun SpeedTestCard(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val st = SpeedTestManager
    val state = st.state
    val isTesting = state == SpeedTestManager.State.PING || state == SpeedTestManager.State.DOWNLOAD
    val progAnim by animateFloatAsState(st.progress, label = "speedProg")

    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0xFF14141C))
            .border(1.dp, if (isTesting) Accent1.copy(alpha = 0.5f) else Color(0x22FFFFFF), shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header Row: Icon + Title + Status Pill
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Accent1.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    AppIcon(AIcon.Pulse, Accent1, size = 20.dp)
                }
                Column {
                    Text(Lang.tr("Geschwindigkeitstest"), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        when (state) {
                            SpeedTestManager.State.PING -> Lang.tr("Ping wird gemessen …")
                            SpeedTestManager.State.DOWNLOAD -> Lang.tr("Download wird gemessen …")
                            SpeedTestManager.State.DONE -> Lang.tr("Test abgeschlossen")
                            SpeedTestManager.State.ERROR -> st.errorMsg ?: Lang.tr("Fehler")
                            else -> Lang.tr("Verbindungsgeschwindigkeit für Streaming prüfen")
                        },
                        color = Muted,
                        fontSize = 13.sp
                    )
                }
            }

            if (state == SpeedTestManager.State.DONE) {
                val ratingBadge = when {
                    st.speedMbps >= 50f -> ("4K UHD Bereit" to Color(0xFF00E676))
                    st.speedMbps >= 25f -> ("Full HD & 4K" to Color(0xFF00B0FF))
                    st.speedMbps >= 10f -> ("Full HD 1080p" to Color(0xFFFFD700))
                    else -> ("SD / 720p HD" to Color(0xFFFF9100))
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ratingBadge.second.copy(alpha = 0.15f))
                        .border(1.dp, ratingBadge.second.copy(alpha = 0.4f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        ratingBadge.first,
                        color = ratingBadge.second,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Stats Display Row: Download (Large) | Ping | Jitter
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Download Speed
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    val displaySpeed = if (state == SpeedTestManager.State.DOWNLOAD) st.liveSpeedMbps
                        else if (state == SpeedTestManager.State.DONE) st.speedMbps
                        else 0f
                    Text(
                        if (displaySpeed > 0f) "%.1f".format(displaySpeed) else "--",
                        color = Color.White,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Mbps",
                        color = Accent1,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                Text(Lang.tr("Download"), color = Muted, fontSize = 13.sp)
            }

            // Divider
            Box(Modifier.width(1.dp).height(40.dp).background(Color(0x22FFFFFF)))

            // Ping
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (st.pingMs > 0) "${st.pingMs}" else "--",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("ms", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text("Ping", color = Muted, fontSize = 13.sp)
            }

            // Divider
            Box(Modifier.width(1.dp).height(40.dp).background(Color(0x22FFFFFF)))

            // Jitter
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (st.jitterMs > 0) "${st.jitterMs}" else "--",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("ms", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 3.dp))
                }
                Text("Jitter", color = Muted, fontSize = 13.sp)
            }
        }

        // Progress bar (visible during test)
        if (isTesting) {
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))
            ) {
                Box(
                    Modifier.fillMaxWidth(progAnim).fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(Accent1, Accent2)))
                )
            }
        }

        // Action Button Row
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val buttonText = when (state) {
                SpeedTestManager.State.PING -> Lang.tr("Ping wird gemessen …")
                SpeedTestManager.State.DOWNLOAD -> Lang.tr("Download wird gemessen …")
                SpeedTestManager.State.DONE -> Lang.tr("Speedtest wiederholen")
                else -> Lang.tr("Speedtest starten")
            }
            PillButton(
                text = buttonText,
                icon = if (isTesting) AIcon.Pulse else AIcon.Play,
                light = state == SpeedTestManager.State.DONE || state == SpeedTestManager.State.IDLE
            ) {
                if (!isTesting) {
                    scope.launch { st.runTest() }
                }
            }
            if (state == SpeedTestManager.State.DONE) {
                val recommendation = when {
                    st.speedMbps >= 50f -> "Hervorragende Verbindung: Flüssiges 4K UHD & HDR Streaming ohne Pufferung."
                    st.speedMbps >= 25f -> "Sehr gute Verbindung: Bereit für 4K und hochauflösendes Full HD Streaming."
                    st.speedMbps >= 10f -> "Gute Verbindung: Ausreichend für Full HD 1080p Streaming."
                    else -> "Niedrige Bandbreite: Bei Full HD/4K Streams kann es zu Pufferpausen kommen."
                }
                Text(Lang.tr(recommendation), color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}
