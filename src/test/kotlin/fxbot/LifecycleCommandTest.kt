package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.common.Update
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.msg.Message
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.time.Instant

/**
 * Covers the `replyToClose` fix (`LifecycleCommands.kt`): HTML parse mode must apply
 * only to [ActionResult.Ok] text, never to `Denied`/`Gone`, because those branches can
 * carry raw user input (the `/cancel`/`/done` short id) that was never meant to be
 * parsed as markup. Same technique as `ForgetCommandTest`/`AdminCommandTest` — the real
 * `cancel`/`done` handlers, real `Registry` wiring, a `MockEngine`-backed `TelegramBot`
 * that records outgoing call bodies so the test can inspect exactly what was sent.
 */
private data class LcSent(val path: String, val body: String)

private fun recordingBot(sink: MutableList<LcSent>): TelegramBot {
    val client = HttpClient(MockEngine { request ->
        val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val path = request.url.encodedPath.substringAfterLast('/')
        sink += LcSent(path, bytes.decodeToString())
        respond(
            content = """{"ok":true,"result":true}""",
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })
    return TelegramBot(token = "000:fake-token-for-lifecycle-test", httpClient = client)
}

private fun updateFor(chatId: Long, chatType: ChatType, text: String, userId: Long = 1L): MessageUpdate {
    val chat = Chat(id = chatId, type = chatType)
    val user = User(id = userId, isBot = false, firstName = "Bob")
    val message = Message(messageId = 1L, date = Instant.fromEpochSeconds(0), chat = chat, from = user, text = text)
    return MessageUpdate(updateId = 1, origin = Update(updateId = 1), message = message)
}

private class LifecycleCommandFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val messages = MessageLogRepository(ds, crypto)

    init {
        Registry.requests = requests
        Registry.messages = messages
        Registry.lifecycle = LifecycleService(requests)
        Registry.buttons = ButtonService(messages)
    }
}

class LifecycleCommandTest : StringSpec({
    "/cancel with an HTML-bearing short id still gets a reply, sent as plain text" {
        val f = LifecycleCommandFixture("cancel-html-shortid")
        val sent = mutableListOf<LcSent>()

        cancel(updateFor(-100L, ChatType.Group, "/cancel <a>evil</a>"), recordingBot(sent))

        // Before the fix, `send()` on an HTML-parse-mode message whose text carries an
        // unescaped angle-bracket tag either gets silently swallowed by Telegram (no
        // reply at all) or renders the tag as a live link — the MockEngine above always
        // answers `ok:true`, so a missing reply here would mean the handler never tried.
        sent shouldHaveSize 1
        sent.single().path shouldBe "sendMessage"
        val body = sent.single().body
        body shouldContain "can't find a waiting request"
        body shouldNotContain "<a>evil</a>" // never sent unescaped
        body shouldContain "&lt;a&gt;evil&lt;/a&gt;" // escaped, per LifecycleService
        body shouldNotContain "\"parse_mode\"" // Gone branch: plain text, no parse mode at all
    }

    "/done with an HTML-bearing short id still gets a reply, sent as plain text" {
        val f = LifecycleCommandFixture("done-html-shortid")
        val sent = mutableListOf<LcSent>()

        done(updateFor(-100L, ChatType.Group, "/done <a>evil</a>"), recordingBot(sent))

        sent shouldHaveSize 1
        sent.single().path shouldBe "sendMessage"
        val body = sent.single().body
        body shouldNotContain "<a>evil</a>"
        body shouldContain "&lt;a&gt;evil&lt;/a&gt;"
        body shouldNotContain "\"parse_mode\""
    }

    "a successful /cancel still uses HTML parse mode" {
        val f = LifecycleCommandFixture("cancel-ok-html")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", java.math.BigDecimal("1000"), CurrencyPair("EUR", "RUB"), 7)
        val sent = mutableListOf<LcSent>()

        cancel(updateFor(-100L, ChatType.Group, "/cancel ${a.shortId}"), recordingBot(sent))

        val confirmation = sent.first { it.path == "sendMessage" }
        confirmation.body shouldContain "\"parse_mode\":\"HTML\""
    }
})
