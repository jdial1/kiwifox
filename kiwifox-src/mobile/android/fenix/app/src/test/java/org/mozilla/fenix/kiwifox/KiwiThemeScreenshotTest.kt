/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.components.compose.browser.toolbar.BrowserDisplayToolbar
import mozilla.components.compose.browser.toolbar.BrowserEditToolbar
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButtonRes
import mozilla.components.compose.browser.toolbar.concept.Action.SearchSelectorAction
import mozilla.components.compose.browser.toolbar.concept.Action.SearchSelectorAction.ContentDescription.StringResContentDescription
import mozilla.components.compose.browser.toolbar.concept.Action.SearchSelectorAction.Icon.DrawableResIcon
import mozilla.components.compose.browser.toolbar.concept.Action.TabCounterAction
import mozilla.components.compose.browser.toolbar.concept.PageOrigin
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.store.ToolbarGravity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.theme.FirefoxTheme
import mozilla.components.compose.base.theme.Theme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import mozilla.components.ui.icons.R as iconsR

/**
 * Renders every theme (Firefox default, the bundled ones, and with KIWIFOX_CATALOG_DIR set the
 * catalogue themes as the importer converts them) in light and dark mode with the real
 * android-components toolbar, the patched AcornTheme and Material surfaces, and writes one PNG
 * per theme and mode to KIWIFOX_SHOT_DIR. Skipped when KIWIFOX_SHOT_DIR is unset.
 *
 * Text and interface scale normally come from the activity configuration
 * (KiwiThemeManager.wrapBaseContext); here they are applied to LocalDensity the same way.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h900dp-xxhdpi")
class KiwiThemeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private class Shot(val label: String, val theme: KiwiTheme?)

    @Test
    fun `render every theme`() {
        val outDir = System.getenv("KIWIFOX_SHOT_DIR")?.let(::File) ?: return
        outDir.mkdirs()
        val context = ApplicationProvider.getApplicationContext<Context>()
        KiwiThemeManager.ensureLoaded(context)

        val shots = ArrayList<Shot>()
        shots.add(Shot("firefox-default", null))
        KiwiThemeManager.themes.forEach { shots.add(Shot("bundled-" + it.id.removePrefix("bundled-"), it)) }
        shots.addAll(catalogueThemes())

        var current by mutableStateOf(shots.first())
        var dark by mutableStateOf(false)
        compose.setContent {
            FirefoxTheme(theme = if (dark) Theme.Dark else Theme.Light) {
                ScaledByStyle { Sample(current.theme?.name ?: "Firefox default", dark) }
            }
        }
        for (shot in shots) {
            for (mode in listOf(false, true)) {
                compose.runOnIdle {
                    KiwiThemeManager.select(context, shot.theme)
                    current = shot
                    dark = mode
                }
                compose.waitForIdle()
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val file = File(outDir, "${shot.label}-${if (mode) "dark" else "light"}.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                println("SHOT ${file.name} ${bitmap.width}x${bitmap.height} style=${KiwiThemeManager.style.toJson()}")
            }
        }
        KiwiThemeManager.select(context, null)
    }

    /** Catalogue repos converted exactly as ThemeImporter.fromCss does (colors + layout). */
    private fun catalogueThemes(): List<Shot> {
        val dir = System.getenv("KIWIFOX_CATALOG_DIR") ?: return emptyList()
        val repos = File(dir).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return repos.mapNotNull { repo ->
            val notGit = { f: File -> !f.path.contains("${File.separator}.git${File.separator}") }
            val css = StringBuilder()
            repo.walkTopDown().filter { it.isFile && it.extension == "css" && notGit(it) }
                .sortedBy { it.relativeTo(repo).path.replace('\\', '/') }
                .sortedBy { if (it.name == "userChrome.css") 0 else 1 }
                .take(40)
                .forEach { if (css.length < 2 * 1024 * 1024) css.append("\n").append(it.readText()) }
            val userJs = repo.walkTopDown().filter { it.isFile && it.name == "user.js" && notGit(it) }
                .minByOrNull { it.relativeTo(repo).path.length }?.readText()
            runCatching {
                val (manifest, _) = CssThemeExtractor.toManifest(css.toString(), repo.name)
                val converted = ThemeConverter.convert(manifest, repo.name)
                val layout = CssStyleExtractor.extract(css.toString(), userJs).style
                Shot("catalog-" + repo.name.lowercase(), converted.theme.copy(id = "catalog-${repo.name}", style = converted.theme.style.overlay(layout)))
            }.getOrNull()
        }
    }
}

/** Mirrors KiwiThemeManager.wrapBaseContext: interface scale multiplies density, text scale the font scale. */
@Composable
private fun ScaledByStyle(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val style = KiwiThemeManager.style
    val ui = style.uiScale ?: 1f
    val text = style.textScale ?: 1f
    CompositionLocalProvider(LocalDensity provides Density(base.density * ui, base.fontScale * text), content = content)
}

private val noEvent = object : BrowserToolbarEvent {}

@Composable
@Suppress("LongMethod")
private fun Sample(name: String, dark: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.width(360.dp).background(colors.background)) {
        // Status bar stand-in, tinted like KiwiThemeManager.applyToActivity does.
        Box(Modifier.fillMaxWidth().height(24.dp).background(colors.surface).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text("$name · ${if (dark) "dark" else "light"}", style = FirefoxTheme.typography.caption, color = colors.onSurface)
        }
        BrowserDisplayToolbar(
            pageOrigin = PageOrigin(title = null, url = "mozilla.org/firefox/new", onClick = noEvent),
            gravity = ToolbarGravity.Top,
            progressBarConfig = null,
            browserActionsStart = listOf(
                ActionButtonRes(drawableResId = iconsR.drawable.mozac_ic_home_24, contentDescription = android.R.string.untitled, onClick = noEvent),
            ),
            pageActionsStart = listOf(
                SearchSelectorAction(
                    icon = DrawableResIcon(iconsR.drawable.mozac_ic_search_24),
                    contentDescription = StringResContentDescription(resourceId = android.R.string.untitled),
                    menu = { emptyList() },
                    onClick = null,
                ),
            ),
            pageActionsEnd = listOf(
                ActionButtonRes(drawableResId = iconsR.drawable.mozac_ic_arrow_clockwise_24, contentDescription = android.R.string.untitled, onClick = noEvent),
            ),
            browserActionsEnd = listOf(
                TabCounterAction(count = 3, contentDescription = "3 tabs", showPrivacyMask = false, onClick = noEvent),
                ActionButtonRes(drawableResId = iconsR.drawable.mozac_ic_ellipsis_vertical_24, contentDescription = android.R.string.untitled, onClick = noEvent),
            ),
            onInteraction = {},
        )
        BrowserEditToolbar(query = "firefoxcss themes", hint = "Search or enter address", onInteraction = {})

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Jump back in", style = FirefoxTheme.typography.headline7, color = colors.onSurface)
            Card(
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(colors.primary, MaterialTheme.shapes.small))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("r/FirefoxCSS: share your setup", style = FirefoxTheme.typography.subtitle1, color = colors.onSurface)
                        Text("reddit.com · 2 hours ago", style = FirefoxTheme.typography.caption, color = colors.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("Bookmarks") }, shape = MaterialTheme.shapes.small)
                AssistChip(onClick = {}, label = { Text("History") }, shape = MaterialTheme.shapes.small)
                OutlinedButton(onClick = {}, shape = MaterialTheme.shapes.medium) { Text("Customize") }
            }
        }

        // Main menu stand-in: the bottom sheet surface and rows Fenix's menu uses.
        Surface(
            color = colors.surface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Box(Modifier.padding(vertical = 4.dp).align(Alignment.CenterHorizontally).size(width = 32.dp, height = 4.dp)
                    .background(colors.onSurfaceVariant, MaterialTheme.shapes.extraSmall))
                Column(
                    modifier = Modifier.padding(12.dp).fillMaxWidth().background(colors.surfaceContainerHigh, MaterialTheme.shapes.large)
                        .padding(vertical = 4.dp),
                ) {
                    listOf("New private tab", "Bookmarks", "History", "Downloads").forEachIndexed { i, item ->
                        if (i > 0) HorizontalDivider(color = colors.outlineVariant)
                        Text(item, style = FirefoxTheme.typography.body1, color = colors.onSurface, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
