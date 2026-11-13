/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Proves the Kotlin .kfex reader interoperates with the .NET/PowerShell writer (browser-export/kfex.ps1):
 * chrome-export-fixture.kfex was produced on Windows with passphrase "kfex-fixture-pass".
 */
class KiwiCryptoTest {

    private fun fixture(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/kiwifox/chrome-export-fixture.kfex")) {
            "fixture resource missing"
        }.use { it.readBytes() }

    @Test
    fun `decrypts and unzips the Windows-produced fixture`() {
        val entries = KiwiCrypto.openKfexEntries(fixture(), "kfex-fixture-pass")
        assertEquals(setOf("bookmarks.html", "History", "passwords.csv"), entries.keys)
        assertTrue(String(entries.getValue("bookmarks.html")).contains("NETSCAPE-Bookmark-file-1"))
        assertEquals("SQLITE_PLACEHOLDER_History", String(entries.getValue("History")))
        assertEquals(
            "name,url,username,password\nEx,https://ex.com/,bob,s3cr3t\n",
            String(entries.getValue("passwords.csv")),
        )
    }

    @Test
    fun `wrong passphrase is rejected before decryption`() {
        try {
            KiwiCrypto.openKfex(fixture(), "not-the-passphrase")
            fail("expected KfexError")
        } catch (e: KiwiCrypto.KfexError) {
            assertTrue(e.message!!.contains("Wrong passphrase"))
        }
    }

    @Test
    fun `a non-kfex file is rejected`() {
        try {
            KiwiCrypto.openKfex(ByteArray(200) { 0x41 }, "x")
            fail("expected KfexError")
        } catch (e: KiwiCrypto.KfexError) {
            assertTrue(e.message!!.contains("bad header") || e.message!!.contains("too small"))
        }
    }
}
