/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.app.Activity
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Paints the status bar in the theme's `frame` color. Android 15+ draws apps edge to edge and ignores
 * `Window.statusBarColor`, so a non-interactive view sized to the status bar inset sits at the top of
 * the decor view instead. It has zero height whenever the status bar is hidden (full screen video).
 */
internal object KiwiStatusBar {
    private const val TAG = "kiwifox-status-bar"
    private const val EDGE_TO_EDGE_ENFORCED_SDK = 35

    fun apply(activity: Activity, color: Int?) {
        if (Build.VERSION.SDK_INT < EDGE_TO_EDGE_ENFORCED_SDK) return
        val decor = activity.window.decorView as? ViewGroup ?: return
        var view = decor.findViewWithTag<View>(TAG)
        if (color == null) {
            view?.let { decor.removeView(it) }
            return
        }
        if (view == null) {
            view = View(activity).apply {
                tag = TAG
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val top = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
            decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, top, Gravity.TOP))
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val height = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                if (v.layoutParams.height != height) {
                    v.layoutParams = v.layoutParams.apply { this.height = height }
                }
                insets
            }
        }
        view.setBackgroundColor(color)
        view.bringToFront()
        ViewCompat.requestApplyInsets(view)
    }
}
