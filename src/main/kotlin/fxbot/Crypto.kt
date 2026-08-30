package fxbot

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyStatus
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Mac
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AesGcmParameters
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.mac.HmacParameters
import com.google.crypto.tink.mac.MacConfig
import com.google.crypto.tink.mac.PredefinedMacParameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

/** AES-256-GCM per ADR 0002; a weaker AEAD would satisfy Tink's parse but not the design. */
private const val MANDATED_AEAD_KEY_SIZE_BYTES = 32

/** HMAC-SHA256 per ADR 0002, at least 256 bits of key material. */
private const val MANDATED_MAC_KEY_SIZE_BYTES = 32

private val B64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val RANDOM = SecureRandom()

/**
 * The two keysets never share key material: [seal]/[open] protect the payload,
 * [ref] derives the searchable identifiers. See ADR 0002.
 *
 * The two keysets rotate differently. The AEAD keyset rotates freely: Tink
 * keeps retired keys in the keyset, so ciphertexts sealed under an old key
 * still [open] correctly after a new primary key is added. The MAC keyset
 * does NOT rotate for free: [ref] is used only for deterministic string
 * equality lookups (`chat_ref` / `user_ref`), never `verifyMac`, so rotating
 * the index key changes every stored ref and those rows silently stop
 * matching — no exception, just no results. Rotating it is a deliberate
 * one-off job (decrypt each sealed payload, re-derive both refs, rewrite
 * them), not an operation this class performs automatically. See ADR 0002.
 */
class Crypto(dataKeysetJson: String, indexKeysetJson: String) {
    private val aead: Aead
    private val mac: Mac

    init {
        AeadConfig.register()
        MacConfig.register()
        val dataHandle = parse(dataKeysetJson)
        val indexHandle = parse(indexKeysetJson)
        requireAes256Gcm(dataHandle, "DATA_KEYSET")
        requireHmacSha256(indexHandle, "INDEX_KEYSET")
        aead = dataHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        mac = indexHandle.getPrimitive(RegistryConfiguration.get(), Mac::class.java)
    }

    fun seal(plaintext: String, aad: String): ByteArray =
        aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), aad.toByteArray(Charsets.UTF_8))

    fun open(ciphertext: ByteArray, aad: String): String =
        String(aead.decrypt(ciphertext, aad.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    /** Deterministic and keyed. Stored in a TEXT column — no width is assumed. */
    fun ref(value: String): String =
        Base64.getEncoder().encodeToString(mac.computeMac(value.toByteArray(Charsets.UTF_8)))

    private fun parse(json: String): KeysetHandle =
        TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get())

    /**
     * Tink only confirms a keyset can produce an [Aead] / [Mac] primitive, not that it uses the
     * algorithm this design mandates. A keyset holds several keys so retired ones can still
     * [open]/decrypt after rotation, so every ENABLED key is checked here, not just the primary
     * -- a weak non-primary key introduced by a rotation would still accept and decrypt data.
     * DISABLED/DESTROYED keys carry no cryptographic capability and are skipped.
     */
    private fun requireAes256Gcm(handle: KeysetHandle, keysetName: String) {
        for (i in 0 until handle.size()) {
            val entry = handle.getAt(i)
            if (entry.status != KeyStatus.ENABLED) continue
            val params = entry.key.parameters
            if (params !is AesGcmParameters || params.keySizeBytes != MANDATED_AEAD_KEY_SIZE_BYTES) {
                throw GeneralSecurityException("$keysetName must contain only AES-256-GCM keys")
            }
        }
    }

    private fun requireHmacSha256(handle: KeysetHandle, keysetName: String) {
        for (i in 0 until handle.size()) {
            val entry = handle.getAt(i)
            if (entry.status != KeyStatus.ENABLED) continue
            val params = entry.key.parameters
            if (params !is HmacParameters ||
                params.hashType != HmacParameters.HashType.SHA256 ||
                params.keySizeBytes < MANDATED_MAC_KEY_SIZE_BYTES
            ) {
                throw GeneralSecurityException(
                    "$keysetName must contain only HMAC-SHA256 keys with at least a 256-bit key",
                )
            }
        }
    }
}

/** 128 bits of randomness, base64url, never shown in a chat. */
fun newRefToken(): String = B64URL.encodeToString(ByteArray(16).also(RANDOM::nextBytes))

/**
 * Generates keysets equivalent to `tinkey create-keyset`. Used by tests, and by
 * the `keygen` helper so a first deploy needs no extra tooling.
 */
object KeysetGen {
    fun aead(): String {
        AeadConfig.register()
        return serialize(KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM))
    }

    fun mac(): String {
        MacConfig.register()
        return serialize(KeysetHandle.generateNew(PredefinedMacParameters.HMAC_SHA256_256BITTAG))
    }

    private fun serialize(handle: KeysetHandle): String =
        TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
}
