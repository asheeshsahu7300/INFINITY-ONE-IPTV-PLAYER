package com.infinity.one

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ExternalPlayerApp(
    val pkg: String,
    val name: String,
    val icon: Drawable?
)

data class ExternalPlayerRequest(
    val url: String,
    val title: String,
    val apps: List<ExternalPlayerApp>
)

object ExternalPlayer {
    var activeRequest by mutableStateOf<ExternalPlayerRequest?>(null)

    private val KNOWN_PLAYERS = listOf(
        "org.videolan.vlc" to "VLC",
        "com.mxtech.videoplayer.television" to "MX Player TV",
        "com.mxtech.videoplayer.television.pro" to "MX Player Pro TV",
        "com.mxtech.videoplayer.ad" to "MX Player",
        "com.mxtech.videoplayer.pro" to "MX Player Pro",
        "com.brouken.player" to "Just Player",
        "org.courville.nova" to "Nova Video Player",
        "net.gtvbox.videoplayer" to "Vimu Media Player",
        "org.xbmc.kodi" to "Kodi",
        "is.xyz.mpv" to "mpv"
    )

    private val EXCLUDED_KEYWORDS = listOf(
        "gallery", "photo", "camera", "iptv", "ventox", "tivimate",
        "browser", "chrome", "vending", "youtube", "netflix", "amazon",
        "settings", "system", "file", "download", "cloudstream", "stub", "framework",
        "packagestubs"
    )

    fun getInstalled(ctx: Context): List<ExternalPlayerApp> {
        val pm = ctx.packageManager
        val found = LinkedHashMap<String, ExternalPlayerApp>()
        val knownPkgs = KNOWN_PLAYERS.map { it.first }.toSet()

        // 1. Direct package lookup for all known top TV & mobile players
        for ((pkg, defaultName) in KNOWN_PLAYERS) {
            runCatching {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString().ifBlank { defaultName }
                val icon = pm.getApplicationIcon(appInfo)
                found[pkg] = ExternalPlayerApp(pkg, label, icon)
            }
        }

        // 2. Generic intent resolver for any other video player on the system
        val testUris = listOf(
            Uri.parse("http://example.com/stream.m3u8") to "application/x-mpegURL",
            Uri.parse("http://example.com/video.mp4") to "video/*",
            Uri.parse("http://example.com/video.mkv") to "video/*"
        )
        for ((uri, mime) in testUris) {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            val matches = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            for (res in matches) {
                val p = res.activityInfo?.packageName?.lowercase() ?: continue
                if (p == ctx.packageName.lowercase()) continue
                // Strictly exclude system gallery/stubs and anything not a real third-party player
                if (p.startsWith("com.android.") && p !in knownPkgs) continue
                if (p.startsWith("com.google.android.") && p !in knownPkgs) continue
                val label = res.loadLabel(pm).toString()
                if (label.isBlank() || label.equals("none", ignoreCase = true)) continue
                if (EXCLUDED_KEYWORDS.any { p.contains(it) || label.lowercase().contains(it) }) continue
                val originalPkg = res.activityInfo.packageName
                if (originalPkg !in found) {
                    val finalLabel = label.ifBlank { originalPkg }
                    val icon = res.loadIcon(pm)
                    found[originalPkg] = ExternalPlayerApp(originalPkg, finalLabel, icon)
                }
            }
        }

        return found.values.toList()
    }

    private fun launchResolved(ctx: Context, pkg: String?, url: String, title: String): Boolean {
        val uri = Uri.parse(url)
        val mime = if (url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)) {
            "application/x-mpegURL"
        } else {
            "video/*"
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            putExtra("title", title)
            putExtra("android.intent.extra.TITLE", title)
            putExtra("secure_uri", true)
            // Essential flags for Android TV singleTask stability
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (!pkg.isNullOrBlank()) {
                setPackage(pkg)
            }
        }

        return runCatching {
            ctx.startActivity(intent)
            true
        }.getOrElse {
            // Fallback without MIME type if strict type failed
            runCatching {
                val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    putExtra("title", title)
                    putExtra("android.intent.extra.TITLE", title)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    if (!pkg.isNullOrBlank()) setPackage(pkg)
                }
                ctx.startActivity(fallbackIntent)
                true
            }.getOrDefault(false)
        }
    }

    fun launch(ctx: Context, pkg: String?, url: String, title: String): Boolean {
        if (StalkerLogic.isItem(url)) {
            Toast.makeText(ctx, Lang.tr("Stream-Adresse wird vorbereitet …"), Toast.LENGTH_SHORT).show()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val resolved = StalkerApi.resolve(ctx, url)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (resolved != null) {
                        launchResolved(ctx, pkg, resolved, title)
                    } else {
                        val reason = StalkerApi.lastDetail.ifBlank { Lang.tr("Senderadresse konnte nicht abgerufen werden") }
                        Toast.makeText(ctx, reason, Toast.LENGTH_LONG).show()
                    }
                }
            }
            return true
        }
        return launchResolved(ctx, pkg, url, title)
    }

    fun open(ctx: Context, url: String, title: String) {
        val installed = getInstalled(ctx)
        if (installed.isEmpty()) {
            Toast.makeText(ctx, Lang.tr("Kein externer Player installiert (z. B. VLC)"), Toast.LENGTH_LONG).show()
            return
        }

        val preferred = AppSettings.externalPlayerPkg
        val preferredApp = installed.firstOrNull { it.pkg == preferred }
        if (preferredApp != null) {
            launch(ctx, preferredApp.pkg, url, title)
            return
        }

        if (installed.size == 1) {
            launch(ctx, installed[0].pkg, url, title)
            return
        }

        // Multiple players available -> surface in-app TV selection dialog
        activeRequest = ExternalPlayerRequest(url, title, installed)
    }

    fun dismiss() {
        activeRequest = null
    }
}

@Composable
fun ExternalPlayerHost() {
    val req = ExternalPlayer.activeRequest ?: return
    val ctx = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    Dialog(
        onDismissRequest = { ExternalPlayer.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler { ExternalPlayer.dismiss() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xE6000000)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .width(400.dp)
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF000000))
                    .border(1.dp, Color(0xFF26282E), RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        Lang.tr("Externer Player"),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (req.title.isNotBlank()) {
                        Text(
                            req.title,
                            color = Color(0xFF90929A),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    items(req.apps, key = { it.pkg }) { app ->
                        var focused by remember { mutableStateOf(false) }
                        val scale by animateFloatAsState(
                            if (focused) 1.02f else 1f,
                            animationSpec = tween(100),
                            label = "extPlayFocus"
                        )
                        val shape = RoundedCornerShape(10.dp)

                        Row(
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayer { scaleX = scale; scaleY = scale }
                                .clip(shape)
                                .background(if (focused) Color(0xFF242732) else Color(0xFF101114))
                                .border(
                                    if (focused) 1.5.dp else 1.dp,
                                    if (focused) Color.White else Color(0xFF22242A),
                                    shape
                                )
                                .clickable {
                                    ExternalPlayer.dismiss()
                                    ExternalPlayer.launch(ctx, app.pkg, req.url, req.title)
                                }
                                .onFocusChanged { focused = it.isFocused }
                                .then(if (app.pkg == req.apps.first().pkg) Modifier.focusRequester(focusRequester) else Modifier)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            val bitmap = remember(app.pkg) {
                                runCatching { app.icon?.toBitmap(64, 64)?.asImageBitmap() }.getOrNull()
                            }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = app.name,
                                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Box(
                                    Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AppIcon(AIcon.External, tint = Color.White, size = 18.dp)
                                }
                            }

                            Column(Modifier.weight(1f)) {
                                Text(
                                    app.name,
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    app.pkg,
                                    color = Color(0xFF82848E),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Lang.tr("Zurück zum Abbrechen"),
                        color = Color(0xFF6B6E7B),
                        fontSize = 12.sp
                    )
                    var cancelFocused by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (cancelFocused) Color.White else Color(0xFF1E2026))
                            .border(1.dp, if (cancelFocused) Color.White else Color(0xFF33353E), RoundedCornerShape(8.dp))
                            .clickable { ExternalPlayer.dismiss() }
                            .onFocusChanged { cancelFocused = it.isFocused }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            Lang.tr("Abbrechen"),
                            color = if (cancelFocused) Color.Black else Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focusRequester.requestFocus() }
    }
}
