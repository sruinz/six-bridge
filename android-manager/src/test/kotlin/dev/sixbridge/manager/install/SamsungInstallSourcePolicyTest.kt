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
    fun `Google installer의 null origin은 시스템 문서 앱 설치로 허용한다`() {
        assertEquals(
            SamsungInstallSourceVerdict.ALLOWED_SYSTEM_DOCUMENTS,
            classifySamsungInstallSource(
                InstallSourceEvidence.Known(
                    installingPackageName = GOOGLE_PACKAGE_INSTALLER,
                    originatingPackageName = null,
                ),
            ),
        )
    }

    @Test
    fun `Google installer의 Samsung My Files origin은 대체 경로로 허용한다`() {
        assertEquals(
            SamsungInstallSourceVerdict.ALLOWED_MY_FILES,
            classifySamsungInstallSource(
                InstallSourceEvidence.Known(
                    installingPackageName = GOOGLE_PACKAGE_INSTALLER,
                    originatingPackageName = SAMSUNG_MY_FILES,
                ),
            ),
        )
    }

    @Test
    fun `Google installer의 다른 파일 앱 origin은 설치됨 지원 미확인이다`() {
        assertEquals(
            SamsungInstallSourceVerdict.UNVERIFIED_FILE_APP,
            classifySamsungInstallSource(
                InstallSourceEvidence.Known(
                    installingPackageName = GOOGLE_PACKAGE_INSTALLER,
                    originatingPackageName = "com.example.files",
                ),
            ),
        )
    }
}
