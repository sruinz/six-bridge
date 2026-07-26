package dev.sixbridge.manager.icon

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface InstalledExtensionIconSource {
    suspend fun load(packageName: String): ByteArray?
}

internal class ContentProviderInstalledExtensionIconSource(
    private val extract: (InputStream) -> ByteArray?,
    private val openStream: (String) -> InputStream?,
) : InstalledExtensionIconSource {
    constructor(contentResolver: ContentResolver, cacheDirectory: File) : this(
        extract = CrxExtensionIconExtractor(cacheDirectory)::extract,
        openStream = { uri -> contentResolver.openInputStream(Uri.parse(uri)) },
    )

    override suspend fun load(packageName: String): ByteArray? =
        withContext(Dispatchers.IO) {
            if (!WRAPPER_PACKAGE.matches(packageName)) {
                return@withContext null
            }
            val uri = "content://$packageName.contentprovider"
            try {
                openStream(uri)?.use(extract)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }

    private companion object {
        val WRAPPER_PACKAGE = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
    }
}
