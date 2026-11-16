package com.infinity.one

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Modern portrait mobile hero card, inspired by mobile streaming apps (JioHotstar / Netflix mobile):
 * - Large rounded vertical card (approx. 450dp tall)
 * - Top ranking badge ("#1 in Today's Highlights")
 * - Bottom gradient scrim with title, metadata, and quick action buttons
 * - "Watch Now" primary pill button + Watchlist "+" icon button
 */
@Composable
fun MobileHeroCard(
    featured: List<Item>,
    onOpen: (Item) -> Unit,
    onPlay: (Item) -> Unit,
    modifier: Modifier = Modifier
) {
    if (featured.isEmpty()) return
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }

    LaunchedEffect(featured.size, General.heroSec) {
        while (featured.size > 1 && General.heroSec > 0) {
            delay(General.heroSec * 1000L)
            idx = (idx + 1) % featured.size
        }
    }

    val item = featured[idx.coerceIn(0, featured.lastIndex)]
    val cardShape = RoundedCornerShape(22.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(440.dp)
            .shadow(16.dp, cardShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(cardShape)
            .background(Color(0xFF14131D))
            .clickable { onOpen(item) }
    ) {
        Crossfade(targetState = item, animationSpec = tween(700), label = "mobileHeroCrossfade") { currentItem ->
            val meta = rememberFullMeta(currentItem, rememberMeta(currentItem))
            val posterUrl = meta?.poster?.takeIf { it.isNotBlank() }
                ?: currentItem.logo?.takeIf { it.isNotBlank() }
                ?: meta?.backdrop

            Box(Modifier.fillMaxSize()) {
                // Background Poster / Art Image
                if (!posterUrl.isNullOrBlank()) {
                    ArtImage(
                        model = posterUrl,
                        contentDescription = currentItem.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color(0xFF2A283E), Color(0xFF13121C))
                                )
                            )
                    )
                }

                // Vertical scrim gradient for readability
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.0f to Color(0x40000000),
                                0.35f to Color.Transparent,
                                0.60f to Color(0xB00D0C15),
                                1.0f to Color(0xF80B0A11)
                            )
                        )
                )

                // Top ranking badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x99000000))
                        .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "🔥 #1 Trending",
                        color = Color(0xFFFFD166),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Bottom Content Information & Action Buttons
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Category / Kind Kicker
                    val kindText = Lang.tr(kindLabel(currentItem.kind)) + (qualityOf(currentItem)?.let { " · " + it.uppercase() } ?: "")
                    Text(
                        text = kindText,
                        color = Accent2,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )

                    // Big Title
                    Text(
                        text = currentItem.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        raw = true
                    )

                    // Metadata Line: Year • Genre • Rating
                    HeroMetaLine(currentItem, meta, dots = true)

                    Spacer(Modifier.height(4.dp))

                    // Buttons Row: [ Watch Now ] and [ + ] Watchlist
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val inWatch = Library.isWatch(currentItem.id)

                        // Primary Watch Now Button
                        val watchBtnShape = RoundedCornerShape(14.dp)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(watchBtnShape)
                                .background(Color(0x3DFFFFFF))
                                .border(1.dp, Color(0x4DFFFFFF), watchBtnShape)
                                .clickable { onPlay(currentItem) },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppIcon(AIcon.Play, Color.White, size = 18.dp)
                                Text(
                                    text = "Jetzt ansehen",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Watchlist Button
                        val favBtnShape = RoundedCornerShape(14.dp)
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(favBtnShape)
                                .background(if (inWatch) Accent1.copy(alpha = 0.35f) else Color(0x2EFFFFFF))
                                .border(1.dp, if (inWatch) Accent1.copy(alpha = 0.6f) else Color(0x38FFFFFF), favBtnShape)
                                .clickable { Library.toggleWatch(ctx, currentItem.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            AppIcon(
                                if (inWatch) AIcon.Check else AIcon.Plus,
                                tint = if (inWatch) Accent1 else Color.White,
                                size = 20.dp
                            )
                        }
                    }
                }
            }
        }
    }
}
