package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConversionPipelineTest {
    @Test
    fun `pipeline use는 취소를 primary로 두고 실제 다중 정리 실패를 중첩 보존한다`() {
        val closeOrder = mutableListOf<String>()
        val materializationFailure = IllegalStateException("materialization cleanup")
        val engineFailure = IllegalStateException("engine cleanup")
        val engine = RecordingPipelineEngine(
            closeFailure = engineFailure,
            onClose = { closeOrder += "engine" },
        )
        val materialization = RecordingPipelineMaterialization(
            closeFailure = materializationFailure,
            onClose = { closeOrder += "materialization" },
        )
        val pipeline = ConversionPipeline.create(
            localRoot = Files.createTempDirectory("six-pipeline-cleanup"),
            engine = engine,
            materialization = materialization,
        )

        val cancellation = assertFailsWith<CancellationException> {
            pipeline.use {
                throw CancellationException("cancel")
            }
        }

        assertEquals(1, cancellation.suppressed.size)
        assertSame(materializationFailure, cancellation.suppressed.single())
        assertEquals(1, materializationFailure.suppressed.size)
        assertSame(engineFailure, materializationFailure.suppressed.single())
        assertEquals(listOf("materialization", "engine"), closeOrder)
    }

    @Test
    fun `분석 preflight 준비는 취소 신호를 각각 전파한다`() {
        val source = plainSource("six-pipeline-cancel")
        val resolved = resolvedSource(source)

        assertPipelineCancellation { pipeline, control ->
            pipeline.analyzeWithIdentity(resolved, profile(), control)
        }
        assertPipelineCancellation { pipeline, control ->
            pipeline.preflight(resolved, profile(), emptyList(), control)
        }
        assertPipelineCancellation { pipeline, control ->
            pipeline.prepare(resolved, profile(), emptyList(), control)
        }
    }

    @Test
    fun `same snapshot profile and approvals produce deterministic source plan and report hashes`() {
        val localRoot = Files.createTempDirectory("six-pipeline-local")
        val source = convertibleSource("six-pipeline-deterministic", includePopup = true)
        val resolved = resolvedSource(source)

        val first = ConversionPipeline(localRoot).use {
            it.prepare(
                resolved,
                profile(),
                listOf(
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                ),
            )
        }
        val second = ConversionPipeline(localRoot).use {
            it.prepare(
                resolved,
                profile(),
                linkedSetOf(
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                ),
            )
        }
        val independent = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-independent-local"),
        ).use {
            it.prepare(
                resolved,
                profile(),
                listOf(
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                ),
            )
        }

        assertContentEquals(first.sourceZip, second.sourceZip)
        assertFalse(first.sourceZip.contentEquals(independent.sourceZip))
        assertEquals(first.planSha256, second.planSha256)
        assertEquals(first.planSha256, independent.planSha256)
        assertEquals(first.reportSha256, second.reportSha256)
        assertNotEquals(first.extensionId, independent.extensionId)
        assertNotEquals(first.reportSha256, independent.reportSha256)
        assertEquals(first.extensionId, second.extensionId)
        assertIs<LogicalSourceIdentityResult.NeedsUserSelection>(
            first.logicalSourceIdentity,
        )
        assertEquals(null, first.logicalIdentity)
        assertEquals(ConversionState.CONVERTED, first.compatibilityAfter.conversionState)
        assertTrue(first.compatibilityAfter.adapterDecisions.all(AdapterDecision::applied))
    }

    @Test
    fun `converted popup uses an option page without runtime injection`() {
        val source = convertibleSource(
            "six-pipeline-options-entry",
            includePopup = true,
        )
        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-options-entry-local"),
        ).use {
            it.prepare(
                resolvedSource(source),
                profile().copy(
                    supportedFeatures =
                        setOf("extension.options-page.user-accessible"),
                ),
                listOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY),
            )
        }
        val extracted = Files.createTempDirectory("six-options-entry")
        ExtensionZip.extract(result.sourceZip, extracted)
        val manifest = extracted.resolve("manifest.json").readText()

        assertTrue("\"options_ui\"" in manifest)
        assertTrue(Files.exists(extracted.resolve("sixbridge/action-popup-entry.v2.json")))
        assertFalse(
            Files.exists(extracted.resolve("_sixbridge/action-popup-entry.v2.json")),
        )
        listOf(
            "_sixbridge/action-popup-entry-content.v1.js",
            "_sixbridge/action-popup-entry-background.v1.js",
            "_sixbridge-action-popup-entry-bootstrap.v1.js",
        ).forEach { relativePath ->
            assertFalse(Files.exists(extracted.resolve(relativePath)), relativePath)
            assertFalse(relativePath in manifest, relativePath)
        }
    }

    @Test
    fun `verified passthrough CRX preserves original bytes proof and id`() {
        val localRoot = Files.createTempDirectory("six-pipeline-passthrough")
        val source = plainSource("six-pipeline-passthrough-source")
        val original = Crx3.pack(source, keyPair())
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "verified.crx",
            sourceRoot = source,
            crxBytes = original.bytes,
            expectedExtensionId = original.extensionId,
        )

        val result = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertContentEquals(original.bytes, result.crxBytes)
        assertEquals(original.extensionId, result.extensionId)
        assertEquals(original.extensionId, result.compatibilityAfter.originalExtensionId)
        assertEquals(original.extensionId, result.compatibilityAfter.convertedExtensionId)
        assertEquals("store:${original.extensionId}", result.logicalIdentity)
        assertTrue(result.passthrough)
    }

    @Test
    fun `converted CRX gets stable profile identity new key and matching manifest key`() {
        val localRoot = Files.createTempDirectory("six-pipeline-store-key")
        val source = convertibleSource("six-pipeline-store-source")
        val original = Crx3.pack(source, keyPair())
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "store.crx",
            sourceRoot = source,
            crxBytes = original.bytes,
            expectedExtensionId = original.extensionId,
        )

        val first = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }
        val second = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertEquals("store:${original.extensionId}", first.logicalIdentity)
        assertNotEquals(original.extensionId, first.extensionId)
        assertEquals(first.extensionId, second.extensionId)
        val parsed = Crx3.parse(first.crxBytes)
        assertEquals(first.extensionId, parsed.extensionId)
        assertContentEquals(first.sourceZip, parsed.zipBytes)
        val extracted = Files.createTempDirectory("six-pipeline-packed")
        ExtensionZip.extract(parsed.zipBytes, extracted)
        val manifest = Json.parseToJsonElement(
            extracted.resolve("manifest.json").readText(),
        ) as JsonObject
        val publicKey = Base64.getDecoder().decode(
            (manifest["key"] as JsonPrimitive).content,
        )
        assertEquals(first.extensionId, Crx3.extensionId(publicKey))
        assertEquals(original.extensionId, first.compatibilityAfter.originalExtensionId)
        assertEquals(first.extensionId, first.compatibilityAfter.convertedExtensionId)
        assertFalse(first.crxBytes.contentEquals(original.bytes))
    }

    @Test
    fun `rejects a CRX whose verified payload does not match the supplied source snapshot`() {
        val localRoot = Files.createTempDirectory("six-pipeline-crx-binding")
        val firstSource = plainSource("six-pipeline-crx-first")
        val secondSource = convertibleSource("six-pipeline-crx-second")
        val original = Crx3.pack(firstSource, keyPair())
        val mismatched = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "mismatched.crx",
            sourceRoot = secondSource,
            crxBytes = original.bytes,
            expectedExtensionId = original.extensionId,
        )

        assertFailsWith<CrxSourceBindingException> {
            ConversionPipeline(localRoot).use {
                it.analyze(mismatched, profile())
            }
        }
        assertFailsWith<CrxSourceBindingException> {
            ConversionPipeline(localRoot).use {
                it.prepare(mismatched, profile(), emptyList())
            }
        }
    }

    @Test
    fun `normal resolver CRX binds its verified payload to the canonical snapshot`() {
        val localRoot = Files.createTempDirectory("six-pipeline-crx-resolver")
        val source = plainSource("six-pipeline-crx-resolver-source")
        val packed = Crx3.pack(source, keyPair())
        val input = localRoot.resolve("normal.crx")
        Files.write(input, packed.bytes)
        val resolved = dev.sixbridge.core.input.ExtensionInputResolver()
            .resolve(input.toString())

        val result = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertContentEquals(packed.bytes, result.crxBytes)
        assertEquals(packed.extensionId, result.extensionId)
    }

    @Test
    fun `external CRX binding canonicalizes manifest formatting and key order on both roots`() {
        val localRoot = Files.createTempDirectory("six-pipeline-external-format")
        val packedSource = Files.createTempDirectory("six-pipeline-packed-format").also {
            it.resolve("manifest.json").writeText(
                """{"version":"1","name":"External","manifest_version":3}""",
            )
            it.resolve("worker.js").writeText("console.log('same');")
        }
        val providedSource = Files.createTempDirectory("six-pipeline-provided-format").also {
            it.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "External",
                  "version": "1"
                }
                """.trimIndent(),
            )
            it.resolve("worker.js").writeText("console.log('same');")
        }
        val packed = Crx3.pack(packedSource, keyPair())
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "external-format.crx",
            sourceRoot = providedSource,
            crxBytes = packed.bytes,
            expectedExtensionId = packed.extensionId,
        )

        val result = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertEquals(packed.extensionId, result.extensionId)
        assertContentEquals(packed.bytes, result.crxBytes)
    }

    @Test
    fun `valid signed CRX without manifest key binds through verified proof public key`() {
        val localRoot = Files.createTempDirectory("six-pipeline-external-no-key")
        val source = plainSource("six-pipeline-external-no-key-source")
        val keyPair = keyPair()
        val unsignedZip = ExtensionZip.createBytes(source)
        val packZip = Crx3::class.java.getDeclaredMethod(
            "packZip",
            ByteArray::class.java,
            java.security.KeyPair::class.java,
        ).apply { isAccessible = true }
        val packed = packZip.invoke(Crx3, unsignedZip, keyPair)
            as dev.sixbridge.core.crx.PackedCrx
        val resolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = "external-no-key.crx",
            sourceRoot = source,
            crxBytes = packed.bytes,
            expectedExtensionId = packed.extensionId,
        )

        val result = ConversionPipeline(localRoot).use {
            it.prepare(resolved, profile(), emptyList())
        }

        assertEquals(packed.extensionId, result.extensionId)
        assertContentEquals(packed.bytes, result.crxBytes)
    }

    @Test
    fun `external CRX rejects a manifest key that differs from verified proof key`() {
        val source = plainSource("six-pipeline-external-key-mismatch")
        val manifestKey = keyPair().public.encoded
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1","key":""" +
                """"${Base64.getEncoder().encodeToString(manifestKey)}"}""",
        )
        val packed = packSignedZip(ExtensionZip.createBytes(source), keyPair())
        val resolved = ResolvedExtensionInput(
            ExtensionInputKind.CRX3,
            "external-key-mismatch.crx",
            source,
            crxBytes = packed.bytes,
            expectedExtensionId = packed.extensionId,
        )

        assertFailsWith<CrxSourceBindingException> {
            ConversionPipeline(Files.createTempDirectory("six-key-mismatch-local")).use {
                it.analyze(resolved, profile())
            }
        }
    }

    @Test
    fun `external CRX rejects malformed manifest key as binding failure`() {
        val source = plainSource("six-pipeline-external-key-malformed")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1","key":"%%%"}""",
        )
        val packed = packSignedZip(ExtensionZip.createBytes(source), keyPair())
        val resolved = ResolvedExtensionInput(
            ExtensionInputKind.CRX3,
            "external-key-malformed.crx",
            source,
            crxBytes = packed.bytes,
            expectedExtensionId = packed.extensionId,
        )

        assertFailsWith<CrxSourceBindingException> {
            ConversionPipeline(Files.createTempDirectory("six-key-malformed-local")).use {
                it.prepare(resolved, profile(), emptyList())
            }
        }
    }

    @Test
    fun `pipeline result reports and nested collections are deeply immutable`() {
        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-immutable-local"),
        ).use {
            it.prepare(
                resolvedSource(convertibleSource("six-pipeline-immutable", includePopup = true)),
                profile(),
                listOf(
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                ),
            )
        }
        val hashBefore = result.reportSha256
        val after = result.compatibilityAfter
        val decision = after.adapterDecisions.first()

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (after.declaredPermissions as MutableSet<String>).add("nativeMessaging")
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (after.adapterDecisions as MutableList<AdapterDecision>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (decision.evidence as MutableList<SourceEvidence>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (decision.changes as MutableList<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (decision.remainingLimitations as MutableList<String>).clear()
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.compatibilityBefore.issues as MutableList<dev.sixbridge.core.compat.CompatibilityIssue>)
                .clear()
        }
        val canonicalHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(
                dev.sixbridge.core.bundle.SixBundle
                    .encodeCompatibility(result.compatibilityAfter)
                    .toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }
        assertEquals(hashBefore, canonicalHash)
    }

    @Test
    fun `storage conversion report carries sealed applied proof`() {
        val source = Files.createTempDirectory("six-pipeline-storage").also { root ->
            root.resolve("worker.js").writeText(
                """chrome.storage.sync.get("theme").then(console.log);""",
            )
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Storage",
                  "version": "1",
                  "permissions": ["storage"],
                  "background": {"service_worker": "worker.js"}
                }
                """.trimIndent(),
            )
        }
        val storageProfile = profile().copy(
            supportedFeatures = setOf("storage.local"),
            unavailableFeatures = setOf("storage.sync"),
        )

        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-storage-local"),
        ).use {
            it.prepare(resolvedSource(source), storageProfile, emptyList())
        }

        val decision = result.compatibilityAfter.adapterDecisions.single {
            it.id == MobilePatternAnalyzer.STORAGE_FALLBACK
        }
        assertTrue(decision.applied)
    }

    @Test
    fun `trusted action popup asset does not contaminate safe storage conversion`() {
        val source = convertibleSource(
            "six-pipeline-popup-storage",
            includePopup = true,
        )
        source.resolve("content.js").writeText(
            source.resolve("content.js").readText() +
                "\nchrome.storage.sync.get(\"theme\").then(console.log);\n",
        )
        val fallbackProfile = profile().copy(
            supportedFeatures = setOf(
                "storage.local",
                "extension.options-page.user-accessible",
            ),
            unavailableFeatures = setOf("storage.sync"),
        )

        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-popup-storage-local"),
        ).use {
            it.prepare(
                resolvedSource(source),
                fallbackProfile,
                listOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY),
            )
        }

        val decisions = result.compatibilityAfter.adapterDecisions.associateBy { it.id }
        assertTrue(decisions.getValue(MobilePatternAnalyzer.ACTION_POPUP_ENTRY).applied)
        val storage = decisions.getValue(MobilePatternAnalyzer.STORAGE_FALLBACK)
        assertEquals(AdapterDisposition.AUTOMATIC, storage.disposition)
        assertTrue(storage.applied)
    }

    @Test
    fun `conversion preserves an existing unselected unavailable decision`() {
        val source = convertibleSource(
            "six-pipeline-unavailable-preserved",
            includePopup = true,
        )
        source.resolve("content.js").writeText(
            source.resolve("content.js").readText() +
                "\nglobalThis.sixbridgeProbe = chrome.storage.sync;\n",
        )
        val fallbackProfile = profile().copy(
            supportedFeatures = setOf(
                "extension.options-page.user-accessible",
                "storage.local",
            ),
            unavailableFeatures = setOf("storage.sync"),
        )

        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-unavailable-local"),
        ).use {
            it.prepare(
                resolvedSource(source),
                fallbackProfile,
                listOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY),
            )
        }

        val before = result.compatibilityBefore.adapterDecisions.associateBy { it.id }
        val after = result.compatibilityAfter.adapterDecisions.associateBy { it.id }
        assertEquals(
            AdapterDisposition.AUTOMATIC,
            before.getValue(MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE).disposition,
        )
        assertEquals(
            AdapterDisposition.REQUIRES_APPROVAL,
            before.getValue(MobilePatternAnalyzer.ACTION_POPUP_ENTRY).disposition,
        )
        assertEquals(
            AdapterDisposition.UNAVAILABLE,
            before.getValue(MobilePatternAnalyzer.STORAGE_FALLBACK).disposition,
        )
        assertTrue(
            after.getValue(MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE).applied,
        )
        assertTrue(after.getValue(MobilePatternAnalyzer.ACTION_POPUP_ENTRY).applied)
        assertEquals(
            before.getValue(MobilePatternAnalyzer.STORAGE_FALLBACK),
            after.getValue(MobilePatternAnalyzer.STORAGE_FALLBACK),
        )
        assertFalse(after.getValue(MobilePatternAnalyzer.STORAGE_FALLBACK).applied)
    }

    @Test
    fun `conversion still rejects a truly new adapter decision`() {
        val before = MobilePatternAnalyzer().analyze(
            convertibleSource("six-pipeline-new-adapter-before"),
            profile(),
        ).adapterDecisions
        val applied = before.map { it.copy(applied = true) }
        val newDecision = AdapterDecision(
            id = MobilePatternAnalyzer.STORAGE_FALLBACK,
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.UNAVAILABLE,
            applied = false,
            evidence = emptyList(),
            changes = emptyList(),
            remainingLimitations = listOf("새 제한"),
        )

        assertFailsWith<ConversionValidationException> {
            reconcileAdapterDecisions(
                beforeDecisions = before,
                appliedDecisions = applied,
                rawAfterDecisions = listOf(newDecision),
            )
        }
    }

    @Test
    fun `reconciliation rejects added evidence on an unselected decision`() {
        assertFailsWith<ConversionValidationException> {
            reconcileWithStorageEvidence(
                listOf(
                    SourceEvidence("content.js", 1, "원본 근거"),
                    SourceEvidence("_sixbridge/generated.js", 2, "추가 근거"),
                ),
            )
        }
    }

    @Test
    fun `reconciliation rejects removed evidence on an unselected decision`() {
        assertFailsWith<ConversionValidationException> {
            reconcileWithStorageEvidence(emptyList())
        }
    }

    @Test
    fun `reconciliation rejects changed evidence on an unselected decision`() {
        assertFailsWith<ConversionValidationException> {
            reconcileWithStorageEvidence(
                listOf(SourceEvidence("content.js", 1, "변경된 근거")),
            )
        }
    }

    @Test
    fun `reconciliation rejects changed evidence path on an unselected decision`() {
        assertFailsWith<ConversionValidationException> {
            reconcileWithStorageEvidence(
                listOf(SourceEvidence("other.js", 1, "원본 근거")),
            )
        }
    }

    @Test
    fun `reconciliation rejects changed semantic fields on an unselected decision`() {
        val mutations = listOf<(AdapterDecision) -> AdapterDecision>(
            { it.copy(risk = AdapterRisk.BEHAVIOR_CHANGE) },
            { it.copy(disposition = AdapterDisposition.REQUIRES_APPROVAL) },
            { it.copy(changes = listOf("변경된 작업")) },
            { it.copy(remainingLimitations = listOf("변경된 제한")) },
        )

        mutations.forEach { mutation ->
            assertFailsWith<ConversionValidationException> {
                reconcileWithStorageDecision(mutation)
            }
        }
    }

    @Test
    fun `reconciliation preserves an unselected decision when only its evidence line moves`() {
        val result = reconcileWithStorageEvidence(
            listOf(SourceEvidence("content.js", 27, "원본 근거")),
        )

        assertEquals(
            SourceEvidence("content.js", 1, "원본 근거"),
            result.single { it.id == MobilePatternAnalyzer.STORAGE_FALLBACK }
                .evidence.single(),
        )
    }

    @Test
    fun `reconciliation rejects a duplicate raw decision with the same identity`() {
        val selected = AdapterDecision(
            id = MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.AUTOMATIC,
            applied = true,
            evidence = listOf(SourceEvidence("content.js", 1, "선택 근거")),
            changes = listOf("선택 변경"),
            remainingLimitations = emptyList(),
        )
        val unavailable = AdapterDecision(
            id = MobilePatternAnalyzer.STORAGE_FALLBACK,
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.UNAVAILABLE,
            applied = false,
            evidence = listOf(SourceEvidence("content.js", 1, "원본 근거")),
            changes = emptyList(),
            remainingLimitations = listOf("원본 제한"),
        )

        assertFailsWith<ConversionValidationException> {
            reconcileAdapterDecisions(
                beforeDecisions = listOf(selected.copy(applied = false), unavailable),
                appliedDecisions = listOf(selected),
                rawAfterDecisions = listOf(
                    selected,
                    unavailable.copy(evidence = emptyList()),
                    unavailable,
                ),
            )
        }
    }

    @Test
    fun `report hash is SHA-256 of final canonical compatibility bytes`() {
        val localRoot = Files.createTempDirectory("six-pipeline-report-hash")
        val result = ConversionPipeline(localRoot).use {
            it.prepare(
                resolvedSource(convertibleSource("six-pipeline-report-source")),
                profile(),
                emptyList(),
            )
        }
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest(
                dev.sixbridge.core.bundle.SixBundle
                    .encodeCompatibility(result.compatibilityAfter)
                    .toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, result.reportSha256)
    }

    @Test
    fun `missing unknown and inapplicable approvals have distinct failures`() {
        val localRoot = Files.createTempDirectory("six-pipeline-approval")
        val popup = resolvedSource(
            convertibleSource("six-pipeline-missing", includePopup = true),
        )
        val plain = resolvedSource(plainSource("six-pipeline-inapplicable"))

        val missing = assertFailsWith<MissingAdapterApprovalException> {
            ConversionPipeline(localRoot).use {
                it.prepare(popup, profile(), emptyList())
            }
        }
        val unknown = assertFailsWith<UnknownAdapterApprovalException> {
            ConversionPipeline(localRoot).use {
                it.prepare(popup, profile(), listOf("not-an-adapter"))
            }
        }
        val inapplicable = assertFailsWith<InapplicableAdapterApprovalException> {
            ConversionPipeline(localRoot).use {
                it.prepare(
                    plain,
                    profile(),
                    listOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY),
                )
            }
        }

        assertEquals(setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY), missing.adapterIds)
        assertEquals(setOf("not-an-adapter"), unknown.adapterIds)
        assertEquals(setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY), inapplicable.adapterIds)
    }

    @Test
    fun `converted report is reanalyzed without compatibility regression`() {
        val source = convertibleSource("six-pipeline-reanalysis")

        val result = ConversionPipeline(
            Files.createTempDirectory("six-pipeline-reanalysis-local"),
        ).use {
            it.prepare(resolvedSource(source), profile(), emptyList())
        }

        assertEquals(CompatibilityStatus.COMPATIBLE, result.compatibilityBefore.status)
        assertEquals(CompatibilityStatus.COMPATIBLE, result.compatibilityAfter.status)
        assertTrue(result.compatibilityAfter.issues.none { it.blocking })
        val extracted = Files.createTempDirectory("six-pipeline-source-zip")
        ExtensionZip.extract(result.sourceZip, extracted)
        assertTrue(
            "sixbridge/desktop-selection-event-bridge.v1.js" in
                extracted.resolve("manifest.json").readText(),
        )
        assertTrue(
            Files.isRegularFile(
                extracted.resolve("sixbridge/desktop-selection-event-bridge.v1.js"),
            ),
        )
        assertFalse(
            Files.exists(
                extracted.resolve("_sixbridge/desktop-selection-event-bridge.v1.js"),
            ),
        )
    }

    private fun plainSource(prefix: String) =
        Files.createTempDirectory(prefix).also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Plain","version":"1"}""",
            )
        }

    private fun convertibleSource(prefix: String, includePopup: Boolean = false) =
        Files.createTempDirectory(prefix).also { root ->
            root.resolve("content.js").writeText(
                """
                document.addEventListener("mouseup", (event) => {
                  toolbar.button.disabled = false;
                  const value = getSelection()?.toString();
                  if (value) console.log(value);
                });
                """.trimIndent(),
            )
            if (includePopup) root.resolve("popup.html").writeText("<p>popup</p>")
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Convertible",
                  "version": "1",
                  ${if (includePopup) "\"action\":{\"default_popup\":\"popup.html\"}," else ""}
                  "content_scripts": [{
                    "matches": ["https://example.com/*"],
                    "js": ["content.js"]
                  }]
                }
                """.trimIndent(),
            )
        }

    private fun resolvedSource(source: java.nio.file.Path) = ResolvedExtensionInput(
        kind = ExtensionInputKind.SOURCE_DIRECTORY,
        originalInput = source.toString(),
        sourceRoot = source,
    )

    private fun reconcileWithStorageEvidence(
        currentEvidence: List<SourceEvidence>,
    ): List<AdapterDecision> = reconcileWithStorageDecision {
        it.copy(evidence = currentEvidence)
    }

    private fun reconcileWithStorageDecision(
        mutate: (AdapterDecision) -> AdapterDecision,
    ): List<AdapterDecision> {
        val selected = AdapterDecision(
            id = MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.AUTOMATIC,
            applied = false,
            evidence = listOf(SourceEvidence("content.js", 1, "선택 근거")),
            changes = listOf("선택 변경"),
            remainingLimitations = emptyList(),
        )
        val unavailable = AdapterDecision(
            id = MobilePatternAnalyzer.STORAGE_FALLBACK,
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.UNAVAILABLE,
            applied = false,
            evidence = listOf(SourceEvidence("content.js", 1, "원본 근거")),
            changes = emptyList(),
            remainingLimitations = listOf("원본 제한"),
        )
        val applied = selected.copy(applied = true)
        return reconcileAdapterDecisions(
            beforeDecisions = listOf(selected, unavailable),
            appliedDecisions = listOf(applied),
            rawAfterDecisions = listOf(
                applied,
                mutate(unavailable),
            ),
        )
    }

    private fun keyPair() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun assertPipelineCancellation(
        operation: (ConversionPipeline, AnalysisControl) -> Unit,
    ) {
        listOf(1, 2, 3).forEach { cancellationCheckpoint ->
            val engine = RecordingPipelineEngine()
            val pipeline = ConversionPipeline.create(
                localRoot = Files.createTempDirectory("six-pipeline-cancel"),
                engine = engine,
            )
            var checkpoints = 0
            val control = AnalysisControl(AnalysisSafetyPolicy.desktop) {
                checkpoints++
                if (checkpoints == cancellationCheckpoint) {
                    throw CancellationException("cancel")
                }
            }
            try {
                assertFailsWith<CancellationException> { operation(pipeline, control) }
                assertEquals(cancellationCheckpoint, checkpoints)
                assertEquals(
                    if (cancellationCheckpoint == 3) 1 else 0,
                    engine.operationCalls,
                )
                if (cancellationCheckpoint == 3) {
                    assertSame(control, engine.observedControl)
                }
            } finally {
                pipeline.close()
            }
        }
    }

    private class RecordingPipelineEngine(
        private val closeFailure: Throwable? = null,
        private val onClose: () -> Unit = {},
    ) : ConversionPipelineEngine {
        var operationCalls = 0
            private set
        var observedControl: AnalysisControl? = null
            private set

        override fun analyzeWithIdentity(
            input: PortableExtensionInput,
            profile: BrowserProfile,
            control: AnalysisControl,
        ): AnalyzedConversion {
            operationCalls++
            observedControl = control
            control.checkCancelled()
            error("engine analyze가 취소 checkpoint 뒤 계속 실행됐습니다.")
        }

        override fun prepare(
            input: PortableExtensionInput,
            profile: BrowserProfile,
            approvedAdapterIds: Iterable<String>,
            control: AnalysisControl,
        ): PreparedConversion {
            operationCalls++
            observedControl = control
            control.checkCancelled()
            error("engine prepare가 취소 checkpoint 뒤 계속 실행됐습니다.")
        }

        override fun bindIdentity(
            prepared: PreparedConversion,
            profile: BrowserProfile,
            publicKeyDer: ByteArray?,
        ): BoundConversion = error("취소 전달 테스트에서 bindIdentity를 호출하면 안 됩니다.")

        override fun close() {
            onClose()
            closeFailure?.let { throw it }
        }
    }

    private class RecordingPipelineMaterialization(
        private val closeFailure: Throwable,
        private val onClose: () -> Unit,
    ) : ConversionPipelineMaterialization {
        override fun snapshot(sourceZip: ByteArray): ExtensionSnapshot =
            error("정리 테스트에서 snapshot을 호출하면 안 됩니다.")

        override fun close() {
            onClose()
            throw closeFailure
        }
    }

    private fun packSignedZip(
        zipBytes: ByteArray,
        keyPair: java.security.KeyPair,
    ): dev.sixbridge.core.crx.PackedCrx {
        val packZip = Crx3::class.java.getDeclaredMethod(
            "packZip",
            ByteArray::class.java,
            java.security.KeyPair::class.java,
        ).apply { isAccessible = true }
        return packZip.invoke(Crx3, zipBytes, keyPair)
            as dev.sixbridge.core.crx.PackedCrx
    }

    private fun profile() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = BrowserProfile.BASELINE_VERSION,
        versionCode = 1_300_261_502,
        majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
        supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        supportedFeatures = setOf("extension.options-page.user-accessible"),
    )
}
