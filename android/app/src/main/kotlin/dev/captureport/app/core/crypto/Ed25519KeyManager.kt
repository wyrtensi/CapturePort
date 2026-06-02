package dev.captureport.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object Ed25519KeyManager {
    private const val KEY_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "captureport_auth_ed25519_v1"
    private const val LEGACY_KEY_ALIAS = "captureport_auth_key"
    private const val ALGORITHM = "Ed25519"
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30,
        0x2A,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2B,
        0x65,
        0x70,
        0x03,
        0x21,
        0x00
    )

    // Generates or retrieves the Ed25519 keypair inside AndroidKeyStore (API 33+)
    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }

        loadUsableKeyPair(keyStore, KEY_ALIAS)?.let { return it }
        loadUsableKeyPair(keyStore, LEGACY_KEY_ALIAS)?.let { return it }

        deleteAliasIfExists(keyStore, KEY_ALIAS)
        val keyPair = generateKeyPair(KEY_ALIAS)
        if (isUsableEd25519KeyPair(keyPair)) {
            return keyPair
        }

        deleteAliasIfExists(keyStore, KEY_ALIAS)
        error("Failed to generate a valid Ed25519 key pair in AndroidKeyStore")
    }

    private fun loadUsableKeyPair(keyStore: KeyStore, alias: String): KeyPair? {
        if (!keyStore.containsAlias(alias)) {
            return null
        }

        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
        val publicKey = keyStore.getCertificate(alias)?.publicKey

        if (privateKey == null || publicKey == null) {
            deleteAliasIfExists(keyStore, alias)
            return null
        }

        val keyPair = KeyPair(publicKey, privateKey)
        return if (isUsableEd25519KeyPair(keyPair)) {
            keyPair
        } else {
            deleteAliasIfExists(keyStore, alias)
            null
        }
    }

    private fun generateKeyPair(alias: String): KeyPair {
        // On Android 13 (API 33)+, Ed25519 KeyPairGenerator inside KeyStore is fully supported
        val kpg = KeyPairGenerator.getInstance(ALGORITHM, KEY_PROVIDER)
        val parameterSpec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).setDigests(KeyProperties.DIGEST_NONE)
         .build()

        kpg.initialize(parameterSpec)
        return kpg.generateKeyPair()
    }

    private fun deleteAliasIfExists(keyStore: KeyStore, alias: String) {
        if (keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
        }
    }

    private fun isUsableEd25519KeyPair(keyPair: KeyPair): Boolean {
        if (!keyPair.public.algorithm.equals(ALGORITHM, ignoreCase = true)) {
            return false
        }

        if (!keyPair.private.algorithm.equals(ALGORITHM, ignoreCase = true)) {
            return false
        }

        val rawPublicKey = extractRawPublicKey(keyPair.public) ?: return false
        val probeMessage = byteArrayOf(1, 2, 3, 4)
        val probeSignature = runCatching {
            signWithPrivateKey(keyPair.private, probeMessage)
        }.getOrNull() ?: return false

        if (rawPublicKey.size != 32 || probeSignature.size != 64) {
            return false
        }

        return verifyWithExportedRawPublicKey(rawPublicKey, probeMessage, probeSignature)
    }

    private fun signWithPrivateKey(privateKey: PrivateKey, challengeBytes: ByteArray): ByteArray {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(privateKey)
        signature.update(challengeBytes)
        return signature.sign()
    }

    private fun extractRawPublicKey(publicKey: PublicKey): ByteArray? {
        val encoded = publicKey.encoded ?: return null

        if (encoded.size == ED25519_SPKI_PREFIX.size + 32 && encoded.copyOfRange(0, ED25519_SPKI_PREFIX.size).contentEquals(ED25519_SPKI_PREFIX)) {
            return encoded.copyOfRange(ED25519_SPKI_PREFIX.size, encoded.size)
        }

        return if (encoded.size >= 32) {
            encoded.takeLast(32).toByteArray()
        } else {
            null
        }
    }

    private fun verifyWithExportedRawPublicKey(rawPublicKey: ByteArray, message: ByteArray, signatureBytes: ByteArray): Boolean {
        val encodedPublicKey = ED25519_SPKI_PREFIX + rawPublicKey

        return runCatching {
            val keyFactory = KeyFactory.getInstance(ALGORITHM)
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(encodedPublicKey))
            val verifier = Signature.getInstance(ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(message)
            verifier.verify(signatureBytes)
        }.getOrDefault(false)
    }

    // Sign the random PC challenge nonce using the private key
    fun signChallenge(challengeBytes: ByteArray): ByteArray {
        val keyPair = getOrCreateKeyPair()
        return signWithPrivateKey(keyPair.private, challengeBytes)
    }

    // Get the raw 32-byte public key representation
    fun getRawPublicKey(): ByteArray {
        val keyPair = getOrCreateKeyPair()
        return extractRawPublicKey(keyPair.public)
            ?: error("Failed to extract raw Ed25519 public key from AndroidKeyStore")
    }
}
