/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.RecyclerView
import java.util.WeakHashMap

/**
 * Themes Fenix's classic settings screens (androidx.preference XML), which no Compose theme reaches and
 * which only bundled themes could recolor through prebuilt resource overlays. For every theme, each
 * preference screen gets the theme's content-page palette (desktop's new tab page colors: `ntp_*`, or
 * the theme's background for KiwiFox's own themes): page background, titles, summaries, icons, and
 * switches / radio buttons / check boxes in the theme's accent.
 */
internal object KiwiSettingsTint : FragmentManager.FragmentLifecycleCallbacks() {
    private const val SECONDARY_TEXT_ALPHA = 0.72f
    private const val OFF_ALPHA = 0.38f
    private val registered = WeakHashMap<Activity, Unit>()
    private val hooked = WeakHashMap<RecyclerView, Unit>()

    private class Palette(val background: Int, val text: Int, val secondary: Int, val accent: Int)

    fun register(activity: Activity) {
        val fa = activity as? FragmentActivity ?: return
        if (registered.containsKey(activity)) return
        registered[activity] = Unit
        fa.supportFragmentManager.registerFragmentLifecycleCallbacks(this, true)
    }

    override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
        tint(f)
    }

    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
        tint(f)
    }

    private fun palette(activity: Activity): Palette? {
        val private = runCatching { (activity as? org.mozilla.fenix.HomeActivity)?.let { it.browsingModeManager.mode.isPrivate } }
            .getOrNull() == true
        if (private) return null
        val roles = KiwiThemeManager.rolesFor(activity) ?: return null
        val background = roles["ntpBackground"] ?: roles["background"] ?: return null
        val text = roles["ntpText"] ?: roles["onSurface"] ?: return null
        val accent = roles["primary"] ?: roles["fieldBorderFocus"] ?: text
        val secondary = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(text, (SECONDARY_TEXT_ALPHA * 255).toInt()), background)
        return Palette(background, text, secondary, accent)
    }

    private fun tint(f: Fragment) {
        if (f !is PreferenceFragmentCompat) return
        runCatching { bindUpdatePreference(f) }
        runCatching { bindHistoryImportPreference(f) }
        runCatching { bindShareImportPreference(f) }
        val root = f.view ?: return
        val palette = palette(f.requireActivity()) ?: return
        root.setBackgroundColor(palette.background)
        val list: RecyclerView = f.listView ?: return
        list.setBackgroundColor(palette.background)
        if (!hooked.containsKey(list)) {
            hooked[list] = Unit
            list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    palette(f.requireActivity())?.let { tintTree(view, it) }
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
        }
        for (i in 0 until list.childCount) tintTree(list.getChildAt(i), palette)
    }

    private fun tintTree(view: View, p: Palette) {
        when (view) {
            is SwitchCompat -> {
                view.thumbTintList = states(p.accent, p.secondary)
                view.trackTintList = states(
                    ColorUtils.setAlphaComponent(p.accent, (0.5f * 255).toInt()),
                    ColorUtils.setAlphaComponent(p.secondary, (OFF_ALPHA * 255).toInt()),
                )
                view.setTextColor(p.text)
            }
            is RadioButton -> {
                view.buttonTintList = states(p.accent, p.secondary)
                view.setTextColor(p.text)
            }
            is CheckBox -> {
                view.buttonTintList = states(p.accent, p.secondary)
                view.setTextColor(p.text)
            }
            is TextView -> view.setTextColor(if (view.id == android.R.id.summary) p.secondary else p.text)
            is ImageView -> if (view.id == android.R.id.icon) view.imageTintList = ColorStateList.valueOf(p.text)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) tintTree(view.getChildAt(i), p)
    }

    private fun states(checked: Int, unchecked: Int) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(checked, unchecked),
    )
}
