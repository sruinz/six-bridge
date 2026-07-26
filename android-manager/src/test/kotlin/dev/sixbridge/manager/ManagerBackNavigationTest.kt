package dev.sixbridge.manager

import dev.sixbridge.manager.ui.ManagerAction
import dev.sixbridge.manager.ui.ManagerRoute
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ManagerBackNavigationTest {
    @Test
    fun `review back은 review를 취소한다`() {
        assertEquals(
            ManagerAction.CancelReview,
            ManagerRoute.Review("review-token").backAction(),
        )
    }

    @Test
    fun `report back은 home으로 돌아간다`() {
        assertEquals(
            ManagerAction.ReturnHome,
            ManagerRoute.Report("dev.sixbridge.ext.example").backAction(),
        )
    }

    @Test
    fun `home은 system back 기본 동작을 가로채지 않는다`() {
        assertNull(ManagerRoute.Home.backAction())
    }
}
