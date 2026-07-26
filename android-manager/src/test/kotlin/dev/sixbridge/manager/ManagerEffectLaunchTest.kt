package dev.sixbridge.manager

import dev.sixbridge.manager.ui.LaunchDestination
import dev.sixbridge.manager.ui.ManagerEffect
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class ManagerEffectLaunchTest {
    @Test
    fun `intent 생성 예외는 같은 effect를 실패 callback에 전달한다`() {
        val effect = launchEffect()
        var failed: ManagerEffect.LaunchIntent? = null

        val launched = executeLaunch(
            effect = effect,
            resolveIntent = { throw IllegalStateException("factory failed") },
            launchIntent = { error("호출되면 안 됩니다.") },
            onFailure = { failed = it },
        )

        assertFalse(launched)
        assertSame(effect, failed)
    }

    @Test
    fun `startActivity 예외는 같은 effect를 실패 callback에 전달한다`() {
        val effect = launchEffect()
        var failed: ManagerEffect.LaunchIntent? = null

        val launched = executeLaunch(
            effect = effect,
            resolveIntent = { "resolved intent" },
            launchIntent = { throw SecurityException("launch denied") },
            onFailure = { failed = it },
        )

        assertFalse(launched)
        assertSame(effect, failed)
    }

    @Test
    fun `성공한 launch는 실패를 저장하지 않는다`() {
        val effect = launchEffect()
        val launchedValues = mutableListOf<String>()
        var failures = 0

        val launched = executeLaunch(
            effect = effect,
            resolveIntent = { "resolved intent" },
            launchIntent = { launchedValues += it },
            onFailure = { failures += 1 },
        )

        assertTrue(launched)
        assertEquals(listOf("resolved intent"), launchedValues)
        assertEquals(0, failures)
    }

    private fun launchEffect() = ManagerEffect.LaunchIntent(
        destination = LaunchDestination.SAMSUNG_ADD_ONS,
        intentFactory = { error("generic seam test에서는 호출하지 않습니다.") },
    )
}
