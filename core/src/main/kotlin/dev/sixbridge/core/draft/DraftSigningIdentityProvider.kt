package dev.sixbridge.core.draft

import java.security.KeyPair

fun interface DraftSigningIdentityProvider {
    fun getOrCreate(): KeyPair
}
