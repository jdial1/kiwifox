/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.mozilla.fenix.R
import org.mozilla.fenix.e2e.SystemInsetsPaddedFragment
import org.mozilla.fenix.ext.showToolbar
import org.mozilla.fenix.theme.FirefoxTheme

private const val SCREEN_TITLE = "Import from Chrome (Wi-Fi)"

/**
 * Settings > Import from Chrome (Wi-Fi): pulls the encrypted bundle from the PC's LAN share and imports
 * bookmarks, history and passwords in one go. See [KiwiShareImport].
 */
class KiwiShareImportFragment : Fragment(), SystemInsetsPaddedFragment {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { FirefoxTheme { ShareImportScreen() } }
        }

    override fun onResume() {
        super.onResume()
        showToolbar(SCREEN_TITLE)
    }

    companion object {
        const val SETTINGS_KEY = "kiwifox_share_import"
    }
}

/** Wires the Settings entry (added to preferences.xml by apply_kiwifox.py) to this screen. */
internal fun bindShareImportPreference(fragment: PreferenceFragmentCompat) {
    val preference = fragment.findPreference<Preference>(KiwiShareImportFragment.SETTINGS_KEY) ?: return
    preference.setOnPreferenceClickListener {
        fragment.findNavController().navigate(R.id.kiwiShareImportFragment)
        true
    }
}

@Composable
private fun ShareImportScreen() {
    val context = LocalContext.current
    var url by remember { mutableStateOf(KiwiShareImport.savedUrl(context)) }
    var passphrase by remember { mutableStateOf("") }
    val state = KiwiShareImport.state
    val running = state is KiwiShareImport.State.Running

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Body(
            "Pulls the encrypted bundle your PC put on the Wi-Fi share and imports your Chrome bookmarks, " +
                "history and passwords in one step. Build it on the PC with export-chrome-to-kiwifox.ps1.",
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Bundle URL") },
            singleLine = true,
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Passphrase") },
            singleLine = true,
            enabled = !running,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { KiwiShareImport.start(context, url, passphrase) },
                enabled = !running && url.isNotBlank() && passphrase.isNotBlank(),
            ) { Text("Pull & import") }
            if (running) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }

        when (state) {
            KiwiShareImport.State.Idle -> Unit
            is KiwiShareImport.State.Running -> Status(state.step)
            is KiwiShareImport.State.Done -> Status(describe(state.summary))
            is KiwiShareImport.State.Failed -> Status("Import failed: ${state.message}")
        }

        Body(
            "Passwords are imported as plaintext into Firefox's password store on this phone. After a password " +
                "import, delete the bundle from the share if you don't need it there.",
        )
    }
}

internal fun describe(summary: KiwiShareImport.Summary): String {
    val parts = mutableListOf<String>()
    summary.bookmarks?.let { parts += "$it bookmarks" }
    summary.historyAdded?.let { added ->
        val dup = summary.historyDuplicates ?: 0
        parts += "$added history visits" + if (dup > 0) " ($dup already present)" else ""
    }
    summary.passwords?.let { parts += "$it passwords" }
    return if (parts.isEmpty()) "Nothing to import was found in the bundle." else "Imported " + parts.joinToString(", ") + "."
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Status(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
