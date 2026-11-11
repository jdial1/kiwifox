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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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

    /** The blurred artwork, rendered once per screen size (see [ambient]). */
    @Volatile
    internal var ambientCache: Pair<String, ImageBitmap?>? = null

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
                val onWallpaper = header.wallpaper && header.hasPicture
                val origin = if (top <= statusBarPx + toolbarPx || onWallpaper) top else statusBarPx
                drawKiwiHeader(header, origin, statusBarPx + toolbarPx, maxOf(windowPx, size.height))
            },
    ) { content() }
}

/**
 * Draws [header] into this scope, which shows the band from [originPx] (distance from the top of the
 * screen) downwards. [headerPx] is the header box height (status bar + toolbar), [windowPx] the screen
 * height (for `backgrounds_area: window` layers).
 */
@Suppress("LongParameterList")
fun DrawScope.drawKiwiHeader(
    header: KiwiHeader,
    originPx: Float,
    headerPx: Float,
    windowPx: Float,
    frame: Boolean = true,
    clipToHeader: Boolean = true,
) {
    // Reading the ticker makes this draw pass observe it, so animated headers redraw on each frame.
    if (header.animated && KiwiHeaderTicker.tick < 0) return
    if (frame) drawRect(header.frame)
    val pxPerCss = density * KiwiHeader.DP_PER_CSS_PX
    val boxW = size.width / pxPerCss
    translate(top = -originPx) {
        for (layer in header.layers.asReversed()) {
            val boxH = (if (layer.windowArea) windowPx else headerPx) / pxPerCss
            // Unclipped, the artwork continues below the header box at the same place and scale.
            val clipH = if (clipToHeader) boxH else maxOf(boxH, windowPx / pxPerCss)
            drawLayer(layer, boxW, boxH, pxPerCss, clipH)
        }
    }
}

/**
 * Where the header's artwork ends, in px from the top of the screen: the lowest bottom edge of its
 * image layers (header images are usually taller than the status bar and toolbar they sit behind).
 */
fun KiwiHeader.pictureBottomPx(density: Float, widthPx: Float, headerPx: Float, windowPx: Float): Float {
    val pxPerCss = density * KiwiHeader.DP_PER_CSS_PX
    val boxW = widthPx / pxPerCss
    var bottom = 0f
    for (layer in layers) {
        if (layer is KiwiHeaderLayer.Gradient) continue
        val boxH = (if (layer.windowArea) windowPx else headerPx) / pxPerCss
        val r = layerRect(layer, boxW, boxH) ?: continue
        val repeatY = layer.tiling == "repeat" || layer.tiling == "repeat-y"
        bottom = maxOf(bottom, (if (repeatY) boxH else r[1] + r[3]) * pxPerCss)
    }
    return bottom.coerceAtMost(windowPx)
}

/**
 * The header's artwork blurred: rendered [AMBIENT_DOWNSCALE] times smaller than the screen and drawn
 * back enlarged with bilinear filtering, which keeps its colors and shapes but no detail, so even a
 * 200 px tall header strip can fill a phone screen. Cached per screen size.
 */
fun KiwiHeader.ambient(density: Float, widthPx: Float, headerPx: Float, windowPx: Float): ImageBitmap? {
    val key = "${widthPx.toInt()}x${windowPx.toInt()}@$density/${headerPx.toInt()}"
    ambientCache?.let { (k, bitmap) -> if (k == key) return bitmap }
    val bottom = pictureBottomPx(density, widthPx, headerPx, windowPx)
    val bitmap = if (bottom < 1f || widthPx < 1f) {
        null
    } else {
        runCatching {
            // Render at 1/4, then halve twice with filtering: a cheap blur without RenderEffect (API 31+).
            val first = ImageBitmap(
                (widthPx / AMBIENT_FIRST_PASS).toInt().coerceAtLeast(1),
                (bottom / AMBIENT_FIRST_PASS).toInt().coerceAtLeast(1),
            )
            CanvasDrawScope().draw(
                Density(density / AMBIENT_FIRST_PASS),
                LayoutDirection.Ltr,
                Canvas(first),
                Size(first.width.toFloat(), first.height.toFloat()),
            ) {
                drawKiwiHeader(this@ambient, 0f, headerPx / AMBIENT_FIRST_PASS, windowPx / AMBIENT_FIRST_PASS)
            }
            var small = first.asAndroidBitmap()
            var factor = AMBIENT_FIRST_PASS
            while (factor < AMBIENT_DOWNSCALE) {
                small = android.graphics.Bitmap.createScaledBitmap(
                    small,
                    (small.width / 2).coerceAtLeast(1),
                    (small.height / 2).coerceAtLeast(1),
                    true,
                )
                factor *= 2
            }
            small.asImageBitmap()
        }.getOrNull()
    }
    ambientCache = key to bitmap
    return bitmap
}

private const val AMBIENT_FIRST_PASS = 4f
private const val AMBIENT_DOWNSCALE = 16f
private const val WALLPAPER_FADE_DP = 72f

/**
 * The homepage wallpaper for a header with [KiwiHeader.wallpaper]: the blurred artwork covering the
 * whole screen and, when [sharp], the artwork itself continuing from the toolbar at its own scale and
 * fading out at its bottom edge. This scope shows the screen from [originPx] (its distance from the
 * top) downwards, so the homepage and the navigation bar line up. Nothing for headers without artwork.
 */
@Suppress("LongParameterList")
fun DrawScope.drawKiwiWallpaper(
    header: KiwiHeader,
    originPx: Float,
    headerPx: Float,
    windowPx: Float,
    sharp: Boolean = true,
) {
    if (!header.hasPicture) return
    if (header.animated && KiwiHeaderTicker.tick < 0) return
    drawRect(header.frame)
    header.ambient(density, size.width, headerPx, windowPx)?.let { ambient ->
        // Cover the screen, centered.
        val scale = maxOf(size.width / ambient.width, windowPx / ambient.height)
        val w = ambient.width * scale
        val h = ambient.height * scale
        drawImage(
            image = ambient,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(ambient.width, ambient.height),
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((windowPx - h) / 2 - originPx).roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
    }
    if (!sharp) return
    val bottom = header.pictureBottomPx(density, size.width, headerPx, windowPx) - originPx
    if (bottom <= 0f) return
    val fade = minOf(bottom, WALLPAPER_FADE_DP * density)
    // The sharp artwork, faded out over its last [fade] px by masking it with a vertical gradient.
    drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, bottom), Paint())
    drawKiwiHeader(header, originPx, headerPx, windowPx, frame = false, clipToHeader = false)
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Black,
            ((bottom - fade) / bottom).coerceIn(0f, 1f) to Color.Black,
            1f to Color.Transparent,
            endY = bottom,
        ),
        size = Size(size.width, bottom),
        blendMode = BlendMode.DstIn,
    )
    drawContext.canvas.restore()
}

/** A layer's natural box in CSS px: left, top, width, height (before tiling), or null if empty. */
private fun layerRect(layer: KiwiHeaderLayer, boxW: Float, boxH: Float): FloatArray? {
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
    if (w <= 0f || h <= 0f) return null
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
    return floatArrayOf(x0, y0, w, h)
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun DrawScope.drawLayer(layer: KiwiHeaderLayer, boxW: Float, boxH: Float, pxPerCss: Float, clipH: Float) {
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
    clipRect(0f, 0f, boxW * pxPerCss, clipH * pxPerCss) {
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
