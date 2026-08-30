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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.math.BigDecimal
import kotlin.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")

/**
 * Exercises the real `forget` handler function directly — not through the framework's
 * command-parsing/dispatch pipeline (that's CommandParsingTest's job), and not by
 * reimplementing its branching in the test. Real `Registry` wiring, real in-memory DB,
 * and a `TelegramBot` whose HTTP client is a MockEngine that records every outgoing
 * call instead of reaching the network — the same technique CommandParsingTest uses,
 * extended to capture request bodies so a test can tell WHICH message text was sent,
 * not just that a send was attempted.
 */
private data class Sent(val path: String, val body: String)

/** Every call answered as a success, whatever it is — good enough where the test only cares who was called, not per-call outcomes. */
private fun recordingBot(sink: MutableList<Sent>): TelegramBot = botWith(sink, deleteSucceeds = true)

/**
 * Records every outgoing call (so a test can assert which message text was sent, not just
 * that a send was attempted) and answers `deleteMessage` truthfully per [deleteSucceeds] — a
 * real Telegram-shaped failure (HTTP 400, `ok:false`), not a thrown exception, which is how
 * "message too old to delete" actually looks on the wire. `editMessageText` always succeeds
 * with a minimal but schema-valid `Message` body, since `sendReturning` decodes the response
 * into the action's real return type.
 */
private fun botWith(sink: MutableList<Sent>, deleteSucceeds: Boolean): TelegramBot {
    val client = HttpClient(MockEngine { request ->
        val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val path = request.url.encodedPath.substringAfterLast('/')
        sink += Sent(path, bytes.decodeToString())
        when {
            path == "deleteMessage" && !deleteSucceeds -> respond(
                content = """{"ok":false,"error_code":400,"description":"Bad Request: message to delete not found"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
            path == "editMessageText" -> respond(
                content = """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-100,"type":"group"}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
            else -> respond(
                content = """{"ok":true,"result":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    })
    return TelegramBot(token = "000:fake-token-for-forget-test", httpClient = client)
}

private fun updateFor(chatId: Long, chatType: ChatType, text: String, userId: Long = 1L): MessageUpdate {
    val chat = Chat(id = chatId, type = chatType)
    val user = User(id = userId, isBot = false, firstName = "Bob")
    val message = Message(messageId = 1L, date = Instant.fromEpochSeconds(0), chat = chat, from = user, text = text)
    return MessageUpdate(updateId = 1, origin = Update(updateId = 1), message = message)
}

private class CommandFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val log = MessageLogRepository(ds, crypto)

    init {
        Registry.requests = requests
        Registry.messages = log
        Registry.forget = ForgetService(requests, log)
    }
}

class ForgetCommandTest : StringSpec({
    "/forget all from a group is refused and never reaches the database" {
        val f = CommandFixture("cmd-group-all")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val sent = mutableListOf<Sent>()
        forget(updateFor(-100L, ChatType.Group, "/forget all"), recordingBot(sent))

        f.requests.resting(-100L) shouldHaveSize 1 // untouched: the guard returned before Registry.forget.plan ran
        sent shouldHaveSize 1
        sent.single().path shouldBe "sendMessage"
        sent.single().body shouldContain "private chat"
    }

    "/forget all from a private chat reaches every group" {
        val f = CommandFixture("cmd-private-all")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val sent = mutableListOf<Sent>()
        forget(updateFor(555L, ChatType.Private, "/forget all"), recordingBot(sent))

        f.requests.resting(-100L) shouldHaveSize 0
        f.requests.resting(-200L) shouldHaveSize 0
        sent.last().path shouldBe "sendMessage"
        sent.last().body shouldContain "Erased 2"
    }

    "plain /forget from a group is scoped to that chat only" {
        val f = CommandFixture("cmd-group-scoped")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val sent = mutableListOf<Sent>()
        forget(updateFor(-100L, ChatType.Group, "/forget"), recordingBot(sent))

        f.requests.resting(-100L) shouldHaveSize 0
        f.requests.resting(-200L) shouldHaveSize 1 // the other chat was never touched
        sent.last().body shouldContain "Erased 1"
    }

    "plain /forget from a private chat is refused by the ordinary group-only guard" {
        val f = CommandFixture("cmd-private-plain")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val sent = mutableListOf<Sent>()
        forget(updateFor(555L, ChatType.Private, "/forget"), recordingBot(sent))

        f.requests.resting(-100L) shouldHaveSize 1 // untouched: inGroupOrExplain rejected before any plan ran
        sent shouldHaveSize 1
        sent.single().body shouldContain "group chat"
    }

    "the confirmation counts only what Telegram actually tidied, not every attempt" {
        val f = CommandFixture("cmd-partial-fail")
        // Names only person 1 -> a delete attempt, but Telegram refuses it (too old, say).
        f.log.record(-100L, 10L, listOf("tokA"), listOf(1L))
        // Names someone else too -> a redact attempt, which succeeds.
        f.log.record(-100L, 11L, listOf("tokA", "tokB"), listOf(1L, 2L))
        val sent = mutableListOf<Sent>()
        forget(updateFor(-100L, ChatType.Group, "/forget"), botWith(sent, deleteSucceeds = false))

        val deleteCalls = sent.filter { it.path == "deleteMessage" }
        val editCalls = sent.filter { it.path == "editMessageText" }
        deleteCalls shouldHaveSize 1 // the attempt was made even though it failed
        editCalls shouldHaveSize 1
        val confirmation = sent.last()
        confirmation.path shouldBe "sendMessage"
        confirmation.body shouldContain "tidied 1 message" // the failed delete is not counted
    }
})
