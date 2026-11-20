package com.cozycat.launcher

/** Hidden test switch: `am start --ei exp N` turns parts of the scene off (bit flags). */
object Perf { @JvmField var exp = 0 }

/** Everything you are likely to tweak lives here. */
object LauncherConfig {

    /**
     * Apps shown first, in this order. Anything not installed is skipped; every other
     * app follows alphabetically. Verify package names on your TV with:
     *   adb shell pm list packages | grep -i <name>
     */
    val pinnedPackages = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "in.startv.hotstar.dplus.tv",
        "in.startv.hotstar",
        "com.apple.atve.androidtv.appletv",
        "com.google.android.youtube.tvkids",
        "com.android.vending",
    )

    /** Apps never shown on the home row (other launchers, this launcher, system shells). */
    val hiddenPackages = setOf(
        "com.android.tv.settings",
    )

    /** Other home-screen launchers on the TV, shown as normal apps so you can switch to them. */
    val launcherNames = mapOf(
        "com.spocky.projengmenu" to "Projectivy",
        "com.google.android.tvlauncher" to "Google TV Home",
        "com.google.android.leanbacklauncher" to "Android TV Home",
    )

    const val AVATAR_LETTER = "A"

    // ---- Scene layout (fractions of the screen) ------------------------------------------
    /** Centre of the cat's feet. The cat never moves from here. */
    const val CAT_CENTER_X = 0.50f
    const val CAT_FEET_Y = 0.655f
    /** Cat box width as a fraction of screen width. */
    const val CAT_WIDTH = 0.20f

    /** Ball diameter in dp. */
    const val BALL_DIAMETER_DP = 40f

    // ---- Behaviour ---------------------------------------------------------------------
    /** Delay between pressing OK on an app and actually launching it (paw tap plays meanwhile). */
    const val LAUNCH_DELAY_MS = 320L

    /** Random gap before the cat plays with the ball on its own while idle. */
    const val IDLE_PLAY_MIN_MS = 14_000L
    const val IDLE_PLAY_MAX_MS = 24_000L
}
