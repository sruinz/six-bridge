package dev.sixbridge.desktop

import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DesktopDistributionMetadataTest {
    @Test
    fun `runtime 배포 메타데이터는 앱 계약과 일치한다`() {
        val properties = loadAppProperties()

        assertEquals("SIX Bridge", properties.getProperty("name"))
        assertEquals("dev.sixbridge.desktop", properties.getProperty("applicationId"))
        assertEquals("0.1.0", properties.getProperty("version"))
        assertEquals("13.0", properties.getProperty("minimumMacOs"))
    }

    private fun loadAppProperties(): Properties {
        val resource = assertNotNull(
            javaClass.classLoader.getResourceAsStream("app.properties"),
            "app.properties가 runtime classpath에 있어야 합니다.",
        )
        return resource.use { Properties().apply { load(it) } }
    }
}
