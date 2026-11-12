/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.compose.base.theme

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * KiwiFox: a desktop theme header (frame color plus `theme_frame` / `additional_backgrounds` layers).
 *
 * Desktop paints the header across the top of the window (tab strip and toolbar, 85 CSS px in Firefox
 * 159) and the theme's `toolbar` color over its lower part. On Android the same band is the status bar
 * plus the toolbar: both draw this header in one coordinate system whose origin is the top of the
 * screen, so the artwork runs continuously from the status bar into the toolbar, and the toolbar paints
 * [toolbar] (usually translucent) and the address field [field] over it.
 *
 * Geometry follows desktop CSS: each layer is placed by `background-position` ([KiwiHeaderLayer.alignment])
 * and repeated by `background-repeat` ([KiwiHeaderLayer.tiling]) in a box as wide as the screen and as
 * tall as the header (status bar + toolbar), or as tall as the screen for `backgrounds_area: window`.
 * One CSS px is [DP_PER_CSS_PX] dp: desktop's 85 px header then matches a phone's status bar + toolbar.
 *
 * @property frame Desktop `frame`, painted under the layers.
 * @property toolbar Desktop `toolbar` with its alpha, painted over the header by the toolbar.
 * @property field Desktop `toolbar_field` with its alpha, the address field over the header.
 * @property layers Top-most first, in manifest order.
 * @property statusBar A single color for the status bar where it can't draw the header, null = [frame].
 * @property wallpaper The header's artwork also fills the homepage and the navigation bar (see
 * [drawKiwiWallpaper]); only headers with an image have artwork to show.
 */
data class KiwiHeader(
    val frame: Color,
    val toolbar: Color,
    val field: Color,
    val layers: List<KiwiHeaderLayer>,
    val statusBar: Color? = null,
    val wallpaper: Boolean = false,
) {
    val animated: Boolean get() = layers.any { it is KiwiHeaderLayer.Animated }

    /** True when the header has artwork (an image), not just colors and gradients. */
    val hasPicture: Boolean get() = layers.any { it !is KiwiHeaderLayer.Gradient }

    /** The wallpaper's fill color, computed once (see [wallpaperFill]). */
    @Volatile
    internal var ambientCache: Pair<String, Color>? = null

    companion object {
        /** Desktop CSS px to Android dp for header artwork. */
        const val DP_PER_CSS_PX = 1f

        /** The toolbar's height below the status bar, part of the header box. */
        val TOOLBAR_HEIGHT: Dp = 64.dp
    }
}

/**
 * One header layer.
 *
 * @property alignment CSS `background-position` keywords, e.g. `right top` (default `left top`).
 * @property tiling CSS `background-repeat`: `no-repeat`, `repeat`, `repeat-x`, `repeat-y`.
 * @property width Layer width in CSS px, or null for natural / full width.
 * @property height Layer height in CSS px, or null for natural / full height.
 * @property windowArea Sized to the whole screen instead of the header (`backgrounds_area: window`).
 */
sealed class KiwiHeaderLayer {
    abstract val alignment: String
    abstract val tiling: String
    abstract val width: Float?
    abstract val height: Float?
    abstract val windowArea: Boolean

    /** A still image; [pxPerCss] is image pixels per CSS px (pre-rasterized SVGs use a higher scale). */
    data class Image(
        val bitmap: ImageBitmap,
        val pxPerCss: Float,
        override val alignment: String,
        override val tiling: String,
        override val width: Float? = null,
        override val height: Float? = null,
        override val windowArea: Boolean = false,
    ) : KiwiHeaderLayer()

    /**
     * An animated image (APNG, GIF, animated WebP) as a self-animating [Drawable] whose intrinsic size is
     * in image pixels. Its callback is [KiwiHeaderTicker], which redraws every header on each frame.
     */
    data class Animated(
        val drawable: Drawable,
        val pxPerCss: Float,
        override val alignment: String,
        override val tiling: String,
        override val width: Float? = null,
        override val height: Float? = null,
        override val windowArea: Boolean = false,
    ) : KiwiHeaderLayer()

    /**
     * A CSS `linear-gradient` resolved to a direction ([dx], [dy]: unit vector, e.g. 0,1 = `to bottom`)
     * and color stops at fractions of the gradient line (may lie outside 0..1, as CSS allows).
     */
    data class Gradient(
        val dx: Float,
        val dy: Float,
        val stops: List<Pair<Float, Color>>,
        override val alignment: String,
        override val tiling: String,
        override val width: Float? = null,
        override val height: Float? = null,
        override val windowArea: Boolean = false,
    ) : KiwiHeaderLayer()
}

/**
 * Drives animated header layers: the [Drawable.Callback] of every animated layer. Each new frame bumps
 * [tick] (Compose headers read it while drawing, so they redraw) and invalidates registered views (the
 * status bar), keeping status bar and toolbar on the same frame.
 */
object KiwiHeaderTicker : Drawable.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private val views = WeakHashMap<View, Unit>()

    var tick by mutableIntStateOf(0)
        private set

    fun register(view: View) {
        views[view] = Unit
    }

    override fun invalidateDrawable(who: Drawable) {
        tick++
        views.keys.forEach { it.postInvalidateOnAnimation() }
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        handler.postAtTime(what, who, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        handler.removeCallbacks(what, who)
    }

    /** Milliseconds for frame-based drawables. */
    fun now(): Long = SystemClock.uptimeMillis()
}

/** Status bar height for the header box; the screenshot harness provides its stand-in's height. */
val LocalKiwiStatusBarHeight = staticCompositionLocalOf<Dp?> { null }

/**
 * Draws the theme's header behind [content] when the theme in effect has one; otherwise just [content].
 * The header's origin is the top of the screen, so a toolbar under the status bar continues the
 * artwork the status bar shows. A toolbar elsewhere (bottom toolbar) shows the band below the status bar.
 */
@Composable
fun KiwiHeaderBox(content: @Composable () -> Unit) {
    val header = KiwiStyles.chrome()?.header
    if (header == null) {
        content()
        return
    }
    val density = LocalDensity.current
    val statusBarPx = LocalKiwiStatusBarHeight.current?.let { with(density) { it.toPx() } }
        ?: WindowInsets.statusBars.getTop(density).toFloat()
    val toolbarPx = with(density) { KiwiHeader.TOOLBAR_HEIGHT.toPx() }
    val windowPx = LocalWindowInfo.current.containerSize.height.toFloat()
    var top by remember { mutableFloatStateOf(statusBarPx) }
    Box(
        modifier = Modifier
            .onGloballyPositioned { top = it.positionInWindow().y }
            .clipToBounds()
            .drawBehind {
                // Under the status bar: continue its band. Anywhere else: the band just below the status bar.
                val origin = if (top <= statusBarPx + toolbarPx) top else statusBarPx
                drawKiwiHeader(header, origin, statusBarPx + toolbarPx, maxOf(windowPx, size.height))
            },
    ) { content() }
}

/**
 * Draws [header] into this scope, which shows the band from [originPx] (distance from the top of the
 * screen) downwards. [headerPx] is the header box height (status bar + toolbar), [windowPx] the screen
 * height (for `backgrounds_area: window` layers).
 */
fun DrawScope.drawKiwiHeader(header: KiwiHeader, originPx: Float, headerPx: Float, windowPx: Float) {
    // Reading the ticker makes this draw pass observe it, so animated headers redraw on each frame.
    if (header.animated && KiwiHeaderTicker.tick < 0) return
    drawRect(header.frame)
    val pxPerCss = density * KiwiHeader.DP_PER_CSS_PX
    val boxW = size.width / pxPerCss
    translate(top = -originPx) {
        for (layer in header.layers.asReversed()) {
            val boxH = (if (layer.windowArea) windowPx else headerPx) / pxPerCss
            drawLayer(layer, boxW, boxH, pxPerCss)
        }
    }
}

/** An image layer's size in image pixels. */
private fun KiwiHeaderLayer.pixelSize(): Pair<Int, Int>? = when (this) {
    is KiwiHeaderLayer.Image -> bitmap.width to bitmap.height
    is KiwiHeaderLayer.Animated -> drawable.intrinsicWidth to drawable.intrinsicHeight
    is KiwiHeaderLayer.Gradient -> null
}?.takeIf { it.first > 0 && it.second > 0 }

/** The image shown as the homepage wallpaper: the header's largest image (by pixels), or null. */
fun KiwiHeader.wallpaperLayer(): KiwiHeaderLayer? =
    layers.filter { it.pixelSize() != null }.maxByOrNull { l -> l.pixelSize()!!.let { it.first.toLong() * it.second } }

/** Image pixels per CSS px of an image layer. */
private val KiwiHeaderLayer.imagePxPerCss: Float
    get() = when (this) {
        is KiwiHeaderLayer.Image -> pxPerCss
        is KiwiHeaderLayer.Animated -> pxPerCss
        is KiwiHeaderLayer.Gradient -> 1f
    }

/** The layer with any downward repeat removed (`repeat` keeps repeating sideways). */
private fun KiwiHeaderLayer.onceDown(): KiwiHeaderLayer {
    val t = when (tiling) {
        "repeat" -> "repeat-x"
        "repeat-y" -> "no-repeat"
        else -> return this
    }
    return when (this) {
        is KiwiHeaderLayer.Image -> copy(tiling = t)
        is KiwiHeaderLayer.Animated -> copy(tiling = t)
        is KiwiHeaderLayer.Gradient -> copy(tiling = t)
    }
}

/** A layer's natural height in CSS px ([boxH] for gradients without a height). */
private fun KiwiHeaderLayer.cssHeight(boxH: Float): Float = when (this) {
    is KiwiHeaderLayer.Gradient -> height ?: boxH
    else -> height ?: pixelSize()?.let { (w, h) -> width?.let { h * it / w } ?: (h / imagePxPerCss) } ?: 0f
}

/**
 * The color the wallpaper continues in below its image: the average of the main image's bottom edge
 * (so the image fades into its own color), or [KiwiHeader.frame]. Computed once per header.
 */
fun KiwiHeader.wallpaperFill(): Color {
    ambientCache?.let { return it.second }
    val layer = wallpaperLayer() as? KiwiHeaderLayer.Image
    val color = layer?.let {
        runCatching {
            var bitmap = it.bitmap.asAndroidBitmap()
            if (bitmap.config == android.graphics.Bitmap.Config.HARDWARE) {
                bitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            }
            val rows = (bitmap.height / FILL_EDGE_FRACTION).coerceAtLeast(1)
            val edge = android.graphics.Bitmap.createBitmap(bitmap, 0, bitmap.height - rows, bitmap.width, rows)
            val pixel = android.graphics.Bitmap.createScaledBitmap(edge, 1, 1, true).getPixel(0, 0)
            Color(pixel).copy(alpha = 1f).compositeOverFrame(frame)
        }.getOrNull()
    } ?: frame
    ambientCache = "" to color
    return color
}

private fun Color.compositeOverFrame(frame: Color): Color =
    if (alpha >= 1f) this else androidx.compose.ui.graphics.lerp(frame, copy(alpha = 1f), alpha)

private const val FILL_EDGE_FRACTION = 12
private const val WALLPAPER_FADE_DP = 40f

/**
 * The homepage wallpaper for a header with [KiwiHeader.wallpaper]: the theme's header artwork (all its
 * layers, in desktop's layout across the screen width) starting below the toolbar ([headerPx] from the
 * top of the screen). It is drawn at most one screen pixel per image pixel, so it stays sharp, and
 * shrunk to fit the screen width when its main image is wider. It is shown once (never tiled down the
 * page) and fades into [wallpaperFill]. This scope shows the screen from
 * [originPx] (its distance from the top) downwards, so the homepage and the navigation bar line up.
 * Draws nothing for headers without an image.
 */
fun DrawScope.drawKiwiWallpaper(header: KiwiHeader, originPx: Float, headerPx: Float, windowPx: Float) {
    val main = header.wallpaperLayer() ?: return
    if (header.animated && KiwiHeaderTicker.tick < 0) return
    val images = header.layers.filter { it.pixelSize() != null }
    // Screen px per CSS px: never more than any image has pixels (no enlarging), at most the header's scale.
    var scale = minOf(density * KiwiHeader.DP_PER_CSS_PX, images.minOf { it.imagePxPerCss })
    val mainCssWidth = main.width ?: (main.pixelSize()!!.first / main.imagePxPerCss)
    if (mainCssWidth * scale > size.width) scale = size.width / mainCssWidth
    val boxW = size.width / scale
    // Shown once: tiling a picture down a phone screen repeats it in visible bands (user, 2026-11-11).
    val bandCss = images.maxOf { it.cssHeight(0f) }
    val top = headerPx - originPx
    val fill = header.wallpaperFill()
    drawRect(fill)
    val bandPx = bandCss * scale
    val bottom = top + bandPx
    if (bottom <= 0f || top >= size.height) return
    drawContext.canvas.saveLayer(Rect(0f, top, size.width, bottom), Paint())
    drawRect(header.frame, topLeft = Offset(0f, top), size = Size(size.width, bottom - top))
    translate(top = top) {
        for (layer in header.layers.asReversed()) {
            drawLayer(layer.onceDown(), boxW, bandCss, scale)
        }
    }
    // Fade the artwork's bottom edge into the fill.
    val fade = minOf(bandPx / 3, WALLPAPER_FADE_DP * density)
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Black,
            ((bandPx - fade) / bandPx).coerceIn(0f, 1f) to Color.Black,
            1f to Color.Transparent,
            startY = top,
            endY = bottom,
        ),
        topLeft = Offset(0f, top),
        size = Size(size.width, bandPx),
        blendMode = BlendMode.DstIn,
    )
    drawContext.canvas.restore()
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DrawScope.drawLayer(layer: KiwiHeaderLayer, boxW: Float, boxH: Float, pxPerCss: Float) {
    fun natural(nw: Float, nh: Float): Pair<Float, Float> = when {
        layer.width != null && layer.height != null -> layer.width!! to layer.height!!
        layer.width != null -> layer.width!! to nh * layer.width!! / nw
        layer.height != null -> nw * layer.height!! / nh to layer.height!!
        else -> nw to nh
    }
    val (w, h) = when (layer) {
        is KiwiHeaderLayer.Image -> natural(layer.bitmap.width / layer.pxPerCss, layer.bitmap.height / layer.pxPerCss)
        is KiwiHeaderLayer.Animated -> natural(
            layer.drawable.intrinsicWidth / layer.pxPerCss,
            layer.drawable.intrinsicHeight / layer.pxPerCss,
        )
        is KiwiHeaderLayer.Gradient -> (layer.width ?: boxW) to (layer.height ?: boxH)
    }
    if (w <= 0f || h <= 0f) return
    val (horizontal, vertical) = positionKeywords(layer.alignment)
    val x0 = when (horizontal) {
        "right" -> boxW - w
        "center" -> (boxW - w) / 2
        else -> 0f
    }
    val y0 = when (vertical) {
        "bottom" -> boxH - h
        "center" -> (boxH - h) / 2
        else -> 0f
    }
    val repeatX = layer.tiling == "repeat" || layer.tiling == "repeat-x"
    val repeatY = layer.tiling == "repeat" || layer.tiling == "repeat-y"
    val xs = if (repeatX) (floor(-x0 / w).toInt()..ceil((boxW - x0) / w).toInt()).map { x0 + it * w } else listOf(x0)
    val ys = if (repeatY) (floor(-y0 / h).toInt()..ceil((boxH - y0) / h).toInt()).map { y0 + it * h } else listOf(y0)
    clipRect(0f, 0f, boxW * pxPerCss, boxH * pxPerCss) {
        for (x in xs) for (y in ys) {
            val left = x * pxPerCss
            val top = y * pxPerCss
            val size = Size(w * pxPerCss, h * pxPerCss)
            when (layer) {
                is KiwiHeaderLayer.Image -> drawImage(
                    image = layer.bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(layer.bitmap.width, layer.bitmap.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                )
                is KiwiHeaderLayer.Animated -> drawIntoCanvas { canvas ->
                    layer.drawable.setBounds(
                        left.roundToInt(), top.roundToInt(),
                        (left + size.width).roundToInt(), (top + size.height).roundToInt(),
                    )
                    layer.drawable.draw(canvas.nativeCanvas)
                }
                is KiwiHeaderLayer.Gradient -> drawRect(
                    brush = gradientBrush(layer, left, top, size),
                    topLeft = Offset(left, top),
                    size = size,
                )
            }
        }
    }
}

/**
 * CSS `background-position` keywords to (horizontal, vertical). One keyword sets its axis and centers
 * the other (`top` = center top); with two, the first is horizontal unless it is `top` or `bottom`.
 */
internal fun positionKeywords(alignment: String): Pair<String, String> {
    val words = alignment.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when (words.size) {
        0 -> "left" to "top"
        1 -> when (val w = words[0]) {
            "top", "bottom" -> "center" to w
            "left", "right" -> w to "center"
            else -> "center" to "center"
        }
        else -> if (words[0] == "top" || words[0] == "bottom") words[1] to words[0] else words[0] to words[1]
    }
}

/**
 * CSS linear-gradient geometry: the gradient line passes through the box center along (dx, dy) and is
 * long enough that its 0% and 100% points reach the box corners. Stops outside 0..1 extend the line.
 */
private fun gradientBrush(layer: KiwiHeaderLayer.Gradient, left: Float, top: Float, size: Size): Brush {
    val half = (kotlin.math.abs(size.width * layer.dx) + kotlin.math.abs(size.height * layer.dy)) / 2
    val center = Offset(left + size.width / 2, top + size.height / 2)
    val start = center - Offset(layer.dx * half, layer.dy * half)
    val end = center + Offset(layer.dx * half, layer.dy * half)
    val min = minOf(0f, layer.stops.minOf { it.first })
    val max = maxOf(1f, layer.stops.maxOf { it.first })
    val from = start + (end - start) * min
    val to = start + (end - start) * max
    val stops = layer.stops.map { (pos, color) -> ((pos - min) / (max - min)) to color }.toTypedArray()
    return Brush.linearGradient(colorStops = stops, start = from, end = to)
}
