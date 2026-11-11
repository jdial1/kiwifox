/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.FirefoxTheme

/**
 * Settings > Customize > Toolbar themes. Laid out like the add-ons manager: an "Installed"
 * section where exactly one entry is active, an "r/FirefoxCSS" section listing [ThemeCatalog]
 * with an Add button per theme, import-from-link / import-file actions, and a Style section
 * that edits the selected theme's radius, font, address bar and scale settings.
 */
class KiwiThemesFragment : Fragment(), SystemInsetsPaddedFragment {
    private var busy by mutableStateOf(false)
    private var catalogBusy by mutableStateOf<String?>(null)
    private var fonts by mutableStateOf<List<String>>(emptyList())

    private val openFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = queryDisplayName(uri) ?: "font.ttf"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw ThemeImportException("could not read the selected file")
                    KiwiThemeManager.importFont(context, bytes, name)
                }
            }
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { file ->
                    fonts = KiwiThemeManager.importedFonts(context)
                    applyStyle(KiwiThemeManager.overrides.copy(fontFile = file))
                },
                onFailure = { e -> status = getString(R.string.kiwifox_import_failed, e.message ?: e.javaClass.simpleName) },
            )
        }
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val context = requireContext().applicationContext
        runImport {
            val name = queryDisplayName(uri) ?: "imported theme"
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw ThemeImportException("could not read the selected file")
            ThemeImporter(requireComponents.core.client).importFile(bytes, name)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        KiwiThemeManager.ensureLoaded(requireContext())
        fonts = KiwiThemeManager.importedFonts(requireContext())
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    KiwiThemesScreen(
                        themes = KiwiThemeManager.themes,
                        selected = KiwiThemeManager.current,
                        status = status,
                        busy = busy,
                        catalogStatus = catalogStatus,
                        catalogBusy = catalogBusy,
                        style = KiwiThemeManager.style,
                        overrides = KiwiThemeManager.overrides,
                        fonts = fonts,
                        onStyleChange = { applyStyle(it) },
                        onImportFont = { openFont.launch(arrayOf("*/*")) },
                        onSelect = {
                            val before = KiwiThemeManager.current
                            selectTheme(it)
                            // Bundled themes restyle the XML screens through a theme overlay, which only
                            // takes effect on a fresh activity.
                            if (before?.builtIn == true || it?.builtIn == true) requireActivity().recreate()
                        },
                        onDelete = {
                            KiwiThemeManager.delete(requireContext(), it)
                            KiwiThemeManager.applyToActivity(requireActivity())
                        },
                        onAddCatalog = { entry -> addFromCatalog(entry) },
                        onImportUrl = { url -> runImport { ThemeImporter(requireComponents.core.client).importUrl(url) } },
                        onImportFile = { openDocument.launch(arrayOf("*/*")) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        showToolbar(getString(R.string.kiwifox_themes_title))
    }

    private fun addFromCatalog(entry: ThemeCatalog.Entry) {
        val context = requireContext().applicationContext
        val client = requireComponents.core.client
        catalogBusy = entry.repoUrl
        catalogStatus.remove(entry.repoUrl)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ThemeImporter(client).importUrl(entry.repoUrl) }
            }
            catalogBusy = null
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { imported ->
                    val stored = KiwiThemeManager.saveImported(context, imported.theme.copy(name = entry.name))
                    if (imported.notes.isNotEmpty()) catalogStatus[entry.repoUrl] = imported.notes.joinToString("\n")
                    selectTheme(stored)
                },
                onFailure = { e -> catalogStatus[entry.repoUrl] = e.message ?: e.javaClass.simpleName },
            )
        }
    }

    private fun runImport(block: suspend () -> ThemeImporter.Imported) {
        val context = requireContext().applicationContext
        busy = true
        status = getString(R.string.kiwifox_import_working)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { block() }
            }
            busy = false
            if (!isAdded) return@launch
            result.fold(
                onSuccess = { imported ->
                    val stored = KiwiThemeManager.saveImported(context, imported.theme)
                    status = buildString {
                        append(getString(R.string.kiwifox_import_done, stored.name))
                        imported.notes.forEach { append("\n• ").append(it) }
                    }
                    selectTheme(stored)
                },
                onFailure = { e ->
                    status = getString(R.string.kiwifox_import_failed, e.message ?: e.javaClass.simpleName)
                },
            )
        }
    }

    /** Selects [theme]; recreates the activity when its text/interface scale or XML font differ. */
    private fun selectTheme(theme: KiwiTheme?) {
        val before = KiwiThemeManager.style
        KiwiThemeManager.select(requireContext(), theme)
        KiwiThemeManager.applyToActivity(requireActivity())
        if (KiwiThemeManager.needsRecreate(before, KiwiThemeManager.style)) requireActivity().recreate()
    }

    /** Stores the user's style overrides for the selected theme and applies them. */
    private fun applyStyle(spec: KiwiStyleSpec) {
        val before = KiwiThemeManager.style
        KiwiThemeManager.setOverrides(requireContext(), spec)
        if (KiwiThemeManager.needsRecreate(before, KiwiThemeManager.style)) requireActivity().recreate()
    }

    private companion object {
        // Kept outside the fragment instance so import results survive the activity being recreated
        // (scale and font changes recreate it).
        var status by mutableStateOf<String?>(null)

        /** Per catalogue entry: the last Add outcome (error text), shown under the row. */
        val catalogStatus = mutableStateMapOf<String, String>()
    }

    private fun queryDisplayName(uri: android.net.Uri): String? =
        requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun KiwiThemesScreen(
    themes: List<KiwiTheme>,
    selected: KiwiTheme?,
    status: String?,
    busy: Boolean,
    catalogStatus: Map<String, String>,
    catalogBusy: String?,
    style: KiwiStyleSpec,
    overrides: KiwiStyleSpec,
    fonts: List<String>,
    onStyleChange: (KiwiStyleSpec) -> Unit,
    onImportFont: () -> Unit,
    onSelect: (KiwiTheme?) -> Unit,
    onDelete: (KiwiTheme) -> Unit,
    onAddCatalog: (ThemeCatalog.Entry) -> Unit,
    onImportUrl: (String) -> Unit,
    onImportFile: () -> Unit,
) {
    var showUrlDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    val installedSources = themes.map { it.source }.toSet()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        item { SectionHeader(stringResource(R.string.kiwifox_section_installed)) }
        item {
            ThemeRow(
                name = stringResource(R.string.kiwifox_theme_default),
                subtitle = stringResource(R.string.kiwifox_theme_default_summary),
                swatch = null,
                onClick = { onSelect(null) },
                trailing = { RadioButton(selected = selected == null, onClick = { onSelect(null) }) },
            )
        }
        items(themes, key = { it.id }) { theme ->
            ThemeRow(
                name = theme.name,
                subtitle = (if (theme.builtIn) stringResource(R.string.kiwifox_theme_bundled) else theme.source) +
                    (if (theme.style.isEmpty) "" else " · " + stringResource(R.string.kiwifox_theme_styled)),
                swatch = theme,
                onClick = { onSelect(theme) },
                trailing = {
                    if (!theme.builtIn) {
                        IconButton(onClick = { onDelete(theme) }) {
                            Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    RadioButton(selected = selected?.id == theme.id, onClick = { onSelect(theme) })
                },
            )
        }

        item {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            StyleSection(
                themeName = selected?.name ?: stringResource(R.string.kiwifox_theme_default),
                style = style,
                overrides = overrides,
                fonts = fonts,
                onChange = onStyleChange,
                onImportFont = onImportFont,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { showUrlDialog = true }, enabled = !busy) {
                    Text(stringResource(R.string.kiwifox_import_link))
                }
                OutlinedButton(onClick = onImportFile, enabled = !busy) {
                    Text(stringResource(R.string.kiwifox_import_file))
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
            if (status != null) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Text(
                text = stringResource(R.string.kiwifox_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        // Two catalogues: AMO static themes (convert completely) and r/FirefoxCSS userChrome.css themes.
        val catalogues = listOf(
            Triple(R.string.kiwifox_section_amo, R.string.kiwifox_amo_summary, ThemeCatalog.AMO_ENTRIES),
            Triple(R.string.kiwifox_section_catalog, R.string.kiwifox_catalog_summary, ThemeCatalog.ENTRIES),
        )
        for ((titleRes, summaryRes, entries) in catalogues) {
            item(key = "header-$titleRes") { SectionHeader(stringResource(titleRes)) }
            item(key = "summary-$titleRes") {
                Text(
                    text = stringResource(summaryRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(entries, key = { it.repoUrl }) { entry ->
                val installed = entry.repoUrl in installedSources
                Column {
                    ThemeRow(
                        name = entry.name,
                        subtitle = if (entry.users > 0) {
                            "${entry.author} · ${"%,d".format(entry.users)} users · ${entry.description}"
                        } else {
                            "${entry.author} · ★ ${entry.stars} · ${entry.description}"
                        },
                        swatch = null,
                        onClick = { if (!installed && catalogBusy == null) onAddCatalog(entry) },
                        trailing = {
                            when {
                                catalogBusy == entry.repoUrl -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                installed -> Text(
                                    text = stringResource(R.string.kiwifox_installed),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                else -> TextButton(onClick = { onAddCatalog(entry) }, enabled = catalogBusy == null) {
                                    Text(stringResource(R.string.kiwifox_add))
                                }
                            }
                        },
                    )
                    catalogStatus[entry.repoUrl]?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp),
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.kiwifox_import_link)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    placeholder = { Text("https://…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUrlDialog = false
                        onImportUrl(url)
                    },
                    enabled = url.isNotBlank(),
                ) { Text(stringResource(R.string.kiwifox_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text(stringResource(R.string.kiwifox_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** One list row in the add-ons manager layout: avatar, title + summary, trailing control. */
@Composable
private fun ThemeRow(
    name: String,
    subtitle: String,
    swatch: KiwiTheme?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(swatch)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        trailing()
    }
}

/** A round icon like the add-on icons: the theme's light bar on top, dark bar below, address field in the middle. */
@Composable
private fun Avatar(theme: KiwiTheme?) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(40.dp)
            .border(1.dp, outline, CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (theme == null) {
            Text("Aa", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val (lightBar, lightField, lightText) = theme.preview(darkMode = false)
            val (darkBar, darkField, darkText) = theme.preview(darkMode = true)
            Column(
                modifier = Modifier.size(width = 30.dp, height = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MiniBar(lightBar, lightField, lightText)
                MiniBar(darkBar, darkField, darkText)
            }
        }
    }
}

@Composable
private fun MiniBar(bar: Color?, field: Color?, text: Color?) {
    Box(
        modifier = Modifier
            .size(width = 30.dp, height = 11.dp)
            .background(bar ?: Color.Transparent, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 6.dp)
                .background(field ?: Color.Transparent, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 3.dp)
                    .size(width = 10.dp, height = 2.dp)
                    .background(text ?: Color.Transparent, RoundedCornerShape(1.dp)),
            )
        }
    }
}

/**
 * Style editor for the selected theme. Every control writes a user override on top of the theme's
 * own style (the values a desktop userChrome.css theme sets); Reset drops the overrides.
 */
@Composable
@Suppress("LongMethod", "LongParameterList")
private fun StyleSection(
    themeName: String,
    style: KiwiStyleSpec,
    overrides: KiwiStyleSpec,
    fonts: List<String>,
    onChange: (KiwiStyleSpec) -> Unit,
    onImportFont: () -> Unit,
) {
    var showFonts by remember { mutableStateOf(false) }
    SectionHeader(stringResource(R.string.kiwifox_style_title, themeName))
    Text(
        text = style.describe().joinToString(" · ").ifEmpty { stringResource(R.string.kiwifox_style_stock) },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    val pill = style.fieldRadius == null || style.fieldRadius < 0f
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.kiwifox_style_pill),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = pill,
            onCheckedChange = { on -> onChange(overrides.copy(fieldRadius = if (on) KiwiStyleSpec.PILL else 8f)) },
        )
    }
    if (!pill) {
        StyleSlider(
            label = stringResource(R.string.kiwifox_style_field_radius),
            value = style.fieldRadius,
            range = 0f..KiwiStyleSpec.MAX_FIELD_RADIUS,
            steps = KiwiStyleSpec.MAX_FIELD_RADIUS.toInt() - 1,
            format = { "${it.toInt()} dp" },
            onDone = { onChange(overrides.copy(fieldRadius = it.toInt().toFloat())) },
        )
    }
    StyleSlider(
        label = stringResource(R.string.kiwifox_style_field_height),
        value = style.fieldHeight ?: KiwiStyleSpec.MAX_FIELD_HEIGHT,
        range = KiwiStyleSpec.MIN_FIELD_HEIGHT..KiwiStyleSpec.MAX_FIELD_HEIGHT,
        steps = (KiwiStyleSpec.MAX_FIELD_HEIGHT - KiwiStyleSpec.MIN_FIELD_HEIGHT).toInt() - 1,
        format = { "${it.toInt()} dp" },
        onDone = { onChange(overrides.copy(fieldHeight = it.toInt().toFloat())) },
    )
    StyleSlider(
        label = stringResource(R.string.kiwifox_style_field_border),
        value = style.fieldBorderWidth ?: 0f,
        range = 0f..KiwiStyleSpec.MAX_BORDER,
        steps = 5,
        format = { "%.1f dp".format(it) },
        onDone = { onChange(overrides.copy(fieldBorderWidth = it)) },
    )
    StyleSlider(
        label = stringResource(R.string.kiwifox_style_corners),
        value = style.cornerScale ?: 1f,
        range = 0f..KiwiStyleSpec.MAX_CORNER_SCALE,
        steps = 11,
        format = { "×%.2f".format(it) },
        onDone = { onChange(overrides.copy(cornerScale = it)) },
    )
    StyleSlider(
        label = stringResource(R.string.kiwifox_style_text_scale),
        value = style.textScale ?: 1f,
        range = KiwiStyleSpec.MIN_TEXT_SCALE..KiwiStyleSpec.MAX_TEXT_SCALE,
        steps = 11,
        format = { "×%.2f".format(it) },
        onDone = { onChange(overrides.copy(textScale = it)) },
    )
    StyleSlider(
        label = stringResource(R.string.kiwifox_style_ui_scale),
        value = style.uiScale ?: 1f,
        range = KiwiStyleSpec.MIN_UI_SCALE..KiwiStyleSpec.MAX_UI_SCALE,
        steps = 8,
        format = { "×%.2f".format(it) },
        onDone = { onChange(overrides.copy(uiScale = it)) },
    )

    val fontLabel = style.fontFile?.takeIf { it != KiwiThemeManager.NO_FONT_FILE }
        ?: style.fontFamily
        ?: stringResource(R.string.kiwifox_style_font_system)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.kiwifox_style_font), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(fontLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { showFonts = true }) { Text(stringResource(R.string.kiwifox_style_font_choose)) }
        OutlinedButton(onClick = onImportFont) { Text(stringResource(R.string.kiwifox_style_font_import)) }
    }
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        TextButton(onClick = { onChange(KiwiStyleSpec.EMPTY) }, enabled = !overrides.isEmpty) {
            Text(stringResource(R.string.kiwifox_style_reset))
        }
    }
    Text(
        text = stringResource(R.string.kiwifox_style_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    if (showFonts) {
        // (label, fontFamily, fontFile); null family + null file = the theme's own font.
        val systemLabel = stringResource(R.string.kiwifox_style_font_system)
        val themeLabel = stringResource(R.string.kiwifox_style_font_theme)
        val choices = buildList {
            add(Triple(themeLabel, null as String?, null as String?))
            add(Triple(systemLabel, "sans-serif", KiwiThemeManager.NO_FONT_FILE))
            KiwiStyleSpec.FONT_FAMILIES.filter { it != "sans-serif" }.forEach { add(Triple(it, it, KiwiThemeManager.NO_FONT_FILE)) }
            fonts.forEach { add(Triple(it, overrides.fontFamily, it)) }
        }
        AlertDialog(
            onDismissRequest = { showFonts = false },
            title = { Text(stringResource(R.string.kiwifox_style_font)) },
            text = {
                LazyColumn {
                    items(choices) { (label, family, file) ->
                        val isSelected = overrides.fontFamily == family && overrides.fontFile == file
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showFonts = false
                                    onChange(overrides.copy(fontFamily = family, fontFile = file))
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = null)
                            Text(label, modifier = Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFonts = false }) { Text(stringResource(R.string.kiwifox_cancel)) }
            },
        )
    }
}

/** A labelled slider that previews while dragging and commits once on release. */
@Composable
@Suppress("LongParameterList")
private fun StyleSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onDone: (Float) -> Unit,
) {
    var current by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { current = value }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(format(current), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = current.coerceIn(range.start, range.endInclusive),
            onValueChange = { current = it },
            valueRange = range,
            steps = steps,
            onValueChangeFinished = { onDone(current) },
        )
    }
}
