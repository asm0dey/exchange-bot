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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.time.Instant

/**
 * Exercises the real `tolerance` handler (and therefore the real `isAdmin`/`adminOnly`)
 * directly, the same technique `ForgetCommandTest` uses for its data-safety-sensitive
 * paths: a `TelegramBot` whose HTTP client is a `MockEngine`, so a Telegram-shaped
 * `getChatMember` failure — or a genuine transport exception — can be simulated without
 * fabricating the full `ChatMember` sealed-class JSON that the ALLOWED path would need.
 * The service layer (`AdminService`) already has its own direct unit tests
 * (`AdminServiceTest`); this file's job is the one path those tests cannot reach —
 * whether a caller who fails the admin check is actually refused, with the database
 * left untouched, rather than merely told "no" while the write goes through anyway.
 */
private data class AdminSent(val path: String, val body: String)

private fun adminRecordingSink() = mutableListOf<AdminSent>()

/**
 * `getChatMember` answers per [chatMemberResponse]: `RESPOND_FAILURE` returns a real
 * Telegram-shaped `ok:false` body (a completed HTTP call Telegram itself refused, e.g.
 * "user not found"); `THROW` never completes the HTTP call at all (a transport-level
 * exception from the engine) — the two distinct failure shapes the review asked to be
 * covered separately. Every other call (`sendMessage`) always succeeds, so the test can
 * read back exactly what was said to the chat.
 */
private enum class ChatMemberResponse { RESPOND_FAILURE, THROW }

private fun botWith(sink: MutableList<AdminSent>, chatMemberResponse: ChatMemberResponse): TelegramBot {
    val client = HttpClient(MockEngine { request ->
        val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        val path = request.url.encodedPath.substringAfterLast('/')
        sink += AdminSent(path, bytes.decodeToString())
        when {
            path == "getChatMember" && chatMemberResponse == ChatMemberResponse.THROW ->
                throw RuntimeException("simulated transport failure")
            path == "getChatMember" -> respond(
                content = """{"ok":false,"error_code":400,"description":"Bad Request: user not found"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
            else -> respond(
                content = """{"ok":true,"result":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    })
    return TelegramBot(token = "000:fake-token-for-admin-test", httpClient = client)
}

private fun updateFor(chatId: Long, chatType: ChatType, text: String, userId: Long = 1L): MessageUpdate {
    val chat = Chat(id = chatId, type = chatType)
    val user = User(id = userId, isBot = false, firstName = "Bob")
    val message = Message(messageId = 1L, date = Instant.fromEpochSeconds(0), chat = chat, from = user, text = text)
    return MessageUpdate(updateId = 1, origin = Update(updateId = 1), message = message)
}

private class AdminCommandFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val settings = ChatSettingsRepository(ds, crypto)

    init {
        Registry.settings = settings
        // Never expected to be called in these tests: the admin gate must refuse the
        // command before `AdminService` is ever reached, and any request this
        // MockEngine sees is a bug — surface it loudly instead of degrading to null.
        Registry.admin = AdminService(
            settings,
            RateClient(HttpClient(MockEngine { throw AssertionError("RateClient must not be called: the admin gate should have refused first") })),
        )
    }
}

class AdminCommandTest : StringSpec({
    "a Telegram-side getChatMember failure refuses /tolerance and leaves the setting unchanged" {
        val f = AdminCommandFixture("admin-chatmember-failure")
        f.settings.save(ChatSettings(-100L, CurrencyPair("EUR", "RUB"), 5, 7))
        val sent = adminRecordingSink()

        tolerance(updateFor(-100L, ChatType.Group, "/tolerance 50"), botWith(sent, ChatMemberResponse.RESPOND_FAILURE))

        f.settings.get(-100L).tolerancePct shouldBe 5 // unchanged: the write never ran
        sent.filter { it.path == "sendMessage" } shouldHaveSize 1
        sent.first { it.path == "sendMessage" }.body shouldContain "admins"
    }

    "a transport exception from getChatMember refuses /tolerance and leaves the setting unchanged" {
        val f = AdminCommandFixture("admin-chatmember-throws")
        f.settings.save(ChatSettings(-100L, CurrencyPair("EUR", "RUB"), 5, 7))
        val sent = adminRecordingSink()

        tolerance(updateFor(-100L, ChatType.Group, "/tolerance 50"), botWith(sent, ChatMemberResponse.THROW))

        f.settings.get(-100L).tolerancePct shouldBe 5 // unchanged: the write never ran
        sent.filter { it.path == "sendMessage" } shouldHaveSize 1
        sent.first { it.path == "sendMessage" }.body shouldContain "admins"
    }
})
