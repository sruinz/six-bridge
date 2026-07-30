package dev.sixbridge.manager.navigation

import android.content.Intent
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileManagerNavigatorTest {
    @Test
    fun `Android files category chooser는 APK URI와 grant를 포함하지 않는다`() {
        val navigator = DefaultFileManagerNavigator(
            canResolve = { true },
            chooser = { Intent.createChooser(it, "파일 앱 선택") },
        )

        val intent = requireNotNull(navigator.launcherIntent())
        val target = requireNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))

        assertEquals(Intent.ACTION_MAIN, target.action)
        assertEquals(Intent.CATEGORY_APP_FILES, target.selector?.categories?.single())
        assertNull(target.data)
        assertNull(target.type)
        assertNull(target.clipData)
        assertEquals(0, target.flags and URI_PERMISSION_FLAGS)
        assertEquals(0, intent.flags and URI_PERMISSION_FLAGS)
    }

    @Test
    fun `Android files category resolver가 없으면 null을 반환한다`() {
        val navigator = DefaultFileManagerNavigator(
            canResolve = { false },
            chooser = { Intent.createChooser(it, "파일 앱 선택") },
        )

        assertNull(navigator.launcherIntent())
    }

    private companion object {
        const val URI_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
