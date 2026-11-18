package com.infinity.one

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
 * Modern touch swipeable mobile hero carousel slider (like Hotstar / Prime Video / Netflix):
 * - HorizontalPager for smooth touch swiping between top featured titles
 * - Auto-sliding timer with smooth transitions
 * - Bottom page indicator dots (active pill + inactive dots)
 * - Top ranking badge ("#1 Trending", "#2 Trending"...)
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
    val items = remember(featured) { featured.take(8) }
    val pagerState = rememberPagerState(pageCount = { items.size })

    // Auto-advance slider
    val autoSlide = General.heroSec > 0 && items.size > 1
    LaunchedEffect(autoSlide, items.size) {
        while (autoSlide) {
            delay((General.heroSec.coerceAtLeast(4)) * 1000L)
            if (!pagerState.isScrollInProgress) {
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next, animationSpec = tween(600))
            }
        }
    }

    val cardShape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(440.dp)
                .shadow(16.dp, cardShape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(cardShape)
                .background(Color(0xFF14131D)),
            pageSpacing = 12.dp
        ) { page ->
            val currentItem = items[page]
            val meta = rememberFullMeta(currentItem, rememberMeta(currentItem))
            val posterUrl = meta?.poster?.takeIf { it.isNotBlank() }
                ?: currentItem.logo?.takeIf { it.isNotBlank() }
                ?: meta?.backdrop

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onOpen(currentItem) }
            ) {
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
                                0.0f to Color(0x30000000),
                                0.35f to Color.Transparent,
                                0.58f to Color(0xCC0D0C15),
                                1.0f to Color(0xFA0B0A11)
                            )
                        )
                )

                // Top ranking badge
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xB3000000))
                        .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "🔥 #${page + 1} Trending",
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
                                .background(Color.White)
                                .clickable { onPlay(currentItem) },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                AppIcon(AIcon.Play, Color.Black, size = 18.dp)
                                Text(
                                    text = Lang.tr("Jetzt ansehen"),
                                    color = Color.Black,
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
                                .background(if (inWatch) Accent1.copy(alpha = 0.35f) else Color(0x33FFFFFF))
                                .border(1.dp, if (inWatch) Accent1.copy(alpha = 0.6f) else Color(0x40FFFFFF), favBtnShape)
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

        // Pager indicator dots (like Amazon Prime / Hotstar / Netflix)
        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .padding(top = 10.dp)
                    .height(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { i ->
                    val isActive = i == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isActive) Accent1 else Color(0x38FFFFFF))
                            .then(
                                if (isActive) Modifier.size(width = 18.dp, height = 5.dp)
                                else Modifier.size(5.dp)
                            )
                    )
                }
            }
        }
    }
}
