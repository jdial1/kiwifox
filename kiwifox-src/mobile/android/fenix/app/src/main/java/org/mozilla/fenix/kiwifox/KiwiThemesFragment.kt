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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
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

/** Settings screen: pick a KiwiFox toolbar theme, import one from a link or a file, delete imported ones. */
class KiwiThemesFragment : Fragment(), SystemInsetsPaddedFragment {
    private var status by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)

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
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    KiwiThemesScreen(
                        themes = KiwiThemeManager.themes,
                        selected = KiwiThemeManager.current,
                        status = status,
                        busy = busy,
                        onSelect = { KiwiThemeManager.select(requireContext(), it) },
                        onDelete = { KiwiThemeManager.delete(requireContext(), it) },
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
                    KiwiThemeManager.select(context, stored)
                    status = buildString {
                        append(getString(R.string.kiwifox_import_done, stored.name))
                        imported.notes.forEach { append("\n• ").append(it) }
                    }
                },
                onFailure = { e ->
                    status = getString(R.string.kiwifox_import_failed, e.message ?: e.javaClass.simpleName)
                },
            )
        }
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
    onSelect: (KiwiTheme?) -> Unit,
    onDelete: (KiwiTheme) -> Unit,
    onImportUrl: (String) -> Unit,
    onImportFile: () -> Unit,
) {
    var showUrlDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                ThemeRow(
                    name = stringResource(R.string.kiwifox_theme_default),
                    subtitle = stringResource(R.string.kiwifox_theme_default_summary),
                    selected = selected == null,
                    swatch = null,
                    onClick = { onSelect(null) },
                    onDelete = null,
                )
            }
            items(themes, key = { it.id }) { theme ->
                ThemeRow(
                    name = theme.name,
                    subtitle = if (theme.builtIn) stringResource(R.string.kiwifox_theme_bundled) else theme.source,
                    selected = selected?.id == theme.id,
                    swatch = theme,
                    onClick = { onSelect(theme) },
                    onDelete = if (theme.builtIn) null else ({ onDelete(theme) }),
                )
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showUrlDialog = true }, enabled = !busy) {
                    Text(stringResource(R.string.kiwifox_import_link))
                }
                OutlinedButton(onClick = onImportFile, enabled = !busy) {
                    Text(stringResource(R.string.kiwifox_import_file))
                }
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp).align(Alignment.CenterVertically))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.kiwifox_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != null) {
                Spacer(Modifier.height(8.dp))
                Text(text = status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
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
@Suppress("LongParameterList")
private fun ThemeRow(
    name: String,
    subtitle: String,
    selected: Boolean,
    swatch: KiwiTheme?,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (swatch != null) {
            Swatch(swatch, darkMode = false)
            Spacer(Modifier.width(6.dp))
            Swatch(swatch, darkMode = true)
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A miniature toolbar: the bar with the address field and a text stroke on it. */
@Composable
private fun Swatch(theme: KiwiTheme, darkMode: Boolean) {
    val (bar, field, text) = theme.preview(darkMode)
    val outline = MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 28.dp)
            .border(1.dp, outline, RoundedCornerShape(4.dp))
            .background(bar ?: Color.Transparent, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 32.dp, height = 14.dp)
                .background(field ?: Color.Transparent, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 5.dp)
                    .size(width = 14.dp, height = 3.dp)
                    .background(text ?: Color.Transparent, RoundedCornerShape(2.dp)),
            )
        }
    }
}
