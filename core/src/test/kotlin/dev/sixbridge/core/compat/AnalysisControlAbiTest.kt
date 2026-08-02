package dev.sixbridge.core.compat

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.jvm.internal.DefaultConstructorMarker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisControlAbiTest {
    @Test
    fun `base Kotlin default constructor client가 head runtime에서 link된다`() {
        val client = OldAnalysisControlClientLoader(javaClass.classLoader)
            .define(oldAnalysisControlClientBytes())

        client.getMethod("run").invoke(null)

        val defaultConstructor = AnalysisControl::class.java.getConstructor(
            AnalysisSafetyPolicy::class.java,
            kotlin.Function0::class.java,
            Int::class.javaPrimitiveType,
            DefaultConstructorMarker::class.java,
        )
        assertTrue(defaultConstructor.isSynthetic)
        assertEquals(
            setOf(
                emptyList(),
                listOf(AnalysisSafetyPolicy::class.java, kotlin.Function0::class.java),
                listOf(
                    AnalysisSafetyPolicy::class.java,
                    kotlin.Function0::class.java,
                    Int::class.javaPrimitiveType,
                    DefaultConstructorMarker::class.java,
                ),
            ),
            AnalysisControl::class.java.constructors.map { it.parameterTypes.toList() }.toSet(),
        )
    }

    @Test
    fun `public source는 noarg 1arg 2arg trailing lambda를 유지한다`() {
        val controls = listOf(
            AnalysisControl(),
            AnalysisControl(AnalysisSafetyPolicy.mobile),
            AnalysisControl(AnalysisSafetyPolicy.mobile) {},
            AnalysisControl {},
        )

        assertEquals(4, controls.size)
        controls.forEach(AnalysisControl::checkCancelled)
    }

    private class OldAnalysisControlClientLoader(parent: ClassLoader) : ClassLoader(parent) {
        fun define(bytes: ByteArray): Class<*> = defineClass(null, bytes, 0, bytes.size)
    }

    /**
     * Base 51e0dc7 API에 대해 `fun run() { AnalysisControl() }`를 컴파일한 호출과
     * 동일한 fixed JVM linkage fixture다.
     */
    private fun oldAnalysisControlClientBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeInt(0xCAFEBABE.toInt())
            data.writeShort(0)
            data.writeShort(61)
            data.writeShort(16)
            data.writeUtf8("dev/sixbridge/core/compat/OldAnalysisControlClient") // 1
            data.writeClass(1) // 2
            data.writeUtf8("java/lang/Object") // 3
            data.writeClass(3) // 4
            data.writeUtf8("<init>") // 5
            data.writeUtf8("()V") // 6
            data.writeNameAndType(5, 6) // 7
            data.writeMethodRef(4, 7) // 8
            data.writeUtf8("run") // 9
            data.writeUtf8("Code") // 10
            data.writeUtf8("dev/sixbridge/core/compat/AnalysisControl") // 11
            data.writeClass(11) // 12
            data.writeUtf8(
                "(Ldev/sixbridge/core/compat/AnalysisSafetyPolicy;" +
                    "Lkotlin/jvm/functions/Function0;I" +
                    "Lkotlin/jvm/internal/DefaultConstructorMarker;)V",
            ) // 13
            data.writeNameAndType(5, 13) // 14
            data.writeMethodRef(12, 14) // 15
            data.writeShort(0x0031)
            data.writeShort(2)
            data.writeShort(4)
            data.writeShort(0)
            data.writeShort(0)
            data.writeShort(2)
            data.writeMethod(5, 6, maxStack = 1, maxLocals = 1, byteArrayOf(
                0x2a, 0xb7.toByte(), 0x00, 0x08, 0xb1.toByte(),
            ))
            data.writeMethod(9, 6, maxStack = 6, maxLocals = 0, byteArrayOf(
                0xbb.toByte(), 0x00, 0x0c, 0x59, 0x01, 0x01, 0x06, 0x01,
                0xb7.toByte(), 0x00, 0x0f, 0x57, 0xb1.toByte(),
            ), access = 0x0009)
            data.writeShort(0)
        }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        writeByte(1)
        writeUTF(value)
    }

    private fun DataOutputStream.writeClass(nameIndex: Int) {
        writeByte(7)
        writeShort(nameIndex)
    }

    private fun DataOutputStream.writeNameAndType(nameIndex: Int, descriptorIndex: Int) {
        writeByte(12)
        writeShort(nameIndex)
        writeShort(descriptorIndex)
    }

    private fun DataOutputStream.writeMethodRef(classIndex: Int, nameAndTypeIndex: Int) {
        writeByte(10)
        writeShort(classIndex)
        writeShort(nameAndTypeIndex)
    }

    private fun DataOutputStream.writeMethod(
        nameIndex: Int,
        descriptorIndex: Int,
        maxStack: Int,
        maxLocals: Int,
        code: ByteArray,
        access: Int = 0x0001,
    ) {
        writeShort(access)
        writeShort(nameIndex)
        writeShort(descriptorIndex)
        writeShort(1)
        writeShort(10)
        writeInt(12 + code.size)
        writeShort(maxStack)
        writeShort(maxLocals)
        writeInt(code.size)
        write(code)
        writeShort(0)
        writeShort(0)
    }
}
