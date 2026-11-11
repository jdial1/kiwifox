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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import mozilla.components.compose.base.theme.Theme
import mozilla.components.compose.browser.awesomebar.AwesomeBar
import mozilla.components.compose.browser.toolbar.BrowserToolbar
import mozilla.components.compose.browser.toolbar.concept.Action.ActionButtonRes
import mozilla.components.compose.browser.toolbar.concept.Action.SearchSelectorAction
import mozilla.components.compose.browser.toolbar.concept.Action.SearchSelectorAction.ContentDescription.StringResContentDescription
import mozilla.components.compose.browser.toolbar.concept.Action.SearchSelectorAction.Icon.DrawableResIcon
import mozilla.components.compose.browser.toolbar.concept.Action.TabCounterAction
import mozilla.components.compose.browser.toolbar.concept.PageOrigin
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarInteraction.BrowserToolbarEvent
import mozilla.components.compose.browser.toolbar.ui.BrowserToolbarQuery
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarState
import mozilla.components.compose.browser.toolbar.store.BrowserToolbarStore
import mozilla.components.compose.browser.toolbar.store.DisplayState
import mozilla.components.compose.browser.toolbar.store.EditState
import mozilla.components.compose.browser.toolbar.store.Mode
import mozilla.components.concept.awesomebar.AwesomeBar as AwesomeBarConcept
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mozilla.fenix.components.menu.compose.MenuDialogBottomSheet
import org.mozilla.fenix.components.menu.compose.MenuHandleState
import org.mozilla.fenix.theme.FirefoxTheme
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import mozilla.components.ui.icons.R as iconsR

/**
 * Renders every theme (Firefox default, the bundled ones, and with KIWIFOX_CATALOG_DIR /
 * KIWIFOX_AMO_DIR set the catalogue themes as the importer converts them) in light and dark mode and
 * writes one PNG per theme and mode to KIWIFOX_SHOT_DIR. Skipped when KIWIFOX_SHOT_DIR is unset.
 *
 * The screen is built from the real, patched components wherever they can run off-device: Fenix's
 * FirefoxTheme, android-components' BrowserToolbar (display and edit mode, with the header, icon and
 * focus patches), AwesomeBar (search suggestions), Fenix's MenuDialogBottomSheet, and the KiwiSurfaces
 * scopes and backgrounds Fenix's HomeFragment / Homepage / AwesomeBarComposable use. The homepage
 * content itself is a stand-in using the roles Fenix's homepage cards use (surfaceBright,
 * surfaceContainerHighest, surfaceContainerLowest), and the status bar is a box in the color
 * KiwiThemeManager.applyToActivity paints it.
 *
 * Text and interface scale normally come from the activity configuration
 * (KiwiThemeManager.wrapBaseContext); here they are applied to LocalDensity the same way.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h1100dp-xxhdpi")
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
        shots.addAll(amoThemes())

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
                // Suggestions are fetched on a background dispatcher; give them a moment to arrive.
                repeat(SETTLE_ROUNDS) {
                    Thread.sleep(SETTLE_MS)
                    compose.waitForIdle()
                }
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val file = File(outDir, "${shot.label}-${if (mode) "dark" else "light"}.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                println("SHOT ${file.name} ${bitmap.width}x${bitmap.height} style=${KiwiThemeManager.style.toJson()}")
            }
        }
        KiwiThemeManager.select(context, null)
    }

    /** [ThemeCatalog.AMO_ENTRIES] through the app's importer, from KIWIFOX_AMO_DIR/<slug>.xpi. */
    private fun amoThemes(): List<Shot> {
        val dir = System.getenv("KIWIFOX_AMO_DIR")?.let(::File) ?: return emptyList()
        val offline = object : mozilla.components.concept.fetch.Client() {
            override fun fetch(request: mozilla.components.concept.fetch.Request): mozilla.components.concept.fetch.Response =
                throw java.io.IOException("offline test")
        }
        return ThemeCatalog.AMO_ENTRIES.mapNotNull { entry ->
            val slug = entry.repoUrl.trimEnd('/').substringAfterLast('/')
            val xpi = File(dir, "$slug.xpi").takeIf { it.isFile } ?: return@mapNotNull null
            val theme = ThemeImporter(offline).importFile(xpi.readBytes(), xpi.name).theme
            Shot("amo-" + slug.trimEnd('_'), theme.copy(id = "amo-$slug", name = entry.name))
        }
    }

    /** Catalogue repos converted exactly as ThemeImporter.fromCss does (colors + layout). */
    private fun catalogueThemes(): List<Shot> {
        return CatalogTestRepos.repos().mapNotNull { repo ->
            val (css, _) = CatalogTestRepos.css(repo)
            val userJs = CatalogTestRepos.userJs(repo)
            runCatching {
                val (manifest, _) = CssThemeExtractor.toManifest(css, repo.name)
                val converted = ThemeConverter.convert(manifest, repo.name)
                val layout = CssStyleExtractor.extract(css, userJs).style
                Shot("catalog-" + repo.name.lowercase(), converted.theme.copy(id = "catalog-${repo.name}", style = converted.theme.style.overlay(layout)))
            }.getOrNull()
        }
    }

    private companion object {
        const val SETTLE_ROUNDS = 4
        const val SETTLE_MS = 150L
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

private val displayState = DisplayState(
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
    pageOrigin = PageOrigin(title = null, url = "mozilla.org/firefox/new", onClick = noEvent),
    pageActionsEnd = listOf(
        ActionButtonRes(drawableResId = iconsR.drawable.mozac_ic_arrow_clockwise_24, contentDescription = android.R.string.untitled, onClick = noEvent),
    ),
    browserActionsEnd = listOf(
        TabCounterAction(count = 3, contentDescription = "3 tabs", showPrivacyMask = false, onClick = noEvent),
        ActionButtonRes(drawableResId = iconsR.drawable.mozac_ic_ellipsis_vertical_24, contentDescription = android.R.string.untitled, onClick = noEvent),
    ),
)

/** Fixed suggestions, as the history / search providers would return them. */
private object SampleSuggestions : AwesomeBarConcept.SuggestionProvider {
    override val id = "kiwifox-sample"
    override suspend fun onInputChanged(text: String): List<AwesomeBarConcept.SuggestionItem> = listOf(
        AwesomeBarConcept.Suggestion(provider = this, id = "1", title = "Firefox themes", description = "addons.mozilla.org/firefox/themes", score = 3),
        AwesomeBarConcept.Suggestion(provider = this, id = "2", title = "r/FirefoxCSS", description = "reddit.com/r/FirefoxCSS", score = 2),
        AwesomeBarConcept.Suggestion(provider = this, id = "3", title = "firefox themes dark", score = 1),
    )
}

@Composable
@Suppress("LongMethod")
private fun Sample(name: String, dark: Boolean) {
    val colors = MaterialTheme.colorScheme
    val displayStore = remember { BrowserToolbarStore(BrowserToolbarState(mode = Mode.DISPLAY, displayState = displayState)) }
    val editStore = remember {
        BrowserToolbarStore(BrowserToolbarState(mode = Mode.EDIT, editState = EditState(query = BrowserToolbarQuery("firefox themes"))))
    }
    Column(modifier = Modifier.width(360.dp).background(colors.background)) {
        // Status bar: KiwiThemeManager.applyToActivity paints it in the theme's frame color.
        Box(
            Modifier.fillMaxWidth().height(24.dp).background(KiwiSurfaces.statusBarColor(colors.surface)).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("$name · ${if (dark) "dark" else "light"}", style = FirefoxTheme.typography.caption, color = colors.onSurface)
        }
        // Home: HomeFragment paints the new tab background; Homepage content runs in HomeScope.
        BrowserToolbar(store = displayStore)
        Box(modifier = Modifier.fillMaxWidth().height(250.dp).background(colors.background)) {
            KiwiSurfaces.HomeBackground()
            KiwiSurfaces.HomeScope { HomeStandIn() }
        }
        // Address bar while typing, with the suggestions list (AwesomeBarComposable).
        BrowserToolbar(store = editStore)
        Box(
            modifier = Modifier.fillMaxWidth().height(200.dp)
                .background(KiwiSurfaces.popupBackground(MaterialTheme.colorScheme.surface)),
        ) {
            KiwiSurfaces.PopupScope {
                AwesomeBar(
                    text = "firefox themes",
                    providers = listOf(SampleSuggestions),
                    onSuggestionClicked = {},
                    onAutoComplete = {},
                    onRemoveClicked = {},
                )
            }
        }
        // Main menu sheet: Fenix's MenuDialogBottomSheet (its KiwiFox wrapper applies the popup colors).
        MenuDialogBottomSheet(
            onRequestDismiss = {},
            menuHandleState = MenuHandleState(contentDescription = "menu"),
            snackbarHostState = remember { SnackbarHostState() },
        ) { MenuStandIn() }
    }
}

/** Homepage stand-in: the roles Fenix's homepage sections use. */
@Composable
private fun HomeStandIn() {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(40.dp).background(colors.surfaceContainerLowest, MaterialTheme.shapes.extraLarge))
        Text("Jump back in", style = FirefoxTheme.typography.headline7, color = colors.onSurface)
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = colors.surfaceBright),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(colors.surfaceContainerHighest, MaterialTheme.shapes.small))
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("r/FirefoxCSS: share your setup", style = FirefoxTheme.typography.subtitle1, color = colors.onSurface)
                    Text("reddit.com · 2 hours ago", style = FirefoxTheme.typography.caption, color = colors.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Bookmarks", "History", "Customize").forEach { label ->
                Surface(color = colors.surfaceBright, shape = MaterialTheme.shapes.extraLarge) {
                    Text(label, color = colors.onSurface, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

/** Main menu stand-in: grouped rows on surfaceContainerHigh, as Fenix's MainMenu draws them. */
@Composable
private fun MenuStandIn() {
    val colors = MaterialTheme.colorScheme
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
