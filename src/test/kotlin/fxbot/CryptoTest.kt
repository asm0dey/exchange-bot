package fxbot

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CryptoTest : StringSpec({
    val crypto = Crypto(KeysetGen.aead(), KeysetGen.mac())

    "round-trips a payload under its associated data" {
        val sealed = crypto.seal("""{"username":"alice"}""", "tok-1")
        crypto.open(sealed, "tok-1") shouldBe """{"username":"alice"}"""
    }
    "refuses to open under different associated data" {
        val sealed = crypto.seal("secret", "tok-1")
        shouldThrowAny { crypto.open(sealed, "tok-2") }
    }
    "refuses to open a tampered ciphertext" {
        val sealed = crypto.seal("secret", "tok-1")
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        shouldThrowAny { crypto.open(sealed, "tok-1") }
    }
    "refuses to open under a different key" {
        val other = Crypto(KeysetGen.aead(), KeysetGen.mac())
        shouldThrowAny { other.open(crypto.seal("secret", "tok-1"), "tok-1") }
    }
    "encrypts the same plaintext to different ciphertexts" {
        crypto.seal("same", "tok-1") shouldNotBe crypto.seal("same", "tok-1")
    }
    "refs are deterministic and key-dependent" {
        crypto.ref("-1001") shouldBe crypto.ref("-1001")
        crypto.ref("-1001") shouldNotBe crypto.ref("-1002")
        Crypto(KeysetGen.aead(), KeysetGen.mac()).ref("-1001") shouldNotBe crypto.ref("-1001")
    }
    "a ref is a stable non-empty string" {
        // Columns are TEXT, so no width is asserted — Tink prepends a key-identity
        // prefix whose size is not ours to hard-code.
        crypto.ref("-1001").isNotEmpty() shouldBe true
        crypto.ref("-1001") shouldBe crypto.ref("-1001")
    }
    "ref tokens are 22 characters and unique" {
        val tokens = List(1000) { newRefToken() }
        tokens.toSet().size shouldBe 1000
        tokens.all { it.length == 22 } shouldBe true
    }
})
