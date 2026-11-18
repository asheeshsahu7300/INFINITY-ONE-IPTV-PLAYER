package com.infinity.one

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern floating Two-Tabs navigation architecture inspired by JioHotstar / Prime Video for Phone:
 * 1. Upper Pill: Category Navigation (Live TV, Movies, Series, Favorites)
 * 2. Lower Pill: Primary Navigation (Home, Search, My Space, Settings)
 *
 * Uses [navigationBarsPadding] with comfortable clearance so it never collides with gesture bars.
 */
@Composable
fun TwoTabsBar(
    currentTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
    showUpper: Boolean = true
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(
            visible = showUpper,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            UpperCategoryPill(activeTab = currentTab, onSelect = onSelectTab)
        }
        LowerNavigationPill(activeTab = currentTab, onSelect = onSelectTab)
    }
}

@Composable
private fun UpperCategoryPill(
    activeTab: Int,
    onSelect: (Int) -> Unit
) {
    // Upper Categories: Live TV (3), Movie (1), Series (2), Favourite (7)
    val categories = listOf(
        3 to Lang.tr("Live TV"),
        1 to Lang.tr("Filme"),
        2 to Lang.tr("Serien"),
        7 to Lang.tr("Favoriten")
    )

    Box(
        modifier = Modifier
            .shadow(22.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(50))
            .background(Color(0xF0141414))
            .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .clickable(enabled = false) { }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            categories.forEachIndexed { index, (tabId, label) ->
                val isSelected = activeTab == tabId
                val itemShape = RoundedCornerShape(50)
                Box(
                    modifier = Modifier
                        .clip(itemShape)
                        .background(if (isSelected) Color.White else Color.Transparent)
                        .clickable { onSelect(tabId) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 13.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }

                if (index < categories.size - 1 && !isSelected && activeTab != categories[index + 1].first) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(Color(0x25FFFFFF))
                    )
                }
            }
        }
    }
}

@Composable
private fun LowerNavigationPill(
    activeTab: Int,
    onSelect: (Int) -> Unit
) {
    // Lower Navigation: Home (0), Search (4), My Space (8), Settings (5)
    val items = listOf(
        NavPillItem(0, Lang.tr("Home"), AIcon.Sparkle, -1),
        NavPillItem(4, Lang.tr("Suche"), null, 4),
        NavPillItem(8, Lang.tr("My Space"), AIcon.Person, -1),
        NavPillItem(5, Lang.tr("Einstellungen"), AIcon.Gear, 5)
    )

    Box(
        modifier = Modifier
            .shadow(32.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(50))
            .background(Color(0xF2121212))
            .border(1.dp, Color(0x30FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .clickable(enabled = false) { }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { item ->
                val isSelected = when (item.id) {
                    0 -> activeTab in listOf(0, 1, 2, 3)
                    4 -> activeTab == 4
                    8 -> activeTab in listOf(7, 8, 9) // My Space includes Favorites, My List, History
                    5 -> activeTab == 5
                    else -> activeTab == item.id
                }

                val itemShape = RoundedCornerShape(50)
                Column(
                    modifier = Modifier
                        .clip(itemShape)
                        .background(if (isSelected) Color(0x30FFFFFF) else Color.Transparent)
                        .clickable { onSelect(item.id) }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val tint = if (isSelected) Color.White else Color(0x88FFFFFF)
                    if (item.menuIconId >= 0) {
                        MenuIcon(item.menuIconId, tint, Modifier.size(22.dp))
                    } else if (item.icon != null) {
                        AppIcon(item.icon, tint, size = 22.dp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = item.label,
                        color = tint,
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

private data class NavPillItem(
    val id: Int,
    val label: String,
    val icon: AIcon?,
    val menuIconId: Int
)
