package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The Playwright fixtures ARE the API contract the SPA is tested against. Decoding alone
 * isn't enough: a fixture that simply omits a default-valued field (CardDto.counterparties,
 * CardDto.token, ...) would still decode fine, but MiniAppServer.kt installs
 * `encodeDefaults = true`, so the real server never omits that field. Each fixture is
 * decoded into its DTO, then re-encoded with that SAME Json config, and the result must
 * equal the fixture file exactly (compared as JsonElement, so key order doesn't matter). If
 * a fixture drifts from what the server actually sends, this fails here instead of the app
 * breaking only in production.
 */
class MiniAppFixturesTest : StringSpec({
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun read(name: String) = File("web/e2e/fixtures/$name").readText()

    "chat.json is exactly what the server sends for a ChatView" {
        val text = read("chat.json")
        val decoded = json.decodeFromString<ChatView>(text)
        json.parseToJsonElement(json.encodeToString(decoded)) shouldBe json.parseToJsonElement(text)
    }
    "me.json is exactly what the server sends for a MeView" {
        val text = read("me.json")
        val decoded = json.decodeFromString<MeView>(text)
        json.parseToJsonElement(json.encodeToString(decoded)) shouldBe json.parseToJsonElement(text)
    }
    "browse.json is exactly what the server sends for a BrowseView" {
        val text = read("browse.json")
        val decoded = json.decodeFromString<BrowseView>(text)
        json.parseToJsonElement(json.encodeToString(decoded)) shouldBe json.parseToJsonElement(text)
    }
})
