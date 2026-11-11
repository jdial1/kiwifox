/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import mozilla.components.compose.base.theme.KiwiStyles
import mozilla.components.compose.base.theme.LocalKiwiDark

/**
 * Desktop theme surfaces that Fenix draws with the same Material roles as the toolbar: the new tab
 * page (desktop `ntp_*`) and menus / the address bar dropdown (desktop `popup*`). Each scope re-themes
 * its content with those colors; with no theme, in private mode, or for a theme without the roles
 * (KiwiFox's own themes) the content keeps the surrounding colors.
 */
object KiwiSurfaces {
    private const val SECONDARY_TEXT_ALPHA = 0.72f
    private const val HIGHLIGHT_RIPPLE_ALPHA = 0.6f

    /** Provides the KiwiFox mode to everything under FirefoxTheme (called by the patched FirefoxTheme). */
    @Composable
    fun ProvideMode(dark: Boolean?, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalKiwiDark provides dark, content = content)
    }

    @Composable
    private fun roles(): Map<String, Int>? {
        val dark = LocalKiwiDark.current ?: return null
        val theme = KiwiThemeManager.current ?: return null
        return if (dark) theme.dark else theme.light
    }

    /** The status bar color KiwiThemeManager.applyToActivity uses: desktop `frame`, or [default]. */
    @Composable
    fun statusBarColor(default: Color): Color =
        KiwiStyles.chrome()?.header?.statusBar ?: roles()?.get("frame")?.let(::Color) ?: default

    /** The new tab page background, or null to keep Firefox's. */
    @Composable
    fun homeBackground(): Color? = roles()?.get("ntpBackground")?.let(::Color)

    /** Paints the new tab page background behind the homepage (under any user wallpaper). */
    @Composable
    fun HomeBackground() {
        val color = homeBackground() ?: return
        Box(modifier = Modifier.fillMaxSize().background(color))
    }

    /** Homepage content in the new tab page colors: `ntp_background`, `ntp_text`, `ntp_card_background`. */
    @Composable
    fun HomeScope(content: @Composable () -> Unit) {
        val r = roles()
        val background = r?.get("ntpBackground")?.let(::Color)
        if (r == null || background == null) {
            content()
            return
        }
        val text = Color(r.getValue("ntpText"))
        val card = r["ntpCard"]?.let(::Color) ?: background
        // Fenix's homepage cards, tiles, pill buttons and search bar use surfaceBright and the
        // surfaceContainer roles; desktop draws those (top sites, search box) in ntp_card_background.
        val scheme = MaterialTheme.colorScheme.copy(
            background = background,
            surface = background,
            surfaceDim = background,
            surfaceBright = card,
            surfaceContainerLowest = card,
            surfaceContainerLow = card,
            surfaceContainer = card,
            surfaceContainerHigh = card,
            surfaceContainerHighest = card,
            onBackground = text,
            onSurface = text,
            onSurfaceVariant = text.copy(alpha = SECONDARY_TEXT_ALPHA).compositeOver(background),
        )
        MaterialTheme(colorScheme = scheme, content = content)
    }

    /** The menu / dropdown background, or [default]. */
    @Composable
    fun popupBackground(default: Color): Color = roles()?.get("popup")?.let(::Color) ?: default

    /**
     * Menus and the address bar dropdown in the theme's popup colors: `popup`, `popup_text`,
     * `popup_border`, and `popup_highlight` for grouped rows and for the pressed / hovered / focused
     * state of items (desktop shows it on the hovered or keyboard-selected row).
     */
    @Composable
    fun PopupScope(content: @Composable () -> Unit) {
        val r = roles()
        val popup = r?.get("popup")?.let(::Color)
        if (r == null || popup == null) {
            content()
            return
        }
        val text = Color(r.getValue("popupText"))
        val highlight = r["popupHighlight"]?.let(::Color) ?: popup
        val border = r["popupBorder"]?.let(::Color)
        val base = MaterialTheme.colorScheme
        val scheme = base.copy(
            background = popup,
            surface = popup,
            surfaceBright = popup,
            surfaceDim = popup,
            surfaceContainerLowest = popup,
            surfaceContainerLow = popup,
            surfaceContainer = popup,
            surfaceContainerHigh = highlight,
            surfaceContainerHighest = highlight,
            onBackground = text,
            onSurface = text,
            onSurfaceVariant = text.copy(alpha = SECONDARY_TEXT_ALPHA).compositeOver(popup),
            outline = border ?: base.outline,
            outlineVariant = border ?: base.outlineVariant,
        )
        val ripple = RippleConfiguration(
            color = highlight,
            rippleAlpha = RippleAlpha(
                draggedAlpha = HIGHLIGHT_RIPPLE_ALPHA,
                focusedAlpha = HIGHLIGHT_RIPPLE_ALPHA,
                hoveredAlpha = HIGHLIGHT_RIPPLE_ALPHA,
                pressedAlpha = HIGHLIGHT_RIPPLE_ALPHA,
            ),
        )
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalRippleConfiguration provides ripple, content = content)
        }
    }
}
