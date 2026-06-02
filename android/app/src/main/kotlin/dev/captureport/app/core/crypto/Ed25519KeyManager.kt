package dev.captureport.app.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
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

    private var softwareKeyPair: KeyPair? = null

    private fun getOrGenerateSoftwareKeyPair(): KeyPair {
        synchronized(this) {
            softwareKeyPair?.let { return it }

            val filesDir = dev.captureport.app.CapturePortApp.instance.filesDir
            val privFile = File(filesDir, "captureport_ed25519_priv.key")
            val pubFile = File(filesDir, "captureport_ed25519_pub.key")

            if (privFile.exists() && pubFile.exists()) {
                try {
                    val privBytes = privFile.readBytes()
                    val pubBytes = pubFile.readBytes()

                    val kf = KeyFactory.getInstance(ALGORITHM)
                    val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                    val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))

                    val kp = KeyPair(pubKey, privKey)
                    softwareKeyPair = kp
                    return kp
                } catch (e: Throwable) {
                    Log.e("Ed25519KeyManager", "Failed to load stored software keypair: ${e.message}", e)
                    privFile.delete()
                    pubFile.delete()
                }
            }

            // Generate a new software keypair
            try {
                val kpg = KeyPairGenerator.getInstance(ALGORITHM)
                val kp = kpg.generateKeyPair()
                
                privFile.writeBytes(kp.private.encoded)
                pubFile.writeBytes(kp.public.encoded)

                softwareKeyPair = kp
                Log.i("Ed25519KeyManager", "Successfully generated and stored a software Ed25519 keypair as fallback")
                return kp
            } catch (e: Throwable) {
                Log.e("Ed25519KeyManager", "Failed to generate software Ed25519 keypair: ${e.message}", e)
                throw e
            }
        }
    }

    // Generates or retrieves the Ed25519 keypair inside AndroidKeyStore (API 33+) with software fallback
    @Synchronized
    fun getOrCreateKeyPair(): KeyPair {
        try {
            val keyStore = KeyStore.getInstance(KEY_PROVIDER).apply { load(null) }

            loadUsableKeyPair(keyStore, KEY_ALIAS)?.let { return it }
            loadUsableKeyPair(keyStore, LEGACY_KEY_ALIAS)?.let { return it }

            deleteAliasIfExists(keyStore, KEY_ALIAS)
            val keyPair = generateKeyPair(KEY_ALIAS)
            if (isStructurallyUsable(keyPair)) {
                return keyPair
            }

            deleteAliasIfExists(keyStore, KEY_ALIAS)
        } catch (e: Throwable) {
            Log.w("Ed25519KeyManager", "AndroidKeyStore initialization failed, falling back to software key: ${e.message}", e)
        }

        // Software fallback
        return getOrGenerateSoftwareKeyPair()
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
        return if (isStructurallyUsable(keyPair)) {
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

    // Structural sanity check: the KeyStore entry must expose an Ed25519 keypair whose
    // public component encodes to a 32-byte raw key. We deliberately do NOT round-trip a
    // probe signature through the default JCE provider here, because KeyFactory /
    // Signature for "Ed25519" outside AndroidKeyStore is brittle across API 33/34 OEM
    // builds and was the root cause of v0.1.6's "Failed to generate a valid Ed25519 key
    // pair" crash. The PC validates the actual signature end-to-end via ed25519-dalek.
    private fun isStructurallyUsable(keyPair: KeyPair): Boolean {
        if (!keyPair.public.algorithm.equals(ALGORITHM, ignoreCase = true)) {
            return false
        }

        if (!keyPair.private.algorithm.equals(ALGORITHM, ignoreCase = true)) {
            return false
        }

        val rawPublicKey = extractRawPublicKey(keyPair.public) ?: return false
        return rawPublicKey.size == 32
    }

    // Optional self-test used only as a diagnostic. Never causes key regeneration to fail.
    // Returns true when a probe signature can be both produced by the AndroidKeyStore
    // private key and verified against the exported raw public key via the default JCE.
    // A false return value does NOT mean the keypair is broken — the PC will verify the
    // real challenge signature using ed25519-dalek.
    private fun runOptionalSelfTest(keyPair: KeyPair): Boolean {
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
            ?: error("Failed to extract raw Ed25519 public key")
    }
}
