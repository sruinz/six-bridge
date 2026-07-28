package dev.sixbridge.manager

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedBundleShareGrantTest {
    @Test
    fun chooser_target_preserves_file_and_receiver_can_read_but_cannot_write() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val expected = "generated-share-content".encodeToByteArray()
        val root = File(targetContext.filesDir, "generated-bundles").apply { mkdirs() }
        val file = File(root, "share-grant-${System.nanoTime()}.six")
        file.writeBytes(expected)

        try {
            val chooser = generatedBundleShareChooser(targetContext, file)
            assertArrayEquals(expected, file.readBytes())
            val target = requireNotNull(
                chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java),
            )
            val uri = requireNotNull(
                target.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java),
            )
            assertEquals(uri, target.clipData?.getItemAt(0)?.uri)
            assertEquals(0, target.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

            val completed = CountDownLatch(1)
            var result: Bundle? = null
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode == ShareGrantReceiverActivity.RESULT_SHARE_ACCESS) {
                        result = resultData
                        completed.countDown()
                    }
                }
            }
            target.component = ComponentName(
                testContext.packageName,
                ShareGrantReceiverActivity::class.java.name,
            )
            target.putExtra(ShareGrantReceiverActivity.EXTRA_RESULT_RECEIVER, receiver)
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            targetContext.startActivity(target)

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertArrayEquals(
                expected,
                result?.getByteArray(ShareGrantReceiverActivity.RESULT_READ_BYTES),
            )
            assertEquals(
                null,
                result?.getString(ShareGrantReceiverActivity.RESULT_READ_ERROR),
            )
            assertTrue(
                result?.getBoolean(ShareGrantReceiverActivity.RESULT_WRITE_DENIED) == true,
            )
            assertEquals(
                SecurityException::class.java.name,
                result?.getString(ShareGrantReceiverActivity.RESULT_WRITE_ERROR),
            )
            assertArrayEquals(expected, file.readBytes())
        } finally {
            file.delete()
        }
    }
}
