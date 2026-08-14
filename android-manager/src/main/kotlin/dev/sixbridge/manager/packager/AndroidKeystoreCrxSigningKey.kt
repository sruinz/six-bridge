package dev.sixbridge.manager.packager

import dev.sixbridge.contract.crx.Crx3SigningKey
import dev.sixbridge.manager.conversion.withConversionKeyStoreBoundary
import java.io.InputStream
import java.security.PrivateKey
import java.security.Signature

class AndroidKeystoreCrxSigningKey private constructor(
    private val privateKey: PrivateKey,
    private val publicKeyDerSource: () -> ByteArray,
) : Crx3SigningKey {
    constructor(identity: AndroidSigningIdentity) : this(
        privateKey = identity.privateKey,
        publicKeyDerSource = { identity.certificate.publicKey.encoded.copyOf() },
    )

    internal constructor(privateKey: PrivateKey, publicKeyDer: ByteArray) : this(
        privateKey = privateKey,
        publicKeyDerSource = { publicKeyDer.copyOf() },
    )

    override fun publicKeyDer(): ByteArray = withConversionKeyStoreBoundary {
        publicKeyDerSource()
    }

    override fun signSha256Rsa(payload: ByteArray): ByteArray =
        withConversionKeyStoreBoundary {
            Signature.getInstance("SHA256withRSA").run {
                initSign(privateKey)
                update(payload)
                sign()
            }
        }

    override fun signSha256Rsa(payload: InputStream): ByteArray {
        val signature = withConversionKeyStoreBoundary {
            Signature.getInstance("SHA256withRSA").apply {
                initSign(privateKey)
            }
        }
        val buffer = ByteArray(64 * 1024)
        payload.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0) { "CRX3 서명 stream 읽기가 진행되지 않습니다." }
                withConversionKeyStoreBoundary {
                    signature.update(buffer, 0, count)
                }
            }
        }
        return withConversionKeyStoreBoundary(signature::sign)
    }
}
