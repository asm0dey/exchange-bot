package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val TOKEN = "123456:TEST-token"
private val NOW = Instant.parse("2026-09-27T12:00:00Z")
private val CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)

private fun hmac(key: ByteArray, data: String): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

/** Signs [fields] the way Telegram documents it, independently of the code under test. */
private fun signed(fields: Map<String, String>, token: String = TOKEN): String {
    val secret = hmac("WebAppData".toByteArray(), token)
    val dcs = fields.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
    val hash = hmac(secret, dcs).joinToString("") { "%02x".format(it) }
    return (fields + ("hash" to hash)).entries.joinToString("&") {
        "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
    }
}

private val USER = """{"id":42,"first_name":"Ann","username":"ann_k"}"""
private fun base(authDate: Instant = NOW.minusSeconds(60)) = mapOf(
    "auth_date" to authDate.epochSecond.toString(),
    "query_id" to "AAH",
    "user" to USER,
    "start_param" to "c-1001",
)

class MiniAppAuthTest : StringSpec({
    val verifier = InitDataVerifier(TOKEN, CLOCK)

    "a correctly signed initData yields the viewer" {
        verifier.verify(signed(base())) shouldBe Viewer(42, "ann_k", "c-1001")
    }
    "a changed field after signing is refused" {
        val raw = signed(base()).replace("ann_k", "bob")
        verifier.verify(raw).shouldBeNull()
    }
    "a different bot token's signature is refused" {
        verifier.verify(signed(base(), token = "999:other")).shouldBeNull()
    }
    "a missing hash is refused" {
        verifier.verify(signed(base()).substringBefore("&hash=")).shouldBeNull()
    }
    "an auth_date older than 24 hours is refused" {
        verifier.verify(signed(base(NOW.minusSeconds(24 * 3600 + 1)))).shouldBeNull()
    }
    "an auth_date more than 5 minutes in the future is refused" {
        verifier.verify(signed(base(NOW.plusSeconds(301)))).shouldBeNull()
    }
    "a duplicated key is refused" {
        verifier.verify(signed(base()) + "&user=" + URLEncoder.encode(USER, Charsets.UTF_8)).shouldBeNull()
    }
    "garbage is refused without throwing" {
        verifier.verify("%%%").shouldBeNull()
        verifier.verify("").shouldBeNull()
    }
    "a viewer without a username is still a viewer" {
        val f = base() + ("user" to """{"id":7,"first_name":"Ivan"}""")
        verifier.verify(signed(f)) shouldBe Viewer(7, null, "c-1001")
    }
})
