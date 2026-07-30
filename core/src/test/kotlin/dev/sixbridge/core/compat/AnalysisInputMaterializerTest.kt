package dev.sixbridge.core.compat

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisInputMaterializerTest {
    @Test
    fun `source directory 예산 초과는 snapshot 전에 거부한다`() {
        val root = Files.createTempDirectory("six-input-directory-")
        Files.writeString(root.resolve("manifest.json"), validManifest)
        Files.write(
            root.resolve("large.js"),
            ByteArray(AnalysisSafetyPolicy.mobile.maxSingleScriptBytes.toInt() + 1),
        )
        var snapshots = 0

        val error = assertFailsWith<AnalysisBudgetExceededException> {
            AnalysisInputMaterializer.snapshotDirectory(
                root,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            ) {
                snapshots += 1
                Unit
            }
        }

        assertEquals("analysis_budget_exceeded", error.code)
        assertEquals(0, snapshots)
    }

    @Test
    fun `ZIP declared entry 예산 초과는 payload reader 전에 거부한다`() {
        val input = oversizedZip()
        var payloadReads = 0

        assertFailsWith<AnalysisBudgetExceededException> {
            AnalysisInputMaterializer.readArchive(
                input,
                AnalysisArchiveKind.ZIP,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            ) {
                payloadReads += 1
                Files.readAllBytes(it)
            }
        }

        assertEquals(0, payloadReads)
    }

    @Test
    fun `CRX header 뒤 ZIP declared entry 예산 초과는 payload reader 전에 거부한다`() {
        val zip = oversizedZip()
        val input = Files.createTempFile("six-input-", ".crx")
        Files.newOutputStream(input).use { output ->
            output.write("Cr24".encodeToByteArray())
            output.write(byteArrayOf(3, 0, 0, 0))
            output.write(byteArrayOf(0, 0, 0, 0))
            Files.copy(zip, output)
        }
        var payloadReads = 0

        assertFailsWith<AnalysisBudgetExceededException> {
            AnalysisInputMaterializer.readArchive(
                input,
                AnalysisArchiveKind.CRX3,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            ) {
                payloadReads += 1
                Files.readAllBytes(it)
            }
        }

        assertEquals(0, payloadReads)
    }

    private fun oversizedZip(): Path =
        Files.createTempFile("six-input-", ".zip").also { zip ->
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(validManifest.encodeToByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry("large.js"))
                repeat(
                    AnalysisSafetyPolicy.mobile.maxSingleScriptBytes.toInt() + 1,
                ) {
                    output.write(0)
                }
                output.closeEntry()
            }
        }

    private val validManifest =
        """{"manifest_version":3,"name":"Budget","version":"1"}"""
}
