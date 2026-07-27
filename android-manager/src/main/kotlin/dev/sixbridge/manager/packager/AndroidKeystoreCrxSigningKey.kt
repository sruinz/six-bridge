package dev.sixbridge.manager.packager

import dev.sixbridge.contract.crx.Crx3SigningKey
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
}
