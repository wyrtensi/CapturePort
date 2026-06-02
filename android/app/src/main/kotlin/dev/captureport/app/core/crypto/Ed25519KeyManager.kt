package dev.captureport.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import android.util.Base64

object Ed25519KeyManager {
    private const val KEY_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "captureport_auth_key"
    private const val ALGORITHM = "Ed25519"

    // Generates or retrieves the Ed25519 keypair inside AndroidKeyStore (API 33+)
    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }
        
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
            val publicKey = keyStore.getCertificate(KEY_ALIAS).publicKey
            return KeyPair(publicKey, privateKey)
        }

        // On Android 13 (API 33)+, Ed25519 KeyPairGenerator inside KeyStore is fully supported
        val kpg = KeyPairGenerator.getInstance(ALGORITHM, KEY_PROVIDER)
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).setDigests(KeyProperties.DIGEST_NONE)
         .build()

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    // Sign the random PC challenge nonce using the private key
    fun signChallenge(challengeBytes: ByteArray): ByteArray {
        val keyPair = getOrCreateKeyPair()
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(challengeBytes)
        return signature.sign()
    }

    // Get the raw 32-byte public key representation
    fun getRawPublicKey(): ByteArray {
        val keyPair = getOrCreateKeyPair()
        // In Java, Ed25519 public keys encode to standard X.509 format.
        // The raw 32 bytes reside at the end of the 44-byte encoded array.
        val encoded = keyPair.public.encoded
        return if (encoded.size >= 32) {
            encoded.takeLast(32).toByteArray()
        } else {
            encoded
        }
    }
}
