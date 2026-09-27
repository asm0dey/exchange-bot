package fxbot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Whoever Telegram vouches for on one mini app call. The ONLY source of a user id on the web side. */
data class Viewer(val userId: Long, val username: String?, val startParam: String?)

@Serializable
private data class InitUser(val id: Long, val username: String? = null, @SerialName("first_name") val firstName: String? = null)

/**
 * Telegram's documented check: the secret is HMAC-SHA256 of the bot token keyed with
 * "WebAppData", and `hash` is HMAC-SHA256 of every other field, sorted, joined by '\n'.
 *
 * Plays the role `callback_query.from.id` plays for buttons. Everything that is not a
 * clean, fresh, correctly signed payload is null — the caller answers 401 and learns
 * nothing about why.
 */
class InitDataVerifier(
    botToken: String,
    private val clock: Clock = Clock.systemUTC(),
    private val maxAge: Duration = Duration.ofHours(24),
) {
    private val secret = hmac("WebAppData".toByteArray(), botToken.toByteArray())
    private val json = Json { ignoreUnknownKeys = true }

    fun verify(raw: String): Viewer? {
        val fields = parse(raw) ?: return null
        val hash = fields["hash"] ?: return null
        val dcs = fields.filterKeys { it != "hash" }.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        val expected = hmac(secret, dcs.toByteArray()).joinToString("") { "%02x".format(it) }
        if (!MessageDigest.isEqual(expected.toByteArray(), hash.lowercase().toByteArray())) return null
        val authDate = fields["auth_date"]?.toLongOrNull()?.let(Instant::ofEpochSecond) ?: return null
        val now = clock.instant()
        if (authDate.isBefore(now.minus(maxAge)) || authDate.isAfter(now.plus(CLOCK_SKEW))) return null
        val user = fields["user"]?.let { runCatching { json.decodeFromString<InitUser>(it) }.getOrNull() } ?: return null
        return Viewer(user.id, user.username, fields["start_param"])
    }

    /** Null on anything ambiguous: a duplicated key could smuggle a second, unsigned value. */
    private fun parse(raw: String): Map<String, String>? {
        if (raw.isBlank()) return null
        val out = linkedMapOf<String, String>()
        for (part in raw.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) return null
            val key = decode(part.substring(0, eq)) ?: return null
            val value = decode(part.substring(eq + 1)) ?: return null
            if (out.put(key, value) != null) return null
        }
        return out
    }

    private fun decode(s: String): String? = runCatching { URLDecoder.decode(s, Charsets.UTF_8) }.getOrNull()

    private companion object {
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }
}
