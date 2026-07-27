package dev.sixbridge.manager.importer

import android.content.ContentResolver
import android.net.Uri
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.GeneratedBundleStore
import java.io.FileNotFoundException
import java.io.InputStream

sealed interface BundleOpenRequest {
    data class Saf(val uri: Uri) : BundleOpenRequest
    data class Generated(val bundle: GeneratedBundle) : BundleOpenRequest
}

fun interface BundleSource {
    fun open(request: BundleOpenRequest): InputStream
}

class ContentResolverBundleSource(
    private val contentResolver: ContentResolver,
) : BundleSource {
    override fun open(request: BundleOpenRequest): InputStream {
        val uri = (request as? BundleOpenRequest.Saf)?.uri
            ?: throw FileNotFoundException("SAF bundle request가 아닙니다.")
        return contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("선택한 bundle을 열 수 없습니다.")
    }
}

class GeneratedBundleStoreSource(
    private val store: GeneratedBundleStore,
) : BundleSource {
    override fun open(request: BundleOpenRequest): InputStream {
        val bundle = (request as? BundleOpenRequest.Generated)?.bundle
            ?: throw FileNotFoundException("생성 bundle request가 아닙니다.")
        return try {
            store.open(bundle)
        } catch (error: Exception) {
            throw FileNotFoundException("검증된 생성 bundle을 열 수 없습니다.").also {
                it.initCause(error)
            }
        }
    }
}

class RoutingBundleSource(
    private val saf: BundleSource,
    private val generated: BundleSource,
) : BundleSource {
    override fun open(request: BundleOpenRequest): InputStream = when (request) {
        is BundleOpenRequest.Saf -> saf.open(request)
        is BundleOpenRequest.Generated -> generated.open(request)
    }
}
