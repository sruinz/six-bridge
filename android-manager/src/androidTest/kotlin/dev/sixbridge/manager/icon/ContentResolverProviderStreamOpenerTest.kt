package dev.sixbridge.manager.icon

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResolverProviderStreamOpenerTest {
    private lateinit var resolver: ContentResolver

    @Before
    fun setUp() {
        resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        assertTrue(callProvider("reset"))
    }

    @After
    fun tearDown() {
        callProvider("release")
    }

    @Test
    fun `open coroutine 취소는 ContentResolver CancellationSignal에 전달된다`() =
        runBlocking {
            val opener = AndroidProviderStreamOpener(resolver)
            val open = launch(Dispatchers.Default) {
                opener.open("$PROVIDER_URI/cancel-aware")
            }
            assertTrue(callProvider("await-open"))

            open.cancel()
            assertTrue(callProvider("await-cancellation"))
            assertTrue(callProvider("release"))
            open.cancelAndJoin()
        }

    private fun callProvider(method: String): Boolean =
        resolver.call(PROVIDER_URI, method, null, null)
            ?.getBoolean("completed")
            ?: false

    private companion object {
        val PROVIDER_URI: Uri =
            Uri.parse("content://dev.sixbridge.manager.test.iconprovider")
    }
}
