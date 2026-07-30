package dev.sixbridge.manager.install

import dev.sixbridge.manager.wrapper.InstallSourceEvidence

enum class SamsungInstallSourceVerdict {
    ALLOWED_ADB,
    ALLOWED_MY_FILES,
    UNVERIFIED_FILE_APP,
    UNSUPPORTED,
    UNKNOWN,
}

fun classifySamsungInstallSource(
    evidence: InstallSourceEvidence,
): SamsungInstallSourceVerdict = when (evidence) {
    InstallSourceEvidence.Unavailable -> SamsungInstallSourceVerdict.UNKNOWN
    is InstallSourceEvidence.Known -> when {
        evidence.installingPackageName == null &&
            evidence.originatingPackageName == null ->
            SamsungInstallSourceVerdict.ALLOWED_ADB
        evidence.installingPackageName == GOOGLE_PACKAGE_INSTALLER &&
            evidence.originatingPackageName in setOf(null, SAMSUNG_MY_FILES) ->
            SamsungInstallSourceVerdict.ALLOWED_MY_FILES
        evidence.installingPackageName == GOOGLE_PACKAGE_INSTALLER &&
            evidence.originatingPackageName != MANAGER_PACKAGE ->
            SamsungInstallSourceVerdict.UNVERIFIED_FILE_APP
        else -> SamsungInstallSourceVerdict.UNSUPPORTED
    }
}

internal const val GOOGLE_PACKAGE_INSTALLER = "com.google.android.packageinstaller"
internal const val SAMSUNG_MY_FILES = "com.sec.android.app.myfiles"
internal const val MANAGER_PACKAGE = "dev.sixbridge.manager"
