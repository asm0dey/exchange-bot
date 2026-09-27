package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf

/**
 * `setDefaultMenuButton` is the one place this codebase calls the Bot API directly instead
 * of through the library's typed `Action` DSL (see its doc comment in `Main.kt`) — exactly
 * because that typed wrapper cannot omit `chat_id`, and omitting it is what makes Telegram
 * treat this as the DEFAULT button for every private chat rather than one chat's override.
 * The payload is the part that can go wrong silently, so this asserts its actual shape
 * against a `MockEngine`, the same technique `BotTokenValidationTest`/`TestBot.kt` use.
 */
class MenuButtonTest : StringSpec({
    "posts a web_app default menu button with no chat_id" {
        var captured: HttpRequestData? = null
        val client = HttpClient(
            MockEngine { request ->
                captured = request
                respond(
                    """{"ok":true,"result":true}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        val ok = setDefaultMenuButton(client, "000:fake-token-for-tests", "https://x.example/app")

        ok shouldBe true
        val req = captured.shouldNotBeNull()
        req.url.encodedPath.endsWith("/setChatMenuButton") shouldBe true
        val bytes = (req.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val body = bytes.decodeToString()
        body shouldNotContain "chat_id"
        body shouldContain """"type":"web_app""""
        body shouldContain """"web_app":{"url":"https://x.example/app"}"""
    }

    "a Telegram-side rejection is reported as false, not thrown" {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"ok":false,"error_code":400,"description":"Bad Request"}""",
                    HttpStatusCode.BadRequest,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

        setDefaultMenuButton(client, "000:fake-token-for-tests", "https://x.example/app") shouldBe false
    }
})
