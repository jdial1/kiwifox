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
 * @property fieldBorderWidth Width of the address field border; 0 draws none.
 * @property fieldBorderLight Border color in light mode ([Color.Unspecified] = outline role).
 * @property fieldBorderDark Border color in dark mode.
 * @property fieldBorderFocusLight Border color while editing, light mode (unspecified = [fieldBorderLight]).
 * @property fieldBorderFocusDark Border color while editing, dark mode.
 * @property cornerScale Multiplier for the Material shape scale (cards, menus, dialogs, buttons).
 * @property fontFamily Font for all Compose text; null keeps the system font.
 */
data class KiwiStyle(
    val fieldCornerRadius: Dp? = null,
    val fieldHeight: Dp = DEFAULT_FIELD_HEIGHT,
    val fieldBorderWidth: Dp = 0.dp,
    val fieldBorderLight: Color = Color.Unspecified,
    val fieldBorderDark: Color = Color.Unspecified,
    val fieldBorderFocusLight: Color = Color.Unspecified,
    val fieldBorderFocusDark: Color = Color.Unspecified,
    val cornerScale: Float = 1f,
    val fontFamily: FontFamily? = null,
) {
    companion object {
        /** Height of the address field in stock Firefox; also the fixed toolbar slot it sits in. */
        val DEFAULT_FIELD_HEIGHT: Dp = 48.dp
    }
}

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

    /** Border for the address field, or null when the theme draws none. */
    @Composable
    fun fieldBorder(focused: Boolean): BorderStroke? {
        val style = current
        if (style.fieldBorderWidth <= 0.dp) return null
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val normal = if (dark) style.fieldBorderDark else style.fieldBorderLight
        val focus = if (dark) style.fieldBorderFocusDark else style.fieldBorderFocusLight
        val color = when {
            focused && focus != Color.Unspecified -> focus
            normal != Color.Unspecified -> normal
            else -> MaterialTheme.colorScheme.outline
        }
        return BorderStroke(style.fieldBorderWidth, color)
    }

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
