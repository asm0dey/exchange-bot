package fxbot

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal

@Serializable
private data class FeedResponse(val result: String, val rates: Map<String, JsonElement> = emptyMap())

/**
 * open.er-api.com: free, no key, updates daily, and — unlike the ECB feeds —
 * it still carries RUB.
 *
 * Rates are decoded as [JsonElement], never as `Double`: kotlinx.serialization's JSON
 * parser keeps every number's original lexical text, and [jsonPrimitive]'s `content` is
 * that exact text. Reading a rate as `Double` first and then formatting it back to a
 * string for `BigDecimal(String)` is exactly the precision trap this sidesteps — a
 * feed value like `99.123456789` would round the moment it touched a `Double`, long
 * before `BigDecimal` ever saw it.
 */
class RateClient(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A feed failure is a null, not an exception — degradation is the design. But
     * cancellation is not a failure: catching it here would let a cancelled refresh
     * keep running, so it is rethrown before anything else is caught.
     *
     * ktor implements a request timeout by cancelling the call's job, so a slow feed
     * arrives here as a CancellationException wrapping HttpRequestTimeoutException —
     * that is an unreachable feed, not our own caller giving up, so it degrades to
     * null like any other feed failure. A CancellationException with any other cause
     * (or none) is a real cancellation and still propagates.
     */
    suspend fun fetch(base: String): Map<String, BigDecimal>? =
        try {
            val body = http.get("https://open.er-api.com/v6/latest/$base").bodyAsText()
            val parsed = json.decodeFromString<FeedResponse>(body)
            if (parsed.result != "success") null
            else parsed.rates.mapValues { (_, v) -> BigDecimal(v.jsonPrimitive.content) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e.cause is io.ktor.client.plugins.HttpRequestTimeoutException) null else throw e
        } catch (e: Exception) {
            null
        }
}
