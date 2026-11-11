/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.compose.base.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * KiwiFox: a desktop theme header (frame color plus `theme_frame` / `additional_backgrounds` layers)
 * drawn behind the Android toolbar, which then paints the theme's [toolbar] color over it, as desktop
 * paints `toolbar` over the header in the navigator toolbox.
 *
 * Geometry follows desktop CSS in CSS px: each layer is placed by its `background-position`
 * ([KiwiHeaderLayer.alignment]) and repeated by its `background-repeat` ([KiwiHeaderLayer.tiling])
 * in a header box as wide as the toolbar whose top is the desktop window top. Desktop's toolbar
 * starts [TOOLBAR_TOP_CSS] below that (under the tab strip), so the Android toolbar shows the same
 * slice of the header that desktop's toolbar shows. One CSS px is [DP_PER_CSS_PX] dp, the scale
 * KiwiFox uses everywhere (32 px desktop bar = 48 dp field).
 *
 * @property frame Desktop `frame`: painted under the layers (and used for the status bar).
 * @property toolbar Desktop `toolbar` with its alpha, painted over the layers by the toolbar itself.
 * @property layers Top-most first, in manifest order.
 * @property statusBar The header's color where desktop's tab strip is, for the status bar (null = [frame]).
 */
data class KiwiHeader(
    val frame: Color,
    val toolbar: Color,
    val layers: List<KiwiHeaderLayer>,
    val statusBar: Color? = null,
) {
    companion object {
        /** Height of desktop Firefox 159's tab strip: the toolbar's top in header coordinates. */
        const val TOOLBAR_TOP_CSS = 44f

        /** Desktop CSS px to Android dp. */
        const val DP_PER_CSS_PX = 1.5f
    }
}

/**
 * One header layer.
 *
 * @property alignment CSS `background-position` keywords, e.g. `right top` (default `left top`).
 * @property tiling CSS `background-repeat`: `no-repeat`, `repeat`, `repeat-x`, `repeat-y`.
 * @property width Layer width in CSS px, or null for natural / full width.
 * @property height Layer height in CSS px, or null for natural / full height.
 */
sealed class KiwiHeaderLayer {
    abstract val alignment: String
    abstract val tiling: String
    abstract val width: Float?
    abstract val height: Float?

    /** An image; [pxPerCss] is image pixels per CSS px (pre-rasterized SVGs are stored at a higher scale). */
    data class Image(
        val bitmap: ImageBitmap,
        val pxPerCss: Float,
        override val alignment: String,
        override val tiling: String,
        override val width: Float? = null,
        override val height: Float? = null,
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
    ) : KiwiHeaderLayer()
}

/** Draws [KiwiHeader] behind [content] when the theme in effect has one; otherwise just [content]. */
@Composable
fun KiwiHeaderBox(content: @Composable () -> Unit) {
    val header = KiwiStyles.chrome()?.header
    if (header == null) {
        content()
        return
    }
    Box(modifier = Modifier.clipToBounds().drawBehind { drawHeader(header) }) { content() }
}

private fun DrawScope.drawHeader(header: KiwiHeader) {
    drawRect(header.frame)
    val pxPerCss = density * KiwiHeader.DP_PER_CSS_PX
    val boxW = size.width / pxPerCss
    val boxH = KiwiHeader.TOOLBAR_TOP_CSS + size.height / pxPerCss
    translate(top = -KiwiHeader.TOOLBAR_TOP_CSS * pxPerCss) {
        for (layer in header.layers.asReversed()) drawLayer(layer, boxW, boxH, pxPerCss)
    }
}

@Suppress("CyclomaticComplexMethod")
private fun DrawScope.drawLayer(layer: KiwiHeaderLayer, boxW: Float, boxH: Float, pxPerCss: Float) {
    val (w, h) = when (layer) {
        is KiwiHeaderLayer.Image -> {
            val nw = layer.bitmap.width / layer.pxPerCss
            val nh = layer.bitmap.height / layer.pxPerCss
            when {
                layer.width != null && layer.height != null -> layer.width to layer.height
                layer.width != null -> layer.width to nh * layer.width / nw
                layer.height != null -> nw * layer.height / nh to layer.height
                else -> nw to nh
            }
        }
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
    val xs = if (repeatX) {
        (floor(-x0 / w).toInt()..ceil((boxW - x0) / w).toInt()).map { x0 + it * w }
    } else {
        listOf(x0)
    }
    val ys = if (repeatY) {
        (floor(-y0 / h).toInt()..ceil((boxH - y0) / h).toInt()).map { y0 + it * h }
    } else {
        listOf(y0)
    }
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
