package dev.sixbridge.manager

import android.net.Uri
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActivityDocumentLaunchStateTest {
    @Test
    fun picker_rotation_keeps_identity_and_returns_the_real_uri_without_saving_it() {
        val pending = PendingActivityDocumentLaunch(
            token = null,
            ownerGeneration = 7,
            launchId = 11,
        )
        val bundle = Bundle()
        pending.save(bundle, tokenKey = null, ownerKey = "owner", launchKey = "launch")

        val restored = bundle.restorePendingDocumentLaunch(
            tokenKey = null,
            ownerKey = "owner",
            launchKey = "launch",
        )
        val uri = Uri.parse("content://provider/private-input.crx")
        val action = requireNotNull(restored).conversionFileAction(uri)

        assertEquals(7, action.ownerGeneration)
        assertEquals(11, action.launchId)
        assertSame(uri, action.uri)
        assertEquals(setOf("owner", "launch"), bundle.keySet())
    }

    @Test
    fun save_rotation_keeps_token_identity_but_cancel_returns_no_destination() {
        val pending = PendingActivityDocumentLaunch(
            token = "conversion-token",
            ownerGeneration = 13,
            launchId = 17,
        )
        val bundle = Bundle()
        pending.save(bundle, tokenKey = "token", ownerKey = "owner", launchKey = "launch")

        val restored = bundle.restorePendingDocumentLaunch(
            tokenKey = "token",
            ownerKey = "owner",
            launchKey = "launch",
        )
        val action = requireNotNull(restored).conversionSaveAction(uri = null)

        assertEquals("conversion-token", action.token)
        assertEquals(13, action.ownerGeneration)
        assertEquals(17, action.launchId)
        assertNull(action.destination)
        assertEquals(setOf("token", "owner", "launch"), bundle.keySet())
    }
}
