package dev.sixbridge.core.crx

import dev.sixbridge.core.conversion.ConversionPipeline
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedCrxPayloadTest {
    @Test
    fun `verified payload packer is not a public Java byte array bypass`() {
        val methods = Crx3::class.java.declaredMethods
            .filter { it.name.contains("packVerified") }

        assertTrue(methods.isNotEmpty())
        assertTrue(methods.all { it.isSynthetic })
        assertFalse(
            methods.any { method ->
                Modifier.isPublic(method.modifiers) &&
                    method.parameterTypes.any { it == ByteArray::class.java }
            },
        )
        assertFalse(
            ConversionPipeline::class.java.methods.any { method ->
                method.returnType == ByteArray::class.java &&
                    method.name.contains("proof", ignoreCase = true)
            },
        )
    }
}
