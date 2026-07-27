package dev.sixbridge.manager.conversion

import android.net.Uri
import java.io.File

sealed interface OnDeviceInputRequest {
    data class Saf(
        val uri: Uri,
    ) : OnDeviceInputRequest {
        override fun toString(): String = "Saf(uri=<redacted>)"
    }

    data class ChromeStore(
        val pageUrl: String,
    ) : OnDeviceInputRequest {
        override fun toString(): String = "ChromeStore(pageUrl=<redacted>)"
    }
}

enum class OnDeviceInputKind {
    ZIP,
    CRX3,
    CHROME_WEB_STORE,
    SIX_DRAFT,
}

data class OnDeviceInputArtifact(
    val token: String,
    val kind: OnDeviceInputKind,
    val privateFile: File,
    val sha256: String,
    val expectedExtensionId: String?,
)

enum class OnDeviceInputErrorCode {
    SOURCE_UNAVAILABLE,
    SOURCE_READ_FAILED,
    INPUT_TOO_LARGE,
    INVALID_ZIP,
    INVALID_CRX3,
    INVALID_DRAFT,
    UNSUPPORTED_FORMAT,
    URL,
    NETWORK,
    INPUT_INVALID,
    PRIVATE_STORAGE_FAILED,
}

class OnDeviceInputException(
    val code: OnDeviceInputErrorCode,
    message: String,
    val chromeDownloadErrorCode: ChromeDownloadErrorCode? = null,
) : IllegalArgumentException(message)

fun interface SafInputOpener {
    fun open(request: OnDeviceInputRequest.Saf): java.io.InputStream?
}

data class DownloadProof(
    val extensionId: String,
    val byteCount: Long,
    val sha256: String,
)
