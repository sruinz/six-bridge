package dev.sixbridge.manager.importer

import android.content.ContentResolver
import android.net.Uri
import java.io.FileNotFoundException
import java.io.InputStream

fun interface BundleSource {
    fun open(request: Any): InputStream
}

class ContentResolverBundleSource(
    private val contentResolver: ContentResolver,
) : BundleSource {
    override fun open(request: Any): InputStream {
        val uri = request as? Uri ?: throw FileNotFoundException("지원하지 않는 bundle request입니다.")
        return contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("선택한 bundle을 열 수 없습니다.")
    }
}
