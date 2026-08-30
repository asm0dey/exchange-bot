package fxbot

import eu.vendeli.tgbot.TelegramBot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

/**
 * Exercises `validateBotToken` — the classification `Main.kt`'s startup check acts on —
 * against a `TelegramBot` whose HTTP client is a `MockEngine`, the same technique
 * `AdminCommandTest`/`ForgetCommandTest` use. `main()` itself (real network I/O ending in
 * `exitProcess`) is not covered here and cannot be meaningfully covered without either a
 * live Telegram token or a test that kills the JVM and proves nothing either way; what's
 * testable, and what this file tests, is the pure classification: does a Telegram-shaped
 * `ok:false` body come out as [TokenValidation.Rejected], a `getMe`-shaped success body come
 * out as [TokenValidation.Valid], and a genuine transport exception come out as
 * [TokenValidation.Unknown] rather than being misread as either of the other two.
 */
private fun botRespondingWith(body: String, status: HttpStatusCode): TelegramBot {
    val client = HttpClient(
        MockEngine {
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )
    return TelegramBot(token = "000:fake-token-for-getme-test", httpClient = client)
}

private fun botThrowingOnRequest(): TelegramBot {
    val client = HttpClient(MockEngine { throw RuntimeException("simulated transport failure") })
    return TelegramBot(token = "000:fake-token-for-getme-test", httpClient = client)
}

class BotTokenValidationTest : StringSpec({
    "a successful getMe classifies as Valid" {
        val bot = botRespondingWith(
            """{"ok":true,"result":{"id":1,"is_bot":true,"first_name":"TestBot"}}""",
            HttpStatusCode.OK,
        )

        validateBotToken(bot) shouldBe TokenValidation.Valid
    }

    "a Telegram-side rejection (401, bad token) classifies as Rejected, not Unknown" {
        val bot = botRespondingWith(
            """{"ok":false,"error_code":401,"description":"Unauthorized"}""",
            HttpStatusCode.Unauthorized,
        )

        validateBotToken(bot) shouldBe TokenValidation.Rejected
    }

    "a transport exception classifies as Unknown, not Rejected" {
        val bot = botThrowingOnRequest()

        val result = validateBotToken(bot).shouldBeInstanceOf<TokenValidation.Unknown>()

        result.causeClass shouldBe "RuntimeException"
    }
})
