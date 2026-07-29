package dev.sixbridge.manager.app

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class InstallApkExportNamingTest {
    @Test
    fun `확장 이름 버전 짧은 ID로 APK 이름을 만든다`() {
        assertEquals(
            "스마트 컨텍스트 메뉴-1.1.1-ddcpml.apk",
            suggestedWrapperApkName(
                "스마트 컨텍스트 메뉴",
                "1.1.1",
                "ddcpmlcaanjillmjmnlmngimhbbojgam",
                7,
            ),
        )
    }

    @Test
    fun `위험 문자와 제어 문자를 밑줄로 치환한다`() {
        assertEquals(
            "A_B_C-2_0-aabbcc.apk",
            suggestedWrapperApkName(
                " A/B\u0000C ",
                "2:0",
                "aabbccddeeffgghhiijjkkllmmnnoopp",
                7,
            ),
        )
    }

    @Test
    fun `양끝 제어 문자도 삭제하지 않고 밑줄로 치환한다`() {
        assertEquals(
            "_A_-1-aabbcc.apk",
            suggestedWrapperApkName(
                "\tA\n",
                "1",
                "aabbccddeeffgghhiijjkkllmmnnoopp",
                7,
            ),
        )
    }

    @Test
    fun `빈 이름은 전체 extension ID를 사용하고 ID suffix를 보존한다`() {
        val extensionId = "aabbccddeeffgghhiijjkkllmmnnoopp"

        assertEquals(
            "$extensionId-7-aabbcc.apk",
            suggestedWrapperApkName("", "7", extensionId, 7),
        )
    }

    @Test
    fun `점과 공백만 남은 이름은 전체 extension ID를 사용한다`() {
        val extensionId = "aabbccddeeffgghhiijjkkllmmnnoopp"

        assertEquals(
            "$extensionId-7-aabbcc.apk",
            suggestedWrapperApkName(" . ", "7", extensionId, 7),
        )
    }

    @Test
    fun `확장자 제외 파일명은 96 code point 이하로 자른다`() {
        val result = suggestedWrapperApkName(
            extensionName = "😀".repeat(100),
            extensionVersion = "1.0.0",
            extensionId = "aabbccddeeffgghhiijjkkllmmnnoopp",
            wrapperVersionCode = 7,
        )

        val baseName = result.removeSuffix(".apk")
        assertTrue(baseName.codePointCount(0, baseName.length) <= 96)
        assertTrue(result.endsWith("-aabbcc.apk"))
    }
}
