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
 * Modern floating Two-Tabs navigation architecture for Phone (portrait):
 * 1. Upper Pill: Category Navigation (Live TV, Movie, Series, Favourite)
 * 2. Lower Pill: Primary Navigation (Home, Search, My Space, Settings)
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
            .padding(horizontal = 16.dp)
            .padding(bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
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
        3 to "Live TV",
        1 to "Filme",
        2 to "Serien",
        7 to "Favoriten"
    )

    Box(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(50))
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xF0181726),
                        Color(0xF0241A35),
                        Color(0xF0181726)
                    )
                )
            )
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 4.dp)
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
                        .background(if (isSelected) Accent1.copy(alpha = 0.28f) else Color.Transparent)
                        .clickable { onSelect(tabId) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) Color.White else Color(0xBBFFFFFF),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }

                if (index < categories.size - 1) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(14.dp)
                            .background(Color(0x1FFFFFFF))
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
        NavPillItem(0, "Home", AIcon.Sparkle, 0),
        NavPillItem(4, "Suche", null, 4),
        NavPillItem(8, "My Space", AIcon.Person, -1),
        NavPillItem(5, "Einstellungen", AIcon.Gear, 5)
    )

    Box(
        modifier = Modifier
            .shadow(16.dp, RoundedCornerShape(50), ambientColor = Color.Black, spotColor = Color.Black)
            .clip(RoundedCornerShape(50))
            .background(Color(0xF212121B))
            .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { item ->
                val isSelected = when (item.id) {
                    0 -> activeTab == 0
                    4 -> activeTab == 4
                    8 -> activeTab in listOf(7, 8, 9) // My Space includes Favorites, My List, History
                    5 -> activeTab == 5
                    else -> activeTab == item.id
                }

                val itemShape = RoundedCornerShape(50)
                Column(
                    modifier = Modifier
                        .clip(itemShape)
                        .clickable { onSelect(item.id) }
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val tint = if (isSelected) Accent1 else Color(0x99FFFFFF)
                    if (item.menuIconId >= 0) {
                        MenuIcon(item.menuIconId, tint, Modifier.size(20.dp))
                    } else if (item.icon != null) {
                        AppIcon(item.icon, tint, size = 20.dp)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        color = if (isSelected) Color.White else Color(0x88FFFFFF),
                        fontSize = 11.sp,
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
