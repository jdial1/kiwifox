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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.requireComponents
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.FirefoxTheme

private const val SCREEN_TITLE = "Import browsing history"

/**
 * Settings > Import browsing history: explains where to get a history file from Chrome (Google Takeout) or a
 * desktop Chromium browser, picks it, and shows [KiwiHistoryImporter]'s progress and result.
 */
class KiwiHistoryImportFragment : Fragment(), SystemInsetsPaddedFragment {

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        KiwiHistoryImporter.start(requireContext(), uri, requireComponents.core.historyStorage)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                FirefoxTheme {
                    HistoryImportScreen(
                        state = KiwiHistoryImporter.state,
                        onChoose = { openDocument.launch(arrayOf("*/*")) },
                        onStop = { KiwiHistoryImporter.stop() },
                    )
                }
            }
        }

    override fun onResume() {
        super.onResume()
        showToolbar(SCREEN_TITLE)
    }
}

/** Wires the Settings entry (added to preferences.xml by apply_kiwifox.py) to the import screen. */
internal fun bindHistoryImportPreference(fragment: PreferenceFragmentCompat) {
    val preference = fragment.findPreference<Preference>(KiwiHistoryImporter.SETTINGS_KEY) ?: return
    preference.setOnPreferenceClickListener {
        fragment.findNavController().navigate(R.id.kiwiHistoryImportFragment)
        true
    }
}

@Composable
private fun HistoryImportScreen(state: KiwiHistoryImporter.State, onChoose: () -> Unit, onStop: () -> Unit) {
    val running = state is KiwiHistoryImporter.State.Running
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Body(
            "Google does not let other browsers sign in to Chrome sync, so history comes over as a file. " +
                "Imported visits keep their dates, show up in History and address bar suggestions, and sync " +
                "with your Mozilla account like any other visit.",
        )

        Heading("From your Google account")
        Body(
            "1. Open takeout.google.com, deselect all, then tick Chrome (its options let you keep only History).\n" +
                "2. Create the export and download the .zip to this phone.\n" +
                "3. Choose the .zip below, or the History.json inside it.",
        )

        Heading("From a computer browser")
        Body(
            "Chrome, Edge, Brave, Opera and Vivaldi keep a file named History (no extension) in their profile " +
                "folder, for example AppData\\Local\\Google\\Chrome\\User Data\\Default on Windows. Close the " +
                "browser, copy that file to this phone and choose it below. It holds that profile's last 90 days.",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onChoose, enabled = !running) { Text("Choose file") }
            if (running) {
                OutlinedButton(onClick = onStop) { Text("Stop") }
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }

        when (state) {
            KiwiHistoryImporter.State.Idle -> Unit
            is KiwiHistoryImporter.State.Running ->
                Status("Importing… ${state.read} visits read, ${state.added} added. You can leave this screen.")
            is KiwiHistoryImporter.State.Done -> Status(describe(state.summary))
            is KiwiHistoryImporter.State.Failed -> Status("Import failed: ${state.message}")
        }

        Heading("Passwords and bookmarks")
        Body(
            "Passwords: export a .csv from the other browser (Chrome: passwords.google.com > Settings > Export), " +
                "then Settings > Passwords > menu > Import from file. Delete the .csv afterwards: it is not encrypted.\n" +
                "Bookmarks: export an .html file, then Bookmarks > menu > Import from file.",
        )
    }
}

internal fun describe(summary: KiwiHistoryImporter.Summary): String = buildString {
    if (summary.read == 0) {
        append("No browsing history found in that file.")
        return@buildString
    }
    append("Added ${summary.added} of ${summary.read} visits.")
    if (summary.duplicates > 0) append(" ${summary.duplicates} were already in your history.")
    if (summary.skipped > 0) append(" ${summary.skipped} were not web page visits.")
    if (summary.failed > 0) append(" ${summary.failed} were refused by the history store.")
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Status(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}
