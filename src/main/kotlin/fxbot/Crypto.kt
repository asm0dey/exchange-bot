package fxbot

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Mac
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.mac.MacConfig
import com.google.crypto.tink.mac.PredefinedMacParameters
import java.security.SecureRandom
import java.util.Base64

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
        aead = parse(dataKeysetJson).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        mac = parse(indexKeysetJson).getPrimitive(RegistryConfiguration.get(), Mac::class.java)
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
