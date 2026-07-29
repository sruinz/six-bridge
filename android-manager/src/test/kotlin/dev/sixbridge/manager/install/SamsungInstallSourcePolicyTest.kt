package dev.sixbridge.manager.install

import dev.sixbridge.manager.wrapper.InstallSourceEvidence
import org.junit.Test
import kotlin.test.assertEquals

class SamsungInstallSourcePolicyTest {
    @Test
    fun `삼성 내 파일 origin만 사용자 설치로 허용한다`() {
        assertEquals(
            SamsungInstallSourceVerdict.ALLOWED_MY_FILES,
            classifySamsungInstallSource(
                InstallSourceEvidence.Known(
                    installingPackageName = "com.google.android.packageinstaller",
                    originatingPackageName = "com.sec.android.app.myfiles",
                ),
            ),
        )
        assertEquals(
            SamsungInstallSourceVerdict.UNSUPPORTED,
            classifySamsungInstallSource(
                InstallSourceEvidence.Known(
                    installingPackageName = "com.google.android.packageinstaller",
                    originatingPackageName = "dev.sixbridge.manager",
                ),
            ),
        )
    }

    @Test
    fun `installer와 origin이 모두 없으면 ADB 개발 설치로 허용한다`() {
        assertEquals(
            SamsungInstallSourceVerdict.ALLOWED_ADB,
            classifySamsungInstallSource(
                InstallSourceEvidence.Known(null, null),
            ),
        )
    }

    @Test
    fun `Google installer의 origin이 없거나 내 파일이면 사용자 설치로 허용한다`() {
        listOf(null, "com.sec.android.app.myfiles").forEach { origin ->
            assertEquals(
                SamsungInstallSourceVerdict.ALLOWED_MY_FILES,
                classifySamsungInstallSource(
                    InstallSourceEvidence.Known(
                        installingPackageName = "com.google.android.packageinstaller",
                        originatingPackageName = origin,
                    ),
                ),
            )
        }
    }
}
