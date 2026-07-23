package dev.sixbridge.core

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SixBridgeServiceTest {
    @Test
    fun `keeps CRX identity when the same manifest moves or its zip is renamed`() {
        val workspace = Files.createTempDirectory("six-identity-workspace")
        val first = source("six-identity-first", "Stable identity")
        val second = source("six-identity-second", "Stable identity")
        val service = SixBridgeService(workspace)

        val firstId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                first.toString(),
                first,
            ),
        )
        val movedId = prepare(
            service,
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                second.toString(),
                second,
            ),
        )
        val firstZip = workspace.resolve("first-name.zip")
        val secondZip = workspace.resolve("renamed.zip")
        ExtensionZip.create(first, firstZip)
        ExtensionZip.create(first, secondZip)
        val resolver = ExtensionInputResolver()
        val zippedId = prepare(service, resolver.resolve(firstZip.toString()))
        val renamedZipId = prepare(service, resolver.resolve(secondZip.toString()))

        assertEquals(firstId, movedId)
        assertEquals(firstId, zippedId)
        assertEquals(firstId, renamedZipId)
    }

    @Test
    fun `rejects a manifest key when its private key does not exist`() {
        val workspace = Files.createTempDirectory("six-keyless-workspace")
        val publicKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public
            .encoded
        val source = keyedSource("six-keyless-source", publicKey)

        assertFailsWith<IllegalArgumentException> {
            prepare(
                SixBridgeService(workspace),
                ResolvedExtensionInput(
                    ExtensionInputKind.SOURCE_DIRECTORY,
                    source.toString(),
                    source,
                ),
            )
        }
    }

    @Test
    fun `uses the existing private key only when manifest key and packed id match`() {
        val workspace = Files.createTempDirectory("six-keyed-workspace")
        val key = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        val extensionId = Crx3.extensionId(key.public.encoded)
        ExtensionKeyManager(workspace.resolve(".local/keystore/crx"))
            .persist(extensionId, key)
        val source = keyedSource("six-keyed-source", key.public.encoded)

        val packedId = prepare(
            SixBridgeService(workspace),
            ResolvedExtensionInput(
                ExtensionInputKind.SOURCE_DIRECTORY,
                source.toString(),
                source,
            ),
        )

        assertEquals(extensionId, packedId)
    }

    private fun source(prefix: String, name: String) =
        Files.createTempDirectory(prefix).also { source ->
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"$name","version":"1"}""",
            )
        }

    private fun keyedSource(prefix: String, publicKey: ByteArray) =
        Files.createTempDirectory(prefix).also { source ->
            source.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Keyed",
                  "version": "1",
                  "key": "${Base64.getEncoder().encodeToString(publicKey)}"
                }
                """.trimIndent(),
            )
        }

    private fun prepare(
        service: SixBridgeService,
        resolved: ResolvedExtensionInput,
    ): String {
        val method = SixBridgeService::class.java.getDeclaredMethod(
            "prepareCrx",
            ResolvedExtensionInput::class.java,
            java.nio.file.Path::class.java,
        ).apply { isAccessible = true }
        val packed = try {
            method.invoke(service, resolved, resolved.sourceRoot)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
        val field = packed.javaClass.getDeclaredField("extensionId")
            .apply { isAccessible = true }
        return field.get(packed) as String
    }
}
