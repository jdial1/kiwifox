/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.mozilla.fenix.components.menu.compose.MenuGroup
import org.mozilla.fenix.components.menu.compose.MenuItem
import mozilla.components.ui.icons.R as iconsR

/**
 * The main menu's "Update KiwiFox" item, at the top of the menu while a newer build is waiting (like
 * desktop Firefox's "Update available — restart" menu item). Patched into MainMenu by apply_kiwifox.py.
 */
@Composable
fun KiwiUpdateMenuItem() {
    val release = KiwiUpdater.available ?: return
    val context = LocalContext.current
    MenuGroup {
        MenuItem(
            label = "Update KiwiFox",
            beforeIconPainter = painterResource(id = iconsR.drawable.mozac_ic_update_circle_24),
            isBeforeIconHighlighted = true,
            description = "${release.label} is ready to install",
            onClick = { with(KiwiUpdater) { context.findActivity() }?.let(KiwiUpdater::startUpdate) },
        )
    }
}

/**
 * The "KiwiFox update" entry at the top of Settings (added to preferences.xml by apply_kiwifox.py): the
 * installed build, the waiting update if any, and a tap to install it or check for one.
 */
internal fun bindUpdatePreference(fragment: PreferenceFragmentCompat) {
    val preference = fragment.findPreference<Preference>(KiwiUpdater.SETTINGS_KEY) ?: return
    val context = fragment.requireContext()
    val release = KiwiUpdater.available
    preference.title = if (release != null) "KiwiFox update available" else "KiwiFox updates"
    preference.summary = if (release != null) {
        "${release.label} is ready to install · tap to update (installed: ${KiwiUpdater.describe(context)})"
    } else {
        "${KiwiUpdater.describe(context)} · tap to check for updates"
    }
    preference.setOnPreferenceClickListener {
        fragment.activity?.let(KiwiUpdater::startUpdate)
        true
    }
}
