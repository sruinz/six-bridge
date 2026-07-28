package dev.sixbridge.manager

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.manager.ui.ManagerAction
import dev.sixbridge.manager.ui.ManagerEffect
import java.io.File
import java.util.UUID
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
        val root = File(targetContext.filesDir, "generated-bundles/share-handoff").apply {
            mkdirs()
        }
        val original = File(
            targetContext.filesDir,
            "generated-bundles/11111111-1111-4111-8111-111111111111.six",
        ).apply {
            parentFile?.mkdirs()
            writeBytes(expected)
        }
        val file = File(root, "share-${UUID.randomUUID()}.six").apply {
            original.copyTo(this)
            assertTrue(setReadOnly())
        }

        try {
            lateinit var chooser: Intent
            var launchAction: ManagerAction? = null
            val effect = ManagerEffect.ShareGeneratedBundle(
                token = "11111111-1111-4111-8111-111111111111",
                file = file,
                handoffId = 29,
            )
            executeGeneratedShareLaunch(
                effect = effect,
                resolveChooser = { generatedBundleShareChooser(targetContext, it.file) },
                launchChooser = { chooser = it },
                onAction = { launchAction = it },
            )
            val succeeded = launchAction as ManagerAction.GeneratedShareLaunchSucceeded
            assertEquals(effect.token, succeeded.token)
            assertEquals(effect.handoffId, succeeded.handoffId)
            assertArrayEquals(expected, file.readBytes())
            assertTrue(original.delete())
            assertTrue(!original.exists())
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
            file.setWritable(true)
            file.delete()
            original.delete()
        }
    }
}
