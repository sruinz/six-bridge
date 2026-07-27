package dev.sixbridge.manager.packager

import dev.sixbridge.contract.crx.Crx3SigningKey
import java.io.InputStream
import java.security.Signature

class AndroidKeystoreCrxSigningKey(
    private val identity: AndroidSigningIdentity,
) : Crx3SigningKey {
    override fun publicKeyDer(): ByteArray = identity.certificate.publicKey.encoded.copyOf()

    override fun signSha256Rsa(payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(identity.privateKey)
            update(payload)
            sign()
        }

    override fun signSha256Rsa(payload: InputStream): ByteArray =
        Signature.getInstance("SHA256withRSA").run {
            initSign(identity.privateKey)
            val buffer = ByteArray(64 * 1024)
            payload.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0) { "CRX3 서명 stream 읽기가 진행되지 않습니다." }
                    update(buffer, 0, count)
                }
            }
            sign()
        }
}
