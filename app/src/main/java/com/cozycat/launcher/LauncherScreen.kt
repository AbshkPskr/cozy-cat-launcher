package com.cozycat.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

@Composable
fun LauncherScreen(
    apps: List<LauncherApp>,
    homeSignal: Int,
    onLaunch: (LauncherApp) -> Unit,
    onSystemAction: (SystemAction) -> Unit,
) {
    val context = LocalContext.current
    val art by produceState<SceneAssets?>(null) {
        value = withContext(Dispatchers.IO) { SceneAssets.load(context.applicationContext) }
    }
    val a = art
    if (a == null) {
        Box(Modifier.fillMaxSize().background(Color(0xFFE6BFB2)))
        return
    }
    Scene(a, apps, homeSignal, onLaunch, onSystemAction)
}

@Composable
private fun Scene(
    art: SceneAssets,
    apps: List<LauncherApp>,
    homeSignal: Int,
    onLaunch: (LauncherApp) -> Unit,
    onSystemAction: (SystemAction) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val kitten = rememberKittenMotion(scope)
    val ball = remember { BallMotion(scope) }
    val row = remember { RowState(scope) }
    var tab by remember { mutableStateOf(Tab.Home) }
    val prefs = LocalContext.current.getSharedPreferences("cozy", android.content.Context.MODE_PRIVATE)
    var lastInputAt by remember { mutableStateOf(System.currentTimeMillis()) }
    val rowRequester = remember { FocusRequester() }
    val tabRequester = remember { FocusRequester() }
    ball.extraRoll = { row.scroll.value * RowState.PITCH }

    fun placeBall(initial: Boolean) {
        val bx = RowState.slotCenterX(row.slot.toFloat())
        ball.rollTo(bx)
        if (initial) kitten.react(bx) else kitten.react(bx)
    }

    // Start with the last opened app selected (or the third app), in the third slot.
    var initialised by remember { mutableStateOf(false) }
    LaunchedEffect(apps) {
        if (apps.isEmpty()) return@LaunchedEffect
        if (!initialised) {
            val last = prefs.getString("last", null)
            val i = apps.indexOfFirst { it.packageName == last }.let { if (it < 0) RowState.START_SLOT.coerceAtMost(apps.lastIndex) else it }
            row.reset(i, apps.size)
            initialised = true
            placeBall(true)
        }
    }

    BackHandler { tab = Tab.Home }

    var refocus by remember { mutableIntStateOf(0) }
    LaunchedEffect(homeSignal) { if (homeSignal > 0) { tab = Tab.Home; refocus++ } }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refocus++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refocus, apps.isNotEmpty(), tab) {
        if (tab != Tab.Home || apps.isEmpty()) return@LaunchedEffect
        repeat(10) {
            if (runCatching { rowRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }

    // Idle play
    LaunchedEffect(tab) {
        if (tab != Tab.Home) return@LaunchedEffect
        while (true) {
            delay(Random.nextLong(LauncherConfig.IDLE_PLAY_MIN_MS, LauncherConfig.IDLE_PLAY_MAX_MS))
            if (System.currentTimeMillis() - lastInputAt > 8_000) {
                kitten.play()
                delay(900)
                ball.jiggle()
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val k = wPx / Art.W                     // art px -> screen px
        fun artDp(v: Float): Dp = with(density) { (v * k).toDp() }

        Image(art.room, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.Low)

        if (tab == Tab.Home) {
            KittenLayer(art, kitten, ball)
            BallLayer(art, ball)
        }

        if (tab == Tab.Apps) Box(Modifier.fillMaxSize().background(Color(0xB33A2622)))

        TopBar(
            tab = tab,
            onTab = { tab = it },
            onAction = onSystemAction,
            tabRequester = tabRequester,
            downTarget = { if (tab == Tab.Home && apps.isNotEmpty()) rowRequester else FocusRequester.Default },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        when (tab) {
            Tab.Home -> AppCarousel(
                apps = apps,
                art = art,
                state = row,
                k = k,
                top = artDp(Art.ROW_TOP),
                height = artDp(Art.TILE_H),
                focusRequester = rowRequester,
                upTarget = tabRequester,
                onMoved = {
                    lastInputAt = System.currentTimeMillis()
                    placeBall(false)
                },
                onLaunch = { app ->
                    lastInputAt = System.currentTimeMillis()
                    kitten.tap()
                    ball.bounce()
                    scope.launch {
                        delay(LauncherConfig.LAUNCH_DELAY_MS)
                        prefs.edit().putString("last", app.packageName).apply()
                        onLaunch(app)
                    }
                },
            )
            Tab.Apps -> AppsGrid(apps, art.glow, artDp(Art.TILE_W), artDp(Art.TILE_H), onLaunch)
        }
    }
}

@Composable
private fun AppsGrid(apps: List<LauncherApp>, glow: ImageBitmap, tileW: Dp, tileH: Dp, onClick: (LauncherApp) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(tileW + 24.dp),
        modifier = Modifier.fillMaxSize().padding(top = 110.dp),
        contentPadding = PaddingValues(horizontal = 56.dp, vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        items(apps, key = { it.packageName }) { app ->
            KnitTile(app, glow, tileW, tileH, onFocused = {}, onClick = { onClick(app) }, showLabel = true)
        }
    }
}
