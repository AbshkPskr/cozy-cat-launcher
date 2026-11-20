package com.cozycat.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Motion of the knitted kitten. When the ball rolls away, the kitten turns toward it (a little
 * hop-turn), trots over with alternating paw steps, a bouncy gait and a swishing tail, and
 * pounces when it arrives. It also breathes, blinks, flicks its ears and plays when idle.
 */
@Stable
class KittenMotion(private val scope: CoroutineScope) {
    val time = mutableFloatStateOf(0f)

    /** +1 = facing left (as drawn), -1 = facing right (mirrored). */
    var facing by mutableStateOf(1f)
        private set
    private var fromFacing = 1f
    val turn = Animatable(1f)          // 0..1 progress of the current turn
    val walkX = Animatable(0f)         // offset from the home spot, art px
    val lunge = Animatable(0f)         // px in art space, + = toward facing direction
    val squash = Animatable(0f)
    val wiggle = Animatable(0f)
    private var walkJob: Job? = null

    private var nextBlink = 2f
    private var blinkStart = -10f
    private var nextTwitch = 3f
    private var twitchStart = -10f
    private var twitchSide = 1

    /** The kitten stays in the middle: face the ball and pounce at it. */
    fun react(ballX: Float) {
        walkJob?.cancel()
        walkJob = scope.launch {
            val want = if (ballX < Art.FLIP_X + Art.CENTER_WALK) 1f else -1f
            if (want != facing) {
                turnTo(want)
                delay(170)
            }
            pounce()
        }
    }

    /** Kitten centre (mirror axis) right now, art px. */
    val centerX: Float get() = Art.FLIP_X + walkX.value

    /** Smoothed iris offset (kitten-local art px) so the eyes follow the ball. */
    internal var eyeX = 0f
    internal var eyeY = 1.5f

    /** Smoothed 0..1: tail held low (when it would poke into the top-bar buttons). */
    internal var tailLow = 0f

    /**
     * Chase the ball at art-space [ballX]. [dir] is the way it is rolling (-1 left, +1 right,
     * 0 = not moving): the kitten faces that way and follows behind it, raised paw reaching
     * for the ball.
     */
    fun chase(ballX: Float, dir: Int) {
        walkJob?.cancel()
        walkJob = scope.launch {
            val r = Art.BALL_R
            fun targetFor(f: Float) = if (f > 0f) ballX + r * 0.85f - Art.PAW_TIP - Art.FLIP_X
                                      else ballX - r * 0.85f + Art.PAW_TIP - Art.FLIP_X
            fun fits(f: Float) = targetFor(f) in (Art.WALK_MIN - 30f)..(Art.WALK_MAX + 30f)
            var want = when {
                dir < 0 -> 1f
                dir > 0 -> -1f
                else -> if (ballX < centerX) 1f else -1f
            }
            // Can't follow from behind without leaving the screen? Face the ball instead.
            if (!fits(want) && fits(-want)) want = -want
            if (want != facing) {
                turnTo(want)
                delay(200)
            }
            val t = targetFor(facing).coerceIn(Art.WALK_MIN, Art.WALK_MAX)
            val dist = abs(t - walkX.value)
            if (dist > 4f) {
                // spring keeps its speed when the target changes mid-walk (fast D-pad presses)
                walkX.animateTo(t, spring(dampingRatio = 0.92f, stiffness = 26f, visibilityThreshold = 1f))
            }
            pounce()
        }
    }

    private fun turnTo(want: Float) {
        fromFacing = facing
        facing = want
        scope.launch {
            turn.snapTo(0f)
            turn.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
        }
    }

    /** Arrived at the ball: a quick pounce-lunge. */
    fun pounce() {
        scope.launch {
            launch {
                squash.animateTo(1f, tween(110))
                squash.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 260f))
            }
            lunge.animateTo(22f, tween(140, easing = FastOutSlowInEasing))
            lunge.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 120f))
        }
    }

    /** OK pressed: bigger lunge and squash. */
    fun tap() {
        scope.launch {
            launch {
                squash.animateTo(1.4f, tween(120))
                squash.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 240f))
            }
            lunge.animateTo(40f, tween(140, easing = FastOutSlowInEasing))
            lunge.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 110f))
        }
    }

    /** Idle play: a playful butt wiggle, then a pounce. */
    fun play() {
        scope.launch {
            wiggle.animateTo(1f, tween(200))
            delay(800)
            wiggle.animateTo(0f, tween(200))
            pounce()
        }
    }

    internal fun blink(t: Float): Boolean {
        if (t >= nextBlink) {
            blinkStart = t
            nextBlink = t + 2.5f + Random.nextFloat() * 3.5f
        }
        return (t - blinkStart) in 0f..0.15f
    }

    /** Returns (side, amount 0..1) of an occasional ear flick. side: -1 left ear, +1 right ear. */
    internal fun earTwitch(t: Float): Pair<Int, Float> {
        if (t >= nextTwitch) {
            twitchStart = t
            twitchSide = if (Random.nextBoolean()) 1 else -1
            nextTwitch = t + 3f + Random.nextFloat() * 5f
        }
        val p = (t - twitchStart) / 0.28f
        return twitchSide to (if (p in 0f..1f) sin(p * PI).toFloat() else 0f)
    }

    internal fun turnState(): Triple<Float, Float, Float> {
        val p = turn.value
        val hop = sin(p * PI).toFloat()
        val mix = ((p - 0.38f) / 0.24f).coerceIn(0f, 1f)
        return Triple(hop, mix, fromFacing)
    }
}

@Composable
fun rememberKittenMotion(scope: CoroutineScope): KittenMotion {
    val m = remember { KittenMotion(scope) }
    LaunchedEffect(m) {
        m.walkX.snapTo(Art.CENTER_WALK)
        val start = withFrameNanos { it }
        while (true) withFrameNanos { m.time.floatValue = (it - start) / 1e9f }
    }
    return m
}

/** Kitten (all parts) + sleeping kitten, drawn in one full-screen layer. */
@Composable
fun KittenLayer(art: SceneAssets, motion: KittenMotion, ball: BallMotion, modifier: Modifier = Modifier) {
    val sockL = remember { socketPath(Art.SOCKET_L) }
    val sockR = remember { socketPath(Art.SOCKET_R) }
    Box(modifier.fillMaxSize().graphicsLayer().drawBehind {
        val k = size.width / Art.W
        val t = motion.time.floatValue

        // ---- Sleeping kitten: gentle breathing ----
        val br = sin(t * 2f * PI.toFloat() / 4.2f)
        withTransform({
            scale(k, k, Offset.Zero)
            scale(1f + br * 0.006f, 1f + br * 0.018f, Offset(Art.SLEEPER_X + 160f, Art.SLEEPER_Y + 200f))
        }) { drawArt(art.sleeper, Art.SLEEPER_X, Art.SLEEPER_Y) }

        // ---- Main kitten ----
        val (turnHop, mix, from) = motion.turnState()
        val to = motion.facing
        val walk = motion.walkX.value
        val speed = abs(motion.walkX.velocity)
        val moving = (speed / 250f).coerceIn(0f, 1f)
        val stride = 95f
        val phase = walk / stride                          // one step per stride
        val step = sin(phase * PI).toFloat()
        val gaitHop = abs(step) * 14f * moving
        val rock = sin(phase * 2 * PI).toFloat() * 2.2f * moving
        // alternate paw lifts: front lifts on one half of the stride, hind on the other
        val liftF = (step.coerceAtLeast(0f)) * 18f * moving
        val liftH = ((-step).coerceAtLeast(0f)) * 16f * moving
        val breath = sin(t * 2f * PI.toFloat() / 3.2f)
        val sq = motion.squash.value
        val wig = sin(t * 30f) * motion.wiggle.value
        val blink = motion.blink(t)
        val (twSide, twAmt) = motion.earTwitch(t)
        val sway = sin(t * 1.4f) * 3f + sin(t * 2.7f) * 1f + wig * 5f + sin(phase * 2 * PI).toFloat() * 4f * moving
        val hop = turnHop * 30f + gaitHop

        // Eyes follow the ball: aim from the point between the eyes, smoothed for a natural glance
        run {
            val faceSign = if (to > 0f) 1f else -1f
            val ex = Art.FLIP_X + walk + faceSign * (Art.EYES_X - Art.FLIP_X)
            val ey = Art.EYES_Y - hop
            val bx = if (ball.placed) ball.x.value else ex
            val dx = bx - ex
            val dy = Art.BALL_Y - ey
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val reach = (len / 260f).coerceIn(0.35f, 1f)
            val tx = dx / len * 3.2f * reach * faceSign      // a gentle glance, mirrored into kitten space
            val ty = dy / len * 2.5f * reach
            motion.eyeX += (tx - motion.eyeX) * 0.12f
            motion.eyeY += (ty - motion.eyeY) * 0.12f
        }
        val tipX = Art.FLIP_X + walk + (if (to > 0f) 280f else -280f)
        val tailTarget = if (tipX < 900f || tipX > 1530f) 1f else 0f
        motion.tailLow += (tailTarget - motion.tailLow) * 0.12f

        // Soft contact shadow on the floor (shrinks while airborne)
        val shadowScale = 1f - (hop / 60f).coerceIn(0f, 0.4f)
        drawOval(
            Color(0x22503028),
            topLeft = Offset((Art.FLIP_X + walk - 250f * shadowScale) * k, (Art.FEET_Y - 12f) * k),
            size = Size(500f * shadowScale * k, 26f * k),
        )

        fun drawKitten(face: Float, alpha: Float) {
            if (alpha <= 0.01f) return
            val lungeX = -face * motion.lunge.value
            val narrow = 1f
            withTransform({
                scale(k, k, Offset.Zero)
                translate(walk + lungeX, -hop)
                scale(face * narrow, 1f, Offset(Art.FLIP_X, Art.FEET_Y))
                scale(1f + sq * 0.035f, 1f + breath * 0.007f - sq * 0.05f + turnHop * 0.05f + abs(step) * 0.025f * moving, Offset(Art.FLIP_X, Art.FEET_Y))
                rotate(wig * 1.6f + rock, Offset(Art.FLIP_X + 200f, Art.FEET_Y))
            }) {
                // The tail is held low and back whenever its tip would reach into the top-bar
                // buttons (they sit at the left and right; the middle of the top is free).
                val tailExtra = 0f
                withTransform({ rotate(sway + tailExtra, Offset(Art.TAIL_PIVOT_X, Art.TAIL_PIVOT_Y)) }) {
                    drawArt(art.tail, Art.TAIL_X, Art.TAIL_Y, alpha)
                }
                drawArt(art.kitten, Art.KITTEN_X, Art.KITTEN_Y, alpha)
                drawArt(art.legF, Art.LEG_F_X, Art.LEG_F_Y - liftF, alpha)
                drawArt(art.legH, Art.LEG_H_X, Art.LEG_H_Y - liftH, alpha)
                val earL = (if (twSide < 0) twAmt * 9f else 0f) + sin(phase * 2 * PI).toFloat() * 3f * moving
                val earR = (if (twSide > 0) -twAmt * 9f else 0f) - sin(phase * 2 * PI).toFloat() * 3f * moving
                withTransform({ rotate(earL, Offset(Art.EAR_L_PX, Art.EAR_L_PY)) }) {
                    drawArt(art.earL, Art.EAR_L_X, Art.EAR_L_Y, alpha)
                }
                withTransform({ rotate(earR, Offset(Art.EAR_R_PX, Art.EAR_R_PY)) }) {
                    drawArt(art.earR, Art.EAR_R_X, Art.EAR_R_Y, alpha)
                }
                // Irises (original art, shine included) glance a few px inside their sockets
                val ix = motion.eyeX; val iy = motion.eyeY
                clipPath(sockL) { drawArt(art.irisL, Art.IRIS_L_X + ix, Art.IRIS_L_Y + iy, alpha) }
                clipPath(sockR) { drawArt(art.irisR, Art.IRIS_R_X + ix, Art.IRIS_R_Y + iy, alpha) }
                if (blink) drawArt(art.blink, Art.KITTEN_X, Art.KITTEN_Y, alpha)
            }
        }
        if (motion.turn.value >= 1f || from == to) {
            drawKitten(to, 1f)
        } else {
            // Flip around through a narrow "side-on" squash (no cross-fade, so no double image)
            val c = kotlin.math.cos(motion.turn.value * PI).toFloat()          // 1 .. -1
            val sx = (if (c >= 0f) 1f else -1f) * kotlin.math.max(abs(c), 0.45f)
            drawKitten(from * sx, 1f)
        }
    })
}

private fun socketPath(s: FloatArray) = androidx.compose.ui.graphics.Path().apply {
    addOval(androidx.compose.ui.geometry.Rect(s[0] - s[2], s[1] - s[3], s[0] + s[2], s[1] + s[3]))
}

/** Draws an art-space bitmap at art-space (x, y) with good filtering. */
private fun DrawScope.drawArt(img: ImageBitmap, x: Float, y: Float, alpha: Float = 1f) {
    translate(x, y) {
        drawImage(
            img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(img.width, img.height),
            alpha = alpha,
            filterQuality = FilterQuality.Low,
        )
    }
}

// ---------------------------------------------------------------------------------- ball

@Stable
class BallMotion(private val scope: CoroutineScope) {
    val x = Animatable(0f)          // art-space centre x
    val hop = Animatable(0f)
    val wobble = Animatable(0f)
    var placed by mutableStateOf(false)
        private set
    /** Extra roll distance (art px) so the ball spins while the app row slides under it. */
    var extraRoll: () -> Float = { 0f }

    fun rollTo(artX: Float) {
        if (!placed) {
            scope.launch { x.snapTo(artX) }
            placed = true
            return
        }
        scope.launch { x.animateTo(artX, spring(dampingRatio = 0.8f, stiffness = 60f)) }
    }

    fun bounce() {
        scope.launch {
            hop.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
            hop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 300f))
        }
    }

    fun jiggle() {
        scope.launch {
            repeat(2) {
                wobble.animateTo(1f, tween(170))
                wobble.animateTo(-1f, tween(240))
            }
            wobble.animateTo(0f, tween(220))
        }
    }
}

/** The knitted yarn ball: texture rotates as it rolls, lighting stays put. */
@Composable
fun BallLayer(art: SceneAssets, motion: BallMotion, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().graphicsLayer().drawBehind {
        if (!motion.placed) return@drawBehind
        val k = size.width / Art.W
        val r = Art.BALL_R
        val wob = motion.wobble.value * r * 0.35f
        val cx = motion.x.value + wob
        val speed = (abs(motion.x.velocity) / (r * 16f)).coerceIn(0f, 1f)
        val rollHop = abs(sin(cx / (r * 3.4f) * PI)).toFloat() * speed * r * 0.3f
        val lift = rollHop + motion.hop.value * r * 0.9f
        val cy = Art.BALL_Y - lift
        withTransform({ scale(k, k, Offset.Zero) }) {
            val sq = 1f - (lift / r).coerceIn(0f, 1f) * 0.45f
            drawOval(
                Color(0x45503028),
                topLeft = Offset(cx - r * 0.9f * sq, Art.BALL_Y + r * 0.78f),
                size = Size(r * 1.8f * sq, r * 0.34f),
            )
            val half = r + Art.BALL_PAD
            rotate(Math.toDegrees(((cx + motion.extraRoll()) / r).toDouble()).toFloat(), Offset(cx, cy)) {
                drawArt(art.ball, cx - half, cy - half)
            }
            drawArt(art.ballShade, cx - half, cy - half)
        }
    })
}
