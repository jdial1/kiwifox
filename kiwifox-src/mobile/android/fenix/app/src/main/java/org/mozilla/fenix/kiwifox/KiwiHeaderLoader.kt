/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import mozilla.components.compose.base.theme.KiwiHeader
import mozilla.components.compose.base.theme.KiwiHeaderLayer
import mozilla.components.compose.base.theme.KiwiHeaderTicker
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Turns a theme's stored [KiwiTheme.HeaderSpec] into a drawable [KiwiHeader]: decodes images, parses gradients. */
internal object KiwiHeaderLoader {
    /** Decoded headers, so switching modes or re-applying a style does not decode (and animate) twice. */
    private val cache = HashMap<String, KiwiHeader?>()

    fun load(context: Context, theme: KiwiTheme, spec: KiwiTheme.HeaderSpec, frameArgb: Int?): KiwiHeader? {
        val key = "${theme.id}|${theme.builtIn}|${spec.hashCode()}|$frameArgb"
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        return decode(context, theme, spec, frameArgb).also { synchronized(cache) { cache[key] = it } }
    }

    private fun decode(context: Context, theme: KiwiTheme, spec: KiwiTheme.HeaderSpec, frameArgb: Int?): KiwiHeader? {
        val layers = spec.layers.mapNotNull { layer ->
            val (w, h) = parseSize(layer.size)
            when {
                layer.file != null -> {
                    val bytes = KiwiThemeManager.openThemeFile(context, theme, layer.file)?.use { it.readBytes() }
                        ?: return@mapNotNull null
                    imageLayer(bytes, layer, w, h)
                }
                layer.gradient != null -> parseGradient(layer.gradient, layer.alignment, layer.tiling, w, h)
                    ?.copy(windowArea = layer.windowArea)
                else -> null
            }
        }
        if (layers.isEmpty()) return null
        val frame = Color(frameArgb ?: 0xFFFFFFFF.toInt())
        return KiwiHeader(
            frame = frame,
            statusBar = statusBarColor(layers, frame),
            toolbar = argbColor(spec.toolbar),
            field = argbColor(spec.field),
            layers = layers,
        )
    }

    private fun argbColor(argb: Long) = Color(
        red = ((argb shr 16) and 0xFF).toInt(),
        green = ((argb shr 8) and 0xFF).toInt(),
        blue = (argb and 0xFF).toInt(),
        alpha = ((argb shr 24) and 0xFF).toInt(),
    )

    /**
     * A header image: animated APNG (KiwiFox's decoder), GIF or WebP (Android's [ImageDecoder]) as a
     * self-animating drawable ticking through [KiwiHeaderTicker]; anything else as a still bitmap.
     */
    private fun imageLayer(bytes: ByteArray, layer: KiwiTheme.LayerSpec, w: Float?, h: Float?): KiwiHeaderLayer? {
        val animated: Drawable? = ApngDecoder.decode(bytes)?.let { ApngDrawable(it) } ?: decodeAnimated(bytes)
        if (animated != null) {
            animated.callback = KiwiHeaderTicker
            (animated as? Animatable)?.start()
            return KiwiHeaderLayer.Animated(animated, layer.pxPerCss, layer.alignment, layer.tiling, w, h, layer.windowArea)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return KiwiHeaderLayer.Image(bitmap.asImageBitmap(), layer.pxPerCss, layer.alignment, layer.tiling, w, h, layer.windowArea)
    }

    /** GIF / animated WebP through [ImageDecoder] (Android 9+); null for still images or on failure. */
    private fun decodeAnimated(bytes: ByteArray): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val gif = bytes.size > 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF"
        val webp = bytes.size > 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        if (!gif && !webp) return null
        val drawable = runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) }
            .getOrNull() as? AnimatedImageDrawable ?: return null
        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        return drawable
    }

    /** Where desktop Firefox 159's tab strip is measured: [STATUS_BAR_Y_CSS] px down, across the middle. */
    private const val STATUS_BAR_Y_CSS = 6f
    private const val HEADER_WIDTH_CSS = 360f // a 360 dp phone at KiwiHeader.DP_PER_CSS_PX

    /**
     * The header's color where desktop shows its tab strip, for the Android status bar: [frame] with each
     * gradient layer that covers that point composited on top, bottom layer first. Image layers are
     * skipped: desktop themes put them in the corners (Alpenglow's noodles), not across the strip.
     */
    internal fun statusBarColor(layers: List<KiwiHeaderLayer>, frame: Color): Color {
        var color = frame
        for (layer in layers.asReversed()) {
            if (layer !is KiwiHeaderLayer.Gradient) continue
            val w = layer.width ?: HEADER_WIDTH_CSS
            val h = layer.height ?: (STATUS_BAR_Y_CSS * 2)
            val repeatsY = layer.tiling == "repeat" || layer.tiling == "repeat-y"
            val bottomAligned = layer.alignment.lowercase().split(Regex("\\s+")).contains("bottom")
            if (bottomAligned || (!repeatsY && STATUS_BAR_Y_CSS > h)) continue
            val half = (kotlin.math.abs(w * layer.dx) + kotlin.math.abs(h * layer.dy)) / 2
            if (half <= 0f) continue
            // Project the point (middle, y) onto the gradient line through the box center.
            val px = (HEADER_WIDTH_CSS / 2 % w) - w / 2
            val py = (STATUS_BAR_Y_CSS % h) - h / 2
            val t = (px * layer.dx + py * layer.dy + half) / (2 * half)
            color = colorAt(layer.stops, t).compositeOver(color)
        }
        return color
    }

    private fun colorAt(stops: List<Pair<Float, Color>>, t: Float): Color {
        val sorted = stops.sortedBy { it.first }
        if (t <= sorted.first().first) return sorted.first().second
        if (t >= sorted.last().first) return sorted.last().second
        val i = sorted.indexOfLast { it.first <= t }
        val (a, ca) = sorted[i]
        val (b, cb) = sorted[i + 1]
        // CSS gradients interpolate in sRGB (Compose's lerp would use Oklab).
        val f = if (b > a) (t - a) / (b - a) else 0f
        return Color(
            red = ca.red + (cb.red - ca.red) * f,
            green = ca.green + (cb.green - ca.green) * f,
            blue = ca.blue + (cb.blue - ca.blue) * f,
            alpha = ca.alpha + (cb.alpha - ca.alpha) * f,
        )
    }

    /** CSS `background-size`: `auto`, `auto 144px`, `120px 60px`, `120px` -> (width, height) in CSS px. */
    internal fun parseSize(size: String): Pair<Float?, Float?> {
        val parts = size.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        fun px(v: String?) = v?.removeSuffix("px")?.toFloatOrNull()?.takeIf { v.endsWith("px") }
        return when (parts.size) {
            0 -> null to null
            1 -> px(parts[0]) to null
            else -> px(parts[0]) to px(parts[1])
        }
    }

    /**
     * The body of a CSS `linear-gradient(...)`: optional direction (`to bottom`, `to top right`, `90deg`)
     * then color stops with optional percentages; missing positions are spread evenly, as in CSS.
     */
    @Suppress("ReturnCount")
    internal fun parseGradient(body: String, alignment: String, tiling: String, w: Float?, h: Float?): KiwiHeaderLayer.Gradient? {
        val parts = CssThemeExtractor.splitTopLevel(body, ',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        var dx = 0f
        var dy = 1f
        var stopsStart = 0
        val first = parts[0].lowercase()
        if (first.startsWith("to ")) {
            val words = first.removePrefix("to ").split(Regex("\\s+"))
            val x = if ("right" in words) 1f else if ("left" in words) -1f else 0f
            val y = if ("bottom" in words) 1f else if ("top" in words) -1f else 0f
            val len = sqrt(x * x + y * y).takeIf { it > 0f } ?: return null
            dx = x / len
            dy = y / len
            stopsStart = 1
        } else if (Regex("^-?[\\d.]+(deg|turn|rad)$").matches(first)) {
            val n = first.takeWhile { it == '-' || it == '.' || it.isDigit() }.toDouble()
            val radians = when {
                first.endsWith("turn") -> n * 2 * PI
                first.endsWith("rad") -> n
                else -> n * PI / 180
            }
            dx = sin(radians).toFloat()
            dy = (-cos(radians)).toFloat()
            stopsStart = 1
        }
        val raw = parts.drop(stopsStart).map { stop ->
            val m = Regex("^(.*?)\\s+(-?[\\d.]+)%$").find(stop)
            val colorText = m?.groupValues?.get(1) ?: stop
            val pos = m?.groupValues?.get(2)?.toFloatOrNull()?.div(100f)
            val rgba = runCatching { ThemeConverter.parseColor("gradient", colorText) }.getOrNull() ?: return null
            pos to Color(rgba.r, rgba.g, rgba.b, (rgba.a * 255).toInt())
        }
        if (raw.size < 2) return null
        val positions = raw.map { it.first }.toMutableList()
        if (positions.first() == null) positions[0] = 0f
        if (positions.last() == null) positions[positions.size - 1] = 1f
        var i = 0
        while (i < positions.size) {
            if (positions[i] == null) {
                val start = i - 1
                var end = i
                while (positions[end] == null) end++
                val a = positions[start]!!
                val b = positions[end]!!
                for (k in i until end) positions[k] = a + (b - a) * (k - start) / (end - start)
                i = end
            }
            i++
        }
        return KiwiHeaderLayer.Gradient(dx, dy, raw.indices.map { positions[it]!! to raw[it].second }, alignment, tiling, w, h)
    }
}
