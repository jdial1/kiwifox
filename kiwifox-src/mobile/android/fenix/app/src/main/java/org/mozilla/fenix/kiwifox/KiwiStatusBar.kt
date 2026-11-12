/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import mozilla.components.compose.base.theme.KiwiHeader
import mozilla.components.compose.base.theme.KiwiHeaderTicker
import mozilla.components.compose.base.theme.drawKiwiHeader
import mozilla.components.compose.base.theme.drawKiwiWallpaper
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Paints the status bar as desktop's tab strip: the top band of the theme's header (continuing into the
 * toolbar below, which draws the same header from the top of the screen), or a flat color for themes
 * without one. Fenix draws edge to edge (on Android 13+ it pads its content below the status bar), so a
 * non-interactive view sized to the status bar inset at the top of the decor view is what shows there.
 * It has zero height whenever the status bar is hidden (full screen video).
 */
internal object KiwiStatusBar {
    private const val TAG = "kiwifox-status-bar"
    private const val NAV_TAG = "kiwifox-navigation-bar"

    /**
     * Paints the navigation bar (gesture handle or buttons) in [color], or removes the paint when null.
     * Android 15+ ignores `window.navigationBarColor` for edge-to-edge apps, and Fenix's ThemeManager
     * resets it to its own surface color, so like the status bar it is a non-interactive view at the
     * bottom of the decor view, sized to the navigation bar inset (zero when the bar is hidden or at
     * the side in landscape, where the content already reaches the edge).
     */
    fun applyNavigationBar(activity: Activity, color: Int?, wallpaper: KiwiHeader? = null) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        var view = decor.findViewWithTag<NavigationBarView>(NAV_TAG)
        if (color == null) {
            view?.let { decor.removeView(it) }
            return
        }
        if (view == null) {
            view = NavigationBarView(activity).apply { tag = NAV_TAG }
            val bottom = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
            decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bottom, Gravity.BOTTOM))
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val height = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                if (v.layoutParams.height != height) v.layoutParams = v.layoutParams.apply { this.height = height }
                insets
            }
        }
        view.color = color
        view.wallpaper = wallpaper
        view.invalidate()
        view.bringToFront()
        ViewCompat.requestApplyInsets(view)
    }

    fun apply(activity: Activity, color: Int?, header: KiwiHeader?) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        var view = decor.findViewWithTag<HeaderView>(TAG)
        if (color == null && header == null) {
            view?.let { decor.removeView(it) }
            return
        }
        if (view == null) {
            view = HeaderView(activity).apply { tag = TAG }
            val top = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, top, Gravity.TOP))
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val height = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                if (v.layoutParams.height != height) v.layoutParams = v.layoutParams.apply { this.height = height }
                insets
            }
            KiwiHeaderTicker.register(view)
        }
        view.color = color ?: 0
        view.header = header
        view.invalidate()
        view.bringToFront()
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * A background for an XML app bar (the settings screens'): the header from the bar's own position on
     * screen, under the theme's translucent toolbar color, so it continues the status bar's artwork.
     */
    fun headerBackground(view: View, header: KiwiHeader): Drawable {
        KiwiHeaderTicker.register(view)
        return HeaderBackground(view, header)
    }

    private class HeaderBackground(private val view: View, private val header: KiwiHeader) : Drawable() {
        private val scope = CanvasDrawScope()
        private val location = IntArray(2)

        override fun draw(canvas: Canvas) {
            view.getLocationInWindow(location)
            val density = Density(view.context)
            val statusBarPx = ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            val headerPx = statusBarPx + with(density) { KiwiHeader.TOOLBAR_HEIGHT.toPx() }
            val windowPx = view.rootView.height.toFloat()
            val b = bounds
            scope.draw(density, LayoutDirection.Ltr, ComposeCanvas(canvas), Size(b.width().toFloat(), b.height().toFloat())) {
                drawKiwiHeader(header, location[1].toFloat(), headerPx, windowPx)
                drawRect(header.toolbar)
            }
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    /** The navigation bar: a color, or the bottom of the homepage wallpaper. */
    @SuppressLint("ViewConstructor")
    private class NavigationBarView(context: Context) : View(context) {
        var color: Int = 0
        var wallpaper: KiwiHeader? = null
        private val scope = CanvasDrawScope()
        private val location = IntArray(2)

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(color)
            val w = wallpaper ?: return
            getLocationInWindow(location)
            val density = Density(context)
            val statusBarPx = ViewCompat.getRootWindowInsets(this)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            val headerPx = statusBarPx + with(density) { KiwiHeader.TOOLBAR_HEIGHT.toPx() }
            val windowPx = (rootView?.height ?: height).toFloat()
            scope.draw(density, LayoutDirection.Ltr, ComposeCanvas(canvas), Size(width.toFloat(), height.toFloat())) {
                drawKiwiWallpaper(w, location[1].toFloat(), headerPx, windowPx)
            }
        }
    }

    /** Draws [header] with the same code and origin (top of the screen) as the Compose toolbar. */
    @SuppressLint("ViewConstructor")
    private class HeaderView(context: Context) : View(context) {
        var color: Int = 0
        var header: KiwiHeader? = null
        private val scope = CanvasDrawScope()

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            val h = header
            if (h == null) {
                canvas.drawColor(color)
                return
            }
            val density = Density(context)
            val headerPx = height + with(density) { KiwiHeader.TOOLBAR_HEIGHT.toPx() }
            val windowPx = (rootView?.height ?: height).toFloat()
            scope.draw(density, LayoutDirection.Ltr, ComposeCanvas(canvas), Size(width.toFloat(), height.toFloat())) {
                drawKiwiHeader(h, 0f, headerPx, windowPx)
            }
        }
    }
}
