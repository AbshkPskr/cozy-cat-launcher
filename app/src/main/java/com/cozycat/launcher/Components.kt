package com.cozycat.launcher

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * NOTE: onFocusChanged must sit BEFORE clickable/focusable in the modifier chain,
 * because it observes the focus target that comes after it.
 */

/** Text pill used for Home / Apps / Search. */
@Composable
fun NavPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        when {
            focused -> Color.White.copy(alpha = 0.92f)
            selected -> Color(0xFFF2E4DC).copy(alpha = 0.85f)
            else -> Color.Transparent
        },
        label = "pillBg",
    )
    val fg = if (focused || selected) Ink else Latte

    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .quietClickable(onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Box(Modifier.width(8.dp))
        }
        Text(label, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** Round icon button (cast, settings). */
@Composable
fun IconPill(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        if (focused) Color.White.copy(alpha = 0.92f) else Color.Transparent,
        label = "iconBg",
    )
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .onFocusChanged { focused = it.isFocused }
            .quietClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (focused) Ink else Latte, modifier = Modifier.size(26.dp))
    }
}

@Composable
fun AvatarPill(letter: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.85f))
            .border(2.dp, if (focused) Glow else Color.Transparent, CircleShape)
            .onFocusChanged { focused = it.isFocused }
            .quietClickable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Latte, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

const val TILE_FOCUS_SCALE = 1.18f

/**
 * A knitted app tile (bitmap from the mockup art or composed in AppRepository).
 * [artScale] converts art pixels (1920-wide artwork) to dp on this screen.
 * While focused it reports its unscaled bounds on every layout pass, so the ball keeps
 * tracking it while the row scrolls.
 */
@Composable
fun KnitTile(
    app: LauncherApp,
    glow: ImageBitmap,
    tileW: Dp,
    tileH: Dp,
    onFocused: (Rect) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val scale by animateFloatAsState(
        if (focused) TILE_FOCUS_SCALE else 1f,
        spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )
    val glowA by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "tileGlow")

    Column(modifier.width(tileW), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(tileW, tileH)
                .onGloballyPositioned {
                    bounds = it.boundsInRoot()
                    if (focused) onFocused(bounds)
                }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused(bounds)
                }
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .drawBehind {
                    if (glowA > 0.01f) {
                        // glow bitmap is the tile size + margin on every side (art pixels)
                        val m = size.width * (Art.TILE_GLOW / Art.TILE_W)
                        drawImage(
                            glow,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(glow.width, glow.height),
                            dstOffset = IntOffset(-m.toInt(), -m.toInt()),
                            dstSize = IntSize((size.width + 2 * m).toInt(), (size.height + 2 * m).toInt()),
                            alpha = glowA,
                        )
                    }
                }
                .quietClickable(onClick),
        ) {
            Image(
                bitmap = app.tile,
                contentDescription = app.label,
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
            )
            if (glowA > 0.01f) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .border(2.dp, Color(0xFFFFF1C9).copy(alpha = 0.9f * glowA), RoundedCornerShape(13.dp))
                )
            }
        }
        if (showLabel) {
            Text(
                app.label,
                color = Latte,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** Clickable without the Material grey focus/press overlay; our own focus visuals are used. */
@Composable
fun Modifier.quietClickable(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)
