package com.infinity.one

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp

/*
 * Shared building blocks of the UI:
 *  - ArtPlaceholder: placeholder for missing images (gradient, big initials, icon, title)
 *  - screenEnter: fading in of screens (only transparency and shift)
 *  - Pulse: brief give on confirming
 *  - EmptyState: empty/error state with explanation and action
 * Everything respects "Animations" (Off/Subtle/Normal) and never runs in the player.
 */

// ---------------------------------------------------------------------------------------------------------- Placeholders

private fun initialsOf(title: String): String {
    val words = title.replace(Regex("""[^\p{L}\p{N} ]"""), " ").split(' ').filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

private fun iconOf(k: Kind) = when (k) { Kind.LIVE -> AIcon.Live; Kind.SERIES -> AIcon.Layers; Kind.MOVIE -> AIcon.Play }

/**
 * Placeholder for missing images: gradient from the title color, big initials as motif, icon of the kind (movie/series/channel)
 * and the title at the bottom. Lies under the image – if the image does not load or fails, it stays visible.
 */
@Composable
fun ArtPlaceholder(item: Item, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    val base = colorFor(item)
    val grad = remember(base) {
        Brush.linearGradient(
            colors = listOf(
                lerp(base, Color(0xFF20283A), 0.35f),
                lerp(base, Color(0xFF101520), 0.65f),
                Color(0xFF090B0E)
            ),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        )
    }
    Box(
        modifier = modifier
            .background(grad)
            .clipToBounds(),
        contentAlignment = Alignment.Center
    ) {
        // Large background watermark monogram
        Text(
            raw = true,
            text = initialsOf(item.title),
            color = Color.White.copy(alpha = 0.08f),
            fontSize = spx(64),
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center)
        )

        // Center badge with kind icon & initials
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(dpx(6)),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = dpx(12))
        ) {
            Box(
                modifier = Modifier
                    .size(dpx(46))
                    .background(Color.White.copy(alpha = 0.12f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                AppIcon(iconOf(item.kind), Color.White.copy(alpha = 0.92f), size = dpx(24))
            }
            Text(
                raw = true,
                text = initialsOf(item.title),
                color = Color.White.copy(alpha = 0.85f),
                fontSize = spx(14),
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
        }

        // Top-end small badge icon
        AppIcon(
            iconOf(item.kind),
            Color.White.copy(alpha = 0.40f),
            Modifier.align(Alignment.TopEnd).padding(dpx(10)),
            size = dpx(18)
        )

        // Bottom title overlay when showTitle is true (unexpanded card)
        if (showTitle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))))
                    .padding(horizontal = dpx(10), vertical = dpx(8))
            ) {
                Text(
                    raw = true,
                    text = item.title,
                    color = Color.White,
                    fontSize = spx(13),
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    lineHeight = spx(16),
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- Motion

/**
 * Fading in a screen: softly from a slight shift, only transparency/shift (without an intermediate buffer).
 * New [key] = fade in again (e.g. a different tab).
 */
@Composable
fun Modifier.screenEnter(key: Any? = Unit): Modifier {
    if (Look.motion == 0) return this
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.animateTo(1f, tween(if (Look.motion == 1) 220 else 380, easing = FastOutSlowInEasing)) }
    return this.graphicsLayer {
        alpha = a.value
        translationY = (1f - a.value) * 18.dp.toPx()
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

/** Brief give on confirming: call [pulse], use [scale] in graphicsLayer. */
class Pulse(private val anim: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, private val scope: kotlinx.coroutines.CoroutineScope) {
    val scale: Float get() = anim.value
    fun pulse() {
        if (Look.motion == 0) return
        scope.launch {
            anim.snapTo(0.93f)
            anim.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium))
        }
    }
}

@Composable
fun rememberPulse(): Pulse {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val anim = remember { Animatable(1f) }
    return remember { Pulse(anim, scope) }
}

// ---------------------------------------------------------------------------------------------------------- Empty/error state

/** Empty or faulty area: icon, title, explanation and (optionally) an action that helps further. */
@Composable
fun EmptyState(
    icon: AIcon, title: String, text: String, modifier: Modifier = Modifier,
    action: String? = null, actionModifier: Modifier = Modifier, onAction: (() -> Unit)? = null
) {
    Column(
        modifier.widthIn(max = dpx(760)).padding(dpx(24)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dpx(14))
    ) {
        Box(
            Modifier.size(dpx(84)).clip(RoundedCornerShape(50))
                .background(Brush.radialGradient(listOf(Accent1.copy(alpha = 0.28f), Color.Transparent))),
            contentAlignment = Alignment.Center
        ) { AppIcon(icon, Accent2, size = dpx(40)) }
        Text(title, color = Tok.TextHi, fontSize = spx(30), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(text, color = Tok.TextLow, fontSize = spx(19), lineHeight = spx(27), textAlign = TextAlign.Center)
        if (action != null && onAction != null) Row(Modifier.padding(top = dpx(8))) {
            HeroButton(action, AIcon.Right, true, actionModifier, 48) { onAction() }
        }
    }
}
