package com.cozycat.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class LauncherApp(
    val label: String,
    val packageName: String,
    val intent: Intent,
    /** Knitted 200x186 tile, ready to draw. */
    val tile: ImageBitmap,
)

class AppRepository(private val context: Context) {

    /** Knitted tiles painted in the mockup, by package. */
    /**
     * Optional hand-made tiles: map a package name to assets/tiles/<name>.png (200x186 px, same
     * rounded shape as tiles/blank.png). Apps without one get their own banner or icon stitched
     * onto the knitted blank tile automatically.
     */
    private val customTiles = mapOf<String, String>(
        // "com.example.app" to "example",
    )

    /** Call off the main thread: it decodes and composes every tile. */
    fun load(): List<LauncherApp> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        val result = ArrayList<LauncherApp>()
        val blank = SceneAssets.decode(context, "tiles/blank.png")
        val felt = SceneAssets.decode(context, "tiles/felt.png")

        // Last: home-screen activities, so other launchers (some register only as HOME) appear too.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_HOME)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || pkg in LauncherConfig.hiddenPackages || !seen.add(pkg)) continue

                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

                val tile = customTiles[pkg]?.let { SceneAssets.decode(context, "tiles/$it.png") }
                    ?: knitTile(blank, felt,
                        banner = ri.activityInfo.loadBanner(pm)?.toBitmap(320, 180),
                        icon = ri.loadIcon(pm).toBitmap(192, 192))

                result += LauncherApp(
                    label = LauncherConfig.launcherNames[pkg] ?: ri.loadLabel(pm).toString(),
                    packageName = pkg,
                    intent = launch,
                    tile = tile.asImageBitmap(),
                )
            }
        }

        val pinned = LauncherConfig.pinnedPackages
        return result.sortedWith(
            compareBy(
                { app -> pinned.indexOf(app.packageName).let { if (it < 0) Int.MAX_VALUE else it } },
                { app -> app.label.lowercase() },
            )
        )
    }

    /**
     * Puts an app's banner (or icon) onto the blank knitted tile and stitches the felt texture
     * over it, so every app matches the knitted look of the mockup tiles.
     */
    private fun knitTile(blank: Bitmap, felt: Bitmap, banner: Bitmap?, icon: Bitmap): Bitmap {
        val out = blank.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val dst = if (banner != null) {
            val bw = w * 0.80f
            val bh = bw * 9f / 16f
            RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2)
        } else {
            val s = w * 0.56f
            RectF((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
        }
        val src = banner ?: icon
        // Rounded "patch" sewn onto the tile
        val patch = Bitmap.createBitmap(dst.width().toInt(), dst.height().toInt(), Bitmap.Config.ARGB_8888)
        val pc = Canvas(patch)
        val r = if (banner != null) 16f else 22f
        pc.drawRoundRect(RectF(0f, 0f, patch.width.toFloat(), patch.height.toFloat()), r, r, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        pc.drawBitmap(src, null, RectF(0f, 0f, patch.width.toFloat(), patch.height.toFloat()), p)
        p.xfermode = null
        // Soft shadow under the patch
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33402018 }
        c.drawRoundRect(RectF(dst.left + 2f, dst.top + 4f, dst.right + 2f, dst.bottom + 5f), r, r, shadow)
        c.drawBitmap(patch, dst.left, dst.top, p)
        // Felt texture multiplied over the patch so it looks knitted
        c.save()
        c.clipRect(dst)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
        p.alpha = 150
        c.drawBitmap(felt, null, RectF(0f, 0f, w, h), p)
        c.restore()
        p.xfermode = null
        p.alpha = 255
        // Keep the tile's rounded alpha shape
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawBitmap(blank, 0f, 0f, p)
        return out
    }
}
