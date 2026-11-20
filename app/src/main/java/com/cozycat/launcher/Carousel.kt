package com.cozycat.launcher

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Selection model for the app row.
 *
 * 8 tile slots always fill the screen edge to edge. The selected app (and the ball) only ever
 * sits in the middle four slots ([ZONE_FIRST]..[ZONE_LAST]); pressing past them slides the whole
 * row instead. With more than 8 apps the row loops around so this always holds.
 *
 * [pos] is a virtual position (can go past the ends); the app is pos mod n.
 * [scroll] is the virtual position of the app in slot 0 (animated).
 */
@Stable
class RowState(private val scope: CoroutineScope) {
    var pos by mutableIntStateOf(START_SLOT)
        private set
    private var scrollTarget = 0
    val scroll = Animatable(0f)
    /** 0..1 grow animation of the selected tile; the previous one shrinks with 1 - pop. */
    val pop = Animatable(1f)
    var prevPos = START_SLOT
        private set

    val slot: Int get() = pos - scrollTarget

    fun appIndex(n: Int, p: Int = pos) = if (n == 0) 0 else Math.floorMod(p, n)

    /** Start with [index] selected in the third slot. */
    fun reset(index: Int, n: Int) {
        val loops = n > SLOTS
        pos = if (loops) index else index
        scrollTarget = if (loops) index - START_SLOT
                       else (index - START_SLOT).coerceIn(0, (n - SLOTS).coerceAtLeast(0))
        prevPos = pos
        scope.launch { scroll.snapTo(scrollTarget.toFloat()); pop.snapTo(1f) }
    }

    fun move(delta: Int, n: Int) {
        if (n == 0) return
        val loops = n > SLOTS
        val next = pos + delta
        if (!loops && next !in 0 until n) return
        prevPos = pos
        pos = next
        var s = scrollTarget
        if (loops) {
            if (pos - s > ZONE_LAST) s = pos - ZONE_LAST
            if (pos - s < ZONE_FIRST) s = pos - ZONE_FIRST
        } else {
            val maxS = (n - SLOTS).coerceAtLeast(0)
            if (pos - s > ZONE_LAST) s = (pos - ZONE_LAST).coerceAtMost(maxS)
            if (pos - s < ZONE_FIRST) s = (pos - ZONE_FIRST).coerceAtLeast(0)
        }
        scrollTarget = s
        scope.launch { scroll.animateTo(s.toFloat(), spring(dampingRatio = 0.85f, stiffness = 140f)) }
        scope.launch { pop.snapTo(0f); pop.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    }

    companion object {
        const val SLOTS = 8
        const val ZONE_FIRST = 2
        const val ZONE_LAST = 5
        const val START_SLOT = 2
        val PITCH = Art.TILE_W + Art.ROW_GAP

        /** Art-space centre x of a slot. */
        fun slotCenterX(slot: Float) = Art.ROW_START + Art.TILE_W / 2f + slot * PITCH
    }
}

/**
 * The app row, drawn as one focusable layer. Left/Right move the selection, OK launches,
 * Up goes to the top bar.
 */
@Composable
fun AppCarousel(
    apps: List<LauncherApp>,
    art: SceneAssets,
    state: RowState,
    k: Float,                     // art px -> screen px
    top: Dp,
    height: Dp,
    focusRequester: FocusRequester,
    upTarget: FocusRequester,
    onMoved: () -> Unit,
    onLaunch: (LauncherApp) -> Unit,
) {
    val n = apps.size
    Box(
        Modifier
            .fillMaxWidth()
            .offset(y = top)
            .height(height)
            .focusRequester(focusRequester)
            .focusProperties { up = upTarget }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { state.move(1, n); onMoved(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { state.move(-1, n); onMoved(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        if (n > 0 && e.nativeKeyEvent.repeatCount == 0) onLaunch(apps[state.appIndex(n)]); true
                    }
                    else -> false
                }
            }
            .focusable()
            .graphicsLayer()
            .drawBehind {
                if (n == 0) return@drawBehind
                val s = state.scroll.value
                val base = floor(s).toInt()
                val frac = s - base
                val pop = state.pop.value
                val topPx = 0f
                val loops = n > RowState.SLOTS
                // draw non-selected first, selected last (on top)
                for (pass in 0..1) {
                    for (j in -1..RowState.SLOTS) {
                        val p = base + j
                        if (!loops && p !in 0 until n) continue
                        val isSel = p == state.pos
                        if ((pass == 1) != isSel) continue
                        val app = apps[Math.floorMod(p, n)]
                        val x = (Art.ROW_START + (j - frac) * RowState.PITCH) * k
                        val w = Art.TILE_W * k
                        val h = Art.TILE_H * k
                        val grow = when (p) {
                            state.pos -> pop
                            state.prevPos -> 1f - pop
                            else -> 0f
                        }
                        val sc = 1f + 0.18f * grow
                        scale(sc, sc, Offset(x + w / 2, topPx + h / 2)) {
                            if (grow > 0.01f) {
                                val m = w * (Art.TILE_GLOW / Art.TILE_W)
                                drawImage(
                                    art.glow, IntOffset.Zero, IntSize(art.glow.width, art.glow.height),
                                    IntOffset((x - m).roundToInt(), (topPx - m).roundToInt()),
                                    IntSize((w + 2 * m).roundToInt(), (h + 2 * m).roundToInt()),
                                    alpha = grow,
                                )
                            }
                            translate(x, topPx) {
                                drawImage(
                                    app.tile, IntOffset.Zero, IntSize(app.tile.width, app.tile.height),
                                    IntOffset.Zero, IntSize(w.roundToInt(), h.roundToInt()),
                                    filterQuality = FilterQuality.High,
                                )
                            }
                        }
                    }
                }
            }
    )
}
