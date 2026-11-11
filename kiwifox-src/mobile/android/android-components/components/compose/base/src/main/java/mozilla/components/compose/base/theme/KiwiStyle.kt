/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.compose.base.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

/**
 * KiwiFox: shape, font and address-bar metrics chosen by the selected theme.
 *
 * Desktop Firefox themes change more than colors (corner radius, fonts, bar height, borders).
 * The Android toolbar and the Acorn theme hard-code those, so KiwiFox patches them to read this
 * instead. The default value reproduces stock Firefox exactly.
 *
 * @property fieldCornerRadius Corner radius of the address field; null keeps Firefox's pill.
 * @property fieldHeight Visible height of the address field inside the toolbar's 48 dp slot.
 * @property fieldBorderWidth Width of the address field border; 0 draws none (the focus ring is separate).
 * @property light Chrome colors in light mode.
 * @property dark Chrome colors in dark mode.
 * @property cornerScale Multiplier for the Material shape scale (cards, menus, dialogs, buttons).
 * @property fontFamily Font for all Compose text; null keeps the system font.
 */
data class KiwiStyle(
    val fieldCornerRadius: Dp? = null,
    val fieldHeight: Dp = DEFAULT_FIELD_HEIGHT,
    val fieldBorderWidth: Dp = 0.dp,
    val light: KiwiChromeColors = KiwiChromeColors(),
    val dark: KiwiChromeColors = KiwiChromeColors(),
    val cornerScale: Float = 1f,
    val fontFamily: FontFamily? = null,
) {
    companion object {
        /** Height of the address field in stock Firefox; also the fixed toolbar slot it sits in. */
        val DEFAULT_FIELD_HEIGHT: Dp = 48.dp

        /** Desktop's 2 px focus ring at KiwiFox's desktop-to-Android scale (32 px bar = 48 dp field). */
        val FOCUS_RING_WIDTH: Dp = 3.dp
    }
}

/**
 * Toolbar colors for one mode that have no Material role. [Color.Unspecified] keeps Firefox's own.
 *
 * @property fieldBorder Address field border (desktop `toolbar_field_border`).
 * @property fieldBorderFocus Focus ring while editing (desktop `toolbar_field_border_focus`).
 * @property fieldFocus Address field background while editing (`toolbar_field_focus`).
 * @property fieldTextFocus Address text while editing (`toolbar_field_text_focus`).
 * @property icons Toolbar button icons (`icons`).
 * @property iconsAttention Toolbar icons in their active state (`icons_attention`).
 * @property header Frame color, translucent toolbar color and header images, or null for a flat toolbar.
 */
data class KiwiChromeColors(
    val fieldBorder: Color = Color.Unspecified,
    val fieldBorderFocus: Color = Color.Unspecified,
    val fieldFocus: Color = Color.Unspecified,
    val fieldTextFocus: Color = Color.Unspecified,
    val icons: Color = Color.Unspecified,
    val iconsAttention: Color = Color.Unspecified,
    val header: KiwiHeader? = null,
)

/**
 * Whether a KiwiFox theme is in effect for this composition and in which mode: null when no theme is
 * selected or in private mode (which keeps Firefox's purple). Provided by the patched FirefoxTheme.
 */
val LocalKiwiDark = staticCompositionLocalOf<Boolean?> { null }

/** Holder of the active [KiwiStyle]. Snapshot state: composables reading it recompose on change. */
object KiwiStyles {
    var current: KiwiStyle by mutableStateOf(KiwiStyle())

    /** Shape of the address field: Firefox's pill unless the theme sets a radius. */
    val fieldShape: Shape
        get() = current.fieldCornerRadius?.let { RoundedCornerShape(it) } ?: CircleShape

    /**
     * Vertical inset that shrinks the field inside the toolbar's fixed 48 dp slot. The toolbar's
     * own height stays the same, because Fenix sizes web content and the dynamic toolbar from it.
     */
    val fieldInset: Dp
        get() = ((KiwiStyle.DEFAULT_FIELD_HEIGHT - current.fieldHeight) / 2).coerceAtLeast(0.dp)

    /** Material shapes scaled by [KiwiStyle.cornerScale], or null for Firefox's own. */
    val shapes: Shapes?
        get() {
            val scale = current.cornerScale
            if (scale == 1f) return null
            return Shapes(
                extraSmall = RoundedCornerShape(AcornCorners.extraSmall * scale),
                small = RoundedCornerShape(AcornCorners.small * scale),
                medium = RoundedCornerShape(AcornCorners.medium * scale),
                large = RoundedCornerShape(AcornCorners.large * scale),
                extraLarge = RoundedCornerShape(AcornCorners.extraLarge * scale),
            )
        }

    private var cachedFamily: FontFamily? = null
    private var cachedAcorn: AcornTypography? = null
    private var cachedMaterial: Typography? = null

    /** Acorn typography in the theme font, or null for Firefox's own. */
    val typography: AcornTypography?
        get() {
            val family = current.fontFamily ?: return null
            refreshTypography(family)
            return cachedAcorn
        }

    /** Material typography in the theme font, or null for Firefox's own. */
    val materialTypography: Typography?
        get() {
            val family = current.fontFamily ?: return null
            refreshTypography(family)
            return cachedMaterial
        }

    /** [style] in the theme font. For text styles built inline instead of taken from a typography. */
    fun font(style: TextStyle): TextStyle = current.fontFamily?.let { style.copy(fontFamily = it) } ?: style

    /** The theme's chrome colors for the current mode, or null when no theme applies here. */
    @Composable
    fun chrome(): KiwiChromeColors? {
        val dark = LocalKiwiDark.current ?: return null
        return if (dark) current.dark else current.light
    }

    /**
     * Border for the address field, or null when none is drawn. While editing, desktop always rings
     * the field in `toolbar_field_border_focus`; unfocused, a border needs `toolbar_field_border`.
     */
    @Composable
    fun fieldBorder(focused: Boolean): BorderStroke? {
        val style = current
        val colors = chrome()
        val focus = colors?.fieldBorderFocus?.takeIf { it != Color.Unspecified }
        if (focused && focus != null) {
            return BorderStroke(maxOf(style.fieldBorderWidth, KiwiStyle.FOCUS_RING_WIDTH), focus)
        }
        if (style.fieldBorderWidth <= 0.dp) return null
        val normal = colors?.fieldBorder?.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.outline
        return BorderStroke(style.fieldBorderWidth, normal)
    }

    /** Address field background while editing, or [default]. */
    @Composable
    fun fieldFocus(default: Color): Color = chrome()?.fieldFocus?.takeIf { it != Color.Unspecified } ?: default

    /** Address text color while editing, or [default]. */
    @Composable
    fun fieldTextFocus(default: Color): Color = chrome()?.fieldTextFocus?.takeIf { it != Color.Unspecified } ?: default

    /** Toolbar button icon color, or null for Firefox's own. */
    @Composable
    fun iconColor(): Color? = chrome()?.icons?.takeIf { it != Color.Unspecified }

    /** Address field background: the theme's translucent field color over its header, or [default]. */
    @Composable
    fun fieldColor(default: Color): Color = chrome()?.header?.field ?: default

    /** Toolbar background: the theme's translucent toolbar color over its header, or [default]. */
    @Composable
    fun toolbarColor(default: Color): Color = chrome()?.header?.toolbar ?: default

    @Synchronized
    private fun refreshTypography(family: FontFamily) {
        if (family == cachedFamily && cachedAcorn != null) return
        cachedFamily = family
        cachedAcorn = defaultTypography.withFont(family)
        cachedMaterial = Typography().withFont(family)
    }
}

/** Draws the theme's address field border, if it has one. */
@Composable
fun Modifier.kiwiFieldBorder(focused: Boolean): Modifier =
    KiwiStyles.fieldBorder(focused)?.let { this.border(it, KiwiStyles.fieldShape) } ?: this

private fun AcornTypography.withFont(f: FontFamily) =
    AcornTypography(
        headline5 = headline5.copy(fontFamily = f),
        headline6 = headline6.copy(fontFamily = f),
        headline7 = headline7.copy(fontFamily = f),
        headline8 = headline8.copy(fontFamily = f),
        subtitle1 = subtitle1.copy(fontFamily = f),
        subtitle2 = subtitle2.copy(fontFamily = f),
        body1 = body1.copy(fontFamily = f),
        body2 = body2.copy(fontFamily = f),
        button = button.copy(fontFamily = f),
        caption = caption.copy(fontFamily = f),
        overline = overline.copy(fontFamily = f),
    )

private fun Typography.withFont(f: FontFamily) =
    Typography(
        displayLarge = displayLarge.copy(fontFamily = f),
        displayMedium = displayMedium.copy(fontFamily = f),
        displaySmall = displaySmall.copy(fontFamily = f),
        headlineLarge = headlineLarge.copy(fontFamily = f),
        headlineMedium = headlineMedium.copy(fontFamily = f),
        headlineSmall = headlineSmall.copy(fontFamily = f),
        titleLarge = titleLarge.copy(fontFamily = f),
        titleMedium = titleMedium.copy(fontFamily = f),
        titleSmall = titleSmall.copy(fontFamily = f),
        bodyLarge = bodyLarge.copy(fontFamily = f),
        bodyMedium = bodyMedium.copy(fontFamily = f),
        bodySmall = bodySmall.copy(fontFamily = f),
        labelLarge = labelLarge.copy(fontFamily = f),
        labelMedium = labelMedium.copy(fontFamily = f),
        labelSmall = labelSmall.copy(fontFamily = f),
    )
