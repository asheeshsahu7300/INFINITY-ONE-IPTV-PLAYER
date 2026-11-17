package com.infinity.one

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Search inside the TV guide: channel names plus show titles from the loaded XMLTV data (no network). */
object GuideSearchLogic {
    /** [now]: for a channel hit, the show running on that channel right now (null if the guide has none). */
    class Hit(val channel: Item, val entry: EpgEntry?, val now: EpgEntry? = null)

    fun search(channels: List<Item>, query: String, nowSec: Long, limit: Int = 60, maxChannels: Int = 150): List<Hit> {
        val q = ChannelSearch.norm(query)
        if (q.isEmpty()) return emptyList()
        val words = q.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<Hit>()
        for (c in ChannelSearch.filter(channels, query).take(maxChannels)) {
            out.add(Hit(c, null, Xmltv.forChannel(c.tvgId).firstOrNull { it.start <= nowSec && nowSec < it.end }))
        }
        val shows = ArrayList<Hit>()
        for (c in channels) {
            for (e in Xmltv.forChannel(c.tvgId)) {
                if (e.end <= nowSec - 3600) continue
                val t = ChannelSearch.norm(e.title)
                if (words.all { it in t }) shows.add(Hit(c, e))
            }
            if (shows.size > 600) break
        }
        shows.sortBy { it.entry!!.start }
        out.addAll(shows.take(limit))
        return out
    }
}

@Composable
fun GuideSearchDialog(channels: List<Item>, onPick: (Item) -> Unit, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val kbFirst = remember { FocusRequester() }
    val isPhone = DeviceMode.isPhonePortrait()

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { (if (isPhone || AppSettings.systemKeyboard) fr else kbFirst).requestFocus() }
    }

    val hits by produceState(emptyList<GuideSearchLogic.Hit>(), q) {
        if (q.isBlank()) { value = emptyList(); return@produceState }
        delay(200)
        value = withContext(Dispatchers.Default) { GuideSearchLogic.search(channels, q, System.currentTimeMillis() / 1000) }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = !isPhone)) {
        if (isPhone) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF000000))
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Top header with Back button, search field, and clear button
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0x28FFFFFF))
                            .clickable { onClose() },
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(AIcon.Left, Color.White, size = 18.dp)
                    }
                    Box(Modifier.weight(1f)) {
                        Field(
                            q,
                            { q = it.take(40) },
                            "Search TV Guide...",
                            inner = Modifier.focusRequester(fr),
                            autoOpen = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (q.isNotEmpty()) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0x28FFFFFF))
                                .clickable { q = "" },
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(AIcon.Close, Color.White, size = 16.dp)
                        }
                    }
                }

                if (q.isNotBlank() && hits.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No channels or shows found", color = Muted, fontSize = 15.sp)
                    }
                } else if (q.isBlank()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Type a channel or program title", color = Muted, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        itemsIndexed(hits) { _, h ->
                            val e = h.entry
                            val nowShow by produceState(h.now, h.channel.id) {
                                if (e == null && value == null) value = runCatching { Epg.current(Epg.now(h.channel)) }.getOrNull()
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x18FFFFFF))
                                    .clickable { onPick(h.channel) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                    if (h.channel.logo != null) {
                                        AsyncImage(h.channel.logo, h.channel.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                    } else {
                                        Text(
                                            raw = true,
                                            text = h.channel.title.take(3).uppercase(),
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        raw = true,
                                        text = e?.title ?: h.channel.title,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        raw = true,
                                        text = if (e == null) {
                                            h.channel.group + (nowShow?.let { " · " + it.title + " (" + hhmm(it.start) + "–" + hhmm(it.end) + ")" } ?: "")
                                        } else {
                                            h.channel.title + " · " + dayLabel(e.start) + " " + hhmm(e.start) + "–" + hhmm(e.end)
                                        },
                                        color = Color(0xFFA0A0A8),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                AppIcon(AIcon.Play, Color.White, size = 18.dp)
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier.width(1180.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Color(0xFF000000)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Search in TV Guide", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!AppSettings.systemKeyboard) SearchKeyboard(
                        Modifier.width(430.dp),
                        onKey = { q = (q + it).take(40) },
                        onSpace = { if (q.isNotEmpty() && !q.endsWith(" ")) q += " " },
                        onDelete = { q = q.dropLast(1) },
                        onClear = { q = "" },
                        onPhone = null, onVoice = null,
                        firstKey = kbFirst, onFocusChange = {}, onRight = { false }
                    )
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(q, { q = it.take(40) }, "Channel or show", inner = Modifier.focusRequester(fr), autoOpen = false)
                        if (q.isNotBlank() && hits.isEmpty()) Text("No matches found", color = Muted, fontSize = 15.sp)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(hits) { _, h ->
                                var f by remember { mutableStateOf(false) }
                                val e = h.entry
                                val nowShow by produceState(h.now, h.channel.id) {
                                    if (e == null && value == null) value = runCatching { Epg.current(Epg.now(h.channel)) }.getOrNull()
                                }
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM))
                                        .background(if (f) Color(0x33FFFFFF) else Color(0x0FFFFFFF))
                                        .onFocusChanged { f = it.isFocused }
                                        .clickable { onPick(h.channel) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                ) {
                                    Text(raw = true, text = e?.title ?: h.channel.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        raw = true,
                                        text = if (e == null) h.channel.group + (nowShow?.let { " · Now: " + it.title + " " + hhmm(it.start) + " - " + hhmm(it.end) } ?: "") else h.channel.title + " · " + dayLabel(e.start) + " " + hhmm(e.start) + " - " + hhmm(e.end),
                                        color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
