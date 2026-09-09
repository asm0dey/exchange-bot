package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.common.CallbackQuery
import eu.vendeli.tgbot.types.common.Update
import eu.vendeli.tgbot.types.component.CallbackQueryUpdate
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.msg.EntityType
import eu.vendeli.tgbot.types.msg.Message
import eu.vendeli.tgbot.types.msg.MessageEntity
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Instant as KtInstant

private val T0: Instant = Instant.parse("2026-09-06T12:00:00Z")
private val EURRUB = CurrencyPair("EUR", "RUB")

/** The one group every fixture configures, so fan-out has somewhere real to reach. */
private const val GROUP = -100L

/** The private chat id used for a direct message — for a DM, Telegram's chat id IS the user id. */
private const val DM = 555L

private const val FEED = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}"""

/**
 * The whole Telegram-facing surface wired against a real `Registry`, a real in-memory
 * database and a real `InterestService` — mirroring `InterestServiceTest`'s fixture, but
 * assigning every field into `Registry` because the handlers under test are top-level
 * functions that reach their dependencies through it.
 *
 * A [NameLookup] and `Registry.giveUpService` are wired deliberately: `GiveUpService`
 * refuses with "no route" when neither side has an `@username`, so a fixture without a
 * lookup would make every give-up test assert against a service that never ran.
 *
 * The batcher's sink collects instead of sending. `telegramSink` is exercised on its own,
 * directly, further down — the point of that test is what it RECORDS, which is invisible
 * through the batcher.
 */
private class PrivateFixture(name: String) {
    val ds = memDataSource("priv_$name").also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, clock)
    val chats = ChatSettingsRepository(ds, crypto, clock)
    val people = PersonSettingsRepository(ds, crypto, clock)
    val giveUps = NameGiveUpRepository(ds, crypto, clock)
    val refusals = DoneRefusalRepository(ds, clock)
    val pending = PendingAnnouncementRepository(ds, crypto, clock)
    val messages = MessageLogRepository(ds, crypto, clock)
    val rateRepo = RateRepository(ds)
    val client = RateClient(HttpClient(MockEngine {
        respond(FEED, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }))
    val rates = RateService(client, rateRepo, clock)

    /** What the batcher was handed, instead of what Telegram would have been sent. */
    val delivered = mutableListOf<Pair<List<Announcement>, List<Ping>>>()

    val handles = mutableMapOf(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann"))

    init {
        rateRepo.put("EUR", "RUB", BigDecimal("99.98"), T0)
        chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))

        Registry.requests = requests
        Registry.settings = chats
        Registry.rates = rates
        Registry.people = people
        Registry.giveUps = giveUps
        Registry.pending = pending
        Registry.messages = messages
        Registry.service = RequestService(requests, chats, rates)
        // Above `Registry.lifecycle`, which now takes the lookup: a done names the
        // counterparty it asks, and somebody with no handle only has a name through this.
        Registry.names = NameLookup { handles[it] }
        Registry.lifecycle = LifecycleService(requests, chats, rates, people, refusals, Registry.names)
        Registry.buttons = ButtonService(messages, requests)
        Registry.forget = ForgetService(requests, messages, people, giveUps, pending)
        Registry.admin = AdminService(chats, client)
        Registry.interests = InterestService(
            requests, chats, people, rates, client, giveUps, pending,
            MembershipProbe { _, _ -> true },
        )
        Registry.giveUpService = GiveUpService(requests, giveUps, Registry.names)
        Registry.batcher = AnnouncementBatcher(
            requests, chats, pending, Registry.interests, rates,
            { announcements, pings -> delivered += announcements to pings },
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock,
            names = Registry.names,
        )
    }
}

private fun updateFor(chatId: Long, chatType: ChatType, text: String, userId: Long = 1L): MessageUpdate {
    val chat = Chat(id = chatId, type = chatType)
    val user = User(id = userId, isBot = false, firstName = "Bob", username = "bob")
    val message = Message(messageId = 1L, date = KtInstant.fromEpochSeconds(0), chat = chat, from = user, text = text)
    return MessageUpdate(updateId = 1, origin = Update(updateId = 1), message = message)
}

/**
 * `/done a1 @name`, with the `@name` carried as a real Telegram `mention` entity —
 * `resolvePeer` reads entities and never the raw text, so a peer is only ever named this
 * way (or by a reply).
 */
private fun mentionUpdate(chatId: Long, chatType: ChatType, text: String, userId: Long = 1L): MessageUpdate {
    val at = text.indexOf('@')
    val chat = Chat(id = chatId, type = chatType)
    val user = User(id = userId, isBot = false, firstName = "Bob", username = "bob")
    val message = Message(
        messageId = 1L,
        date = KtInstant.fromEpochSeconds(0),
        chat = chat,
        from = user,
        text = text,
        entities = listOf(MessageEntity(EntityType.Mention, at, text.length - at)),
    )
    return MessageUpdate(updateId = 1, origin = Update(updateId = 1), message = message)
}

/**
 * A press. Only `from`, `message.chat` and `id` are filled in: those are the three things
 * the handlers touch — `getUser()` reads `callbackQuery.from`, `getChat()` reads
 * `callbackQuery.message?.chat`, and the answer needs the query id.
 */
private fun callbackFrom(userId: Long, chatId: Long = DM): CallbackQueryUpdate {
    val type = if (chatId < 0) ChatType.Group else ChatType.Private
    val chat = Chat(id = chatId, type = type)
    val user = User(id = userId, isBot = false, firstName = "P$userId")
    val message = Message(messageId = 1L, date = KtInstant.fromEpochSeconds(0), chat = chat, from = user)
    val query = CallbackQuery(id = "q1", from = user, message = message, chatInstance = "ci")
    return CallbackQueryUpdate(updateId = 1, origin = Update(updateId = 1), callbackQuery = query)
}

/**
 * A bot whose every call Telegram REFUSES — an `ok:false` body with an HTTP error, which is
 * how a real refusal looks on the wire (`throwExOnActionsFailure` is false, so nothing
 * throws). `TestBot.recordingBot` answers everything as a success on purpose, so a test
 * about failure needs its own engine; its doc says exactly that.
 */
private fun refusingBot(sink: MutableList<Call>): TelegramBot {
    val client = HttpClient(MockEngine { request ->
        val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        sink += Call(request.url.encodedPath.substringAfterLast('/'), bytes.decodeToString())
        respond(
            """{"ok":false,"error_code":403,"description":"Forbidden: bot was kicked from the group chat"}""",
            HttpStatusCode.Forbidden,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })
    return TelegramBot(token = "000:fake-token-for-tests", httpClient = client)
}

/** A bot that answers every call with [body], for pinning an adapter's decoding. */
private fun botReplying(body: String): TelegramBot = TelegramBot(
    token = "000:fake-token-for-tests",
    httpClient = HttpClient(MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }),
)

/** A bot whose transport blows up before Telegram is ever reached. */
private fun throwingBot(): TelegramBot = TelegramBot(
    token = "000:fake-token-for-tests",
    httpClient = HttpClient(MockEngine { throw java.io.IOException("connection refused") }),
)

/**
 * Blows up on the FIRST call and records every one after it — one unreachable recipient
 * partway through a list, which is the shape that silently costs everybody after them
 * their message if a loop has no per-recipient catch.
 */
private fun throwingOnceBot(sink: MutableList<Call>): TelegramBot {
    var thrown = false
    return TelegramBot(
        token = "000:fake-token-for-tests",
        httpClient = HttpClient(MockEngine { request ->
            if (!thrown) {
                thrown = true
                throw java.io.IOException("connection refused")
            }
            val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
            sink += Call(request.url.encodedPath.substringAfterLast('/'), bytes.decodeToString())
            respond(
                """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-100,"type":"group"}}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }),
    )
}

/** A `getChatMember` success carrying [status], with only the fields that variant requires. */
private fun memberResult(status: String): String = when (status) {
    "creator" -> """{"ok":true,"result":{"status":"creator","user":{"id":1,"is_bot":false,"first_name":"B"},"is_anonymous":false}}"""
    "administrator" -> """{"ok":true,"result":{"status":"administrator","user":{"id":1,"is_bot":false,"first_name":"B"},""" +
        """"can_be_edited":false,"is_anonymous":false,"can_manage_chat":true,"can_delete_messages":false,""" +
        """"can_restrict_members":false,"can_promote_members":false,"can_change_info":false,"can_invite_users":true,""" +
        """"can_manage_video_chats":false,"can_post_stories":false,"can_edit_stories":false,"can_delete_stories":false}}"""
    "member" -> """{"ok":true,"result":{"status":"member","user":{"id":1,"is_bot":false,"first_name":"B"}}}"""
    "left" -> """{"ok":true,"result":{"status":"left","user":{"id":1,"is_bot":false,"first_name":"B"}}}"""
    "kicked" -> """{"ok":true,"result":{"status":"kicked","user":{"id":1,"is_bot":false,"first_name":"B"},"until_date":0}}"""
    // `restricted` carries `is_member`, and it is FALSE for somebody who is restricted and
    // not in the chat at all — the one status whose name does not settle the question.
    "restricted_member", "restricted_left" ->
        """{"ok":true,"result":{"status":"restricted","user":{"id":1,"is_bot":false,"first_name":"B"},""" +
            """"is_member":${status == "restricted_member"},"can_send_messages":true,"can_send_audios":true,""" +
            """"can_send_documents":true,"can_send_photos":true,"can_send_videos":true,"can_send_video_notes":true,""" +
            """"can_send_voice_notes":true,"can_send_polls":true,"can_send_other_messages":true,""" +
            """"can_add_web_page_previews":true,"can_change_info":false,"can_invite_users":false,""" +
            """"can_pin_messages":false,"can_manage_topics":false,"until_date":0}}"""
    else -> error("no fixture for status $status")
}

/** A `getChat` success. The four fields after `type` are the ones `ChatFullInfo` requires. */
private fun chatResult(username: String?, firstName: String?, title: String?): String {
    val fields = listOfNotNull(
        username?.let { """"username":"$it"""" },
        firstName?.let { """"first_name":"$it"""" },
        title?.let { """"title":"$it"""" },
    ).joinToString(",", prefix = if (username == null && firstName == null && title == null) "" else ",")
    return """{"ok":true,"result":{"id":7,"type":"private"$fields,"accent_color_id":0,"max_reaction_count":1,""" +
        """"accepted_gift_types":{"unlimited_gifts":false,"limited_gifts":false,"unique_gifts":false,""" +
        """"premium_subscription":false,"gifts_from_channels":false}}}"""
}

class PrivateCommandTest : StringSpec({
    "a private /sell without 'for' is refused with the example" {
        val f = PrivateFixture("privsellnofor")
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR"), recordingBot(sent))
        sent.single().body shouldContain "/sell 10 EUR for RUB"
        f.requests.resting(NO_CHAT_ID).shouldBeEmpty()
    }

    "a private /sell with 'for' states an interest and replies at once" {
        val f = PrivateFixture("privsell")
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        f.requests.resting(NO_CHAT_ID) shouldHaveSize 1
        // Fan-out reached the configured group, and the showing rests there immediately.
        f.requests.resting(GROUP) shouldHaveSize 1
        sent.last().path shouldBe "sendMessage"
        sent.last().body shouldContain "1 group"
    }

    "the private reply is recorded against every ref token its buttons name" {
        val f = PrivateFixture("privrecord")
        // Somebody to be found, so the reply carries give-up buttons naming THEIR token.
        val peer = f.requests.create(
            NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2",
        )
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        val mine = f.requests.resting(NO_CHAT_ID).single { it.userId == 1L }
        // recordingBot answers every send with message_id 1.
        val logged = f.messages.logged(DM, 1L).shouldNotBeNull()
        logged.refTokens shouldContainExactlyInAnyOrder listOf(mine.refToken, peer.refToken)
        logged.buttons.map { it.data } shouldContain Cb.giveUp(mine.refToken, peer.refToken)
        // Every token a button names is recorded, or ButtonService.refreshFor drops it.
        logged.buttons.forEach { b -> logged.refTokens.any { it in b.data } shouldBe true }
    }

    "a group /sell is unchanged: one showing, no fan-out, no rate limit" {
        val f = PrivateFixture("groupsell")
        val sent = mutableListOf<Call>()
        sell(updateFor(GROUP, ChatType.Group, "/sell 1000 EUR"), recordingBot(sent))
        f.requests.resting(GROUP) shouldHaveSize 1
        f.requests.resting(NO_CHAT_ID).shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
    }

    "a private /tolerance sets the person's own, a group /tolerance is still the admin command" {
        val f = PrivateFixture("privtolerance")
        val sent = mutableListOf<Call>()
        tolerance(updateFor(DM, ChatType.Private, "/tolerance 5"), recordingBot(sent))
        f.people.get(1L).tolerancePct shouldBe 5
        f.chats.get(GROUP).tolerancePct shouldBe 20 // untouched
        sent.single().path shouldBe "sendMessage"
    }

    "a private /status lists each interest once, with where it still rests" {
        val f = PrivateFixture("privstatus")
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        sent.clear()
        status(updateFor(DM, ChatType.Private, "/status"), recordingBot(sent))
        val body = sent.single().body
        body shouldContain "sell 10 EUR"
        body shouldContain "in 1 group"
        f.requests.resting(GROUP) shouldHaveSize 1
    }

    "a private /cancel withdraws the interest and every showing with it" {
        val f = PrivateFixture("privcancel")
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        f.requests.resting(GROUP) shouldHaveSize 1 // the showing exists before the cancel
        val shortId = f.requests.resting(NO_CHAT_ID).single().shortId
        cancel(updateFor(DM, ChatType.Private, "/cancel $shortId"), recordingBot(sent))
        f.requests.resting(NO_CHAT_ID).shouldBeEmpty()
        f.requests.resting(GROUP).shouldBeEmpty()
    }

    "a private /settings reports the person's own tolerance, not a chat's" {
        val f = PrivateFixture("privsettings")
        val sent = mutableListOf<Call>()
        f.people.save(PersonSettings(1L, 40))
        settings(updateFor(DM, ChatType.Private, "/settings"), recordingBot(sent))
        sent.single().body shouldContain "40"
    }

    "a private /pair still gets the group hint" {
        PrivateFixture("privpair")
        val sent = mutableListOf<Call>()
        pair(updateFor(DM, ChatType.Private, "/pair EUR RUB"), recordingBot(sent))
        sent.single().body shouldContain "group"
    }

    "a private /tif still gets the group hint" {
        PrivateFixture("privtif")
        val sent = mutableListOf<Call>()
        tif(updateFor(DM, ChatType.Private, "/tif 7"), recordingBot(sent))
        sent.single().body shouldContain "group"
    }

    "a give-up press discloses nothing until the other side presses too" {
        val f = PrivateFixture("givepress")
        val a = f.requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()
        giveUpCallback(a.refToken, b.refToken, callbackFrom(1L), recordingBot(sent))
        sent.joinToString { it.body } shouldNotContain "@ann"
        sent.joinToString { it.body } shouldNotContain "@bob"
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED
        // The peer is told about THEIR OWN interest, not the presser's.
        val ask = sent.single { it.path == "sendMessage" }
        ask.body shouldContain "buy 1,000 EUR"
        // And nothing is recorded against the peer's private chat: neither has pressed
        // anything that would justify storing a link between the two.
        f.messages.logged(2L, 1L) shouldBe null
    }

    "a forged give-up payload, pressed by someone who owns neither request, discloses nothing" {
        val f = PrivateFixture("giveforged")
        val a = f.requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        f.giveUps.record(b.refToken, a.refToken, 2L, Stance.OFFERED)
        val sent = mutableListOf<Call>()
        giveUpCallback(a.refToken, b.refToken, callbackFrom(99L), recordingBot(sent))
        sent.joinToString { it.body } shouldNotContain "@ann"
        sent.joinToString { it.body } shouldNotContain "@bob"
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe null
        sent.none { it.path == "sendMessage" } shouldBe true
    }

    "both sides pressing passes each the other's handle, and both messages are recorded" {
        val f = PrivateFixture("givedisclose")
        val a = f.requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()
        giveUpCallback(a.refToken, b.refToken, callbackFrom(1L), recordingBot(sent))
        sent.clear()
        giveUpCallback(b.refToken, a.refToken, callbackFrom(2L), recordingBot(sent))
        val bodies = sent.filter { it.path == "sendMessage" }
        bodies shouldHaveSize 2
        bodies.joinToString { it.body } shouldContain "@ann"
        bodies.joinToString { it.body } shouldContain "@bob"
        // Both people are named on each disclosure, so /forget from either side reaches it.
        f.messages.logged(1L, 1L).shouldNotBeNull().refTokens shouldContainExactlyInAnyOrder
            listOf(a.refToken, b.refToken)
        f.messages.messagesForUser(2L, null).map { it.chatId } shouldContain 1L
    }

    "a decline by the owner is recorded, and the pairing is suppressed for both" {
        val f = PrivateFixture("declinepress")
        val a = f.requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()
        declineCallback(a.refToken, b.refToken, callbackFrom(1L), recordingBot(sent))
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.DECLINED
        f.giveUps.declined(b.refToken, a.refToken) shouldBe true
        sent.single().path shouldBe "answerCallbackQuery"
        sent.single().body shouldNotContain "@ann"
    }

    "a decline pressed by someone who owns neither request writes nothing" {
        val f = PrivateFixture("declineforged")
        val a = f.requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()
        declineCallback(a.refToken, b.refToken, callbackFrom(99L), recordingBot(sent))
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe null
        sent.joinToString { it.body } shouldNotContain "@ann"
        sent.joinToString { it.body } shouldNotContain "@bob"
    }

    "restating a residual makes a new request rather than rewriting the original" {
        val f = PrivateFixture("restate")
        val mine = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        val theirs = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7)
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(theirs.refToken, RequestState.OPEN, RequestState.DONE)
        val sent = mutableListOf<Call>()
        restateCallback(mine.refToken, theirs.refToken, callbackFrom(1L, chatId = GROUP), recordingBot(sent))
        val fresh = f.requests.resting(GROUP).single()
        fresh.statedAmount shouldBe BigDecimal("400")
        // A NEW row, not the original edited. Its SHORT id may well be the original's:
        // `allocateShortId` only avoids the ids of requests still OPEN, so a closed one's
        // id is deliberately free again. The ref token is what identifies the row.
        fresh.refToken shouldNotBe mine.refToken
        fresh.state shouldBe RequestState.OPEN
        f.requests.byRefToken(mine.refToken)!!.statedAmount shouldBe BigDecimal("1000")
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        // A request typed in a group is restated in that group alone (ADR 0007).
        f.requests.resting(NO_CHAT_ID).shouldBeEmpty()
    }

    "restating a privately stated interest states it again privately, and it fans out" {
        val f = PrivateFixture("restatepriv")
        val mine = f.requests.create(
            NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        val theirs = f.requests.create(
            NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7, "i2",
        )
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(theirs.refToken, RequestState.OPEN, RequestState.DONE)
        val sent = mutableListOf<Call>()
        restateCallback(mine.refToken, theirs.refToken, callbackFrom(1L), recordingBot(sent))
        f.requests.resting(NO_CHAT_ID).single().statedAmount shouldBe BigDecimal("400")
        f.requests.resting(GROUP) shouldHaveSize 1
    }

    "a restate pressed by someone who owns neither request creates nothing" {
        val f = PrivateFixture("restateforged")
        val mine = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        val theirs = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7)
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(theirs.refToken, RequestState.OPEN, RequestState.DONE)
        val sent = mutableListOf<Call>()
        restateCallback(mine.refToken, theirs.refToken, callbackFrom(99L, chatId = GROUP), recordingBot(sent))
        f.requests.resting(GROUP).shouldBeEmpty()
        f.requests.resting(NO_CHAT_ID).shouldBeEmpty()
        sent.none { it.path == "sendMessage" } shouldBe true
    }

    "the sink records each announcement against every token its buttons name, and records no ping" {
        val f = PrivateFixture("sink")
        val a = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val buttons = listOf(
            Button("✅ Done with ann", Cb.done(a.refToken, b.refToken)),
            Button("✖️ Cancel ${a.shortId}", Cb.cancel(a.refToken)),
        )
        val sent = mutableListOf<Call>()
        telegramSink(recordingBot(sent)).deliver(
            listOf(
                Announcement(
                    GROUP, "I'm showing this on bob's behalf:", buttons,
                    listOf(a.refToken, b.refToken), listOf(1L, 2L),
                ),
            ),
            listOf(Ping(2L, "Someone matches an interest you have with me:", nameGiveUpButtons(b, a))),
        )
        val logged = f.messages.logged(GROUP, 1L).shouldNotBeNull()
        // The exact sent text, with no status line, and the same buttons in the same order.
        logged.text shouldBe "I'm showing this on bob's behalf:"
        logged.buttons shouldBe buttons
        // Every token named, not just the subject — refreshFor rebuilds only from these.
        logged.refTokens shouldContainExactlyInAnyOrder listOf(a.refToken, b.refToken)
        // Paired 1:1 with each token's real owner, so /forget keys off the right person.
        f.messages.messagesForUser(2L, GROUP) shouldHaveSize 1
        // The ping is deliberately NOT recorded: its give-up button names the
        // counterparty's token, and storing that against this person's private chat would
        // link two people with no chat before either pressed anything.
        f.messages.logged(2L, 1L) shouldBe null
        sent.filter { it.path == "sendMessage" } shouldHaveSize 2
    }

    "re-recording the same posted message is idempotent" {
        val f = PrivateFixture("sinkidem")
        val a = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val announcement = Announcement(GROUP, "text", emptyList(), listOf(a.refToken), listOf(1L))
        val sent = mutableListOf<Call>()
        val sink = telegramSink(recordingBot(sent))
        sink.deliver(listOf(announcement), emptyList())
        sink.deliver(listOf(announcement), emptyList())
        f.messages.logged(GROUP, 1L).shouldNotBeNull().refTokens shouldHaveSize 1
    }

    "the command menus keep every command each scope needs" {
        // A private-scope list is added here; the group list must not lose anything an
        // earlier task registered (fanout, in particular).
        GROUP_COMMANDS.map { it.first } shouldContainExactlyInAnyOrder listOf(
            "sell", "buy", "status", "cancel", "done", "reopen", "settings",
            "pair", "tolerance", "tif", "fanout", "forget", "help",
        )
        PRIVATE_COMMANDS.map { it.first } shouldContainExactlyInAnyOrder listOf(
            "sell", "buy", "tolerance", "status", "cancel", "done", "reopen", "settings", "forget", "help",
        )
        // Admin-only commands have no private meaning, so they are not suggested there.
        PRIVATE_COMMANDS.map { it.first } shouldNotContain "pair"
        PRIVATE_COMMANDS.map { it.first } shouldNotContain "tif"
        PRIVATE_COMMANDS.map { it.first } shouldNotContain "fanout"
    }
    // ---- Fix 1: a refused send must reach the batcher as a throw ----

    "a refused announcement send throws, so the batcher keeps the pending row" {
        val f = PrivateFixture("sinkrefused")
        val a = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val sent = mutableListOf<Call>()
        val sink = telegramSink(refusingBot(sent))
        // Throwing is the ONLY way to tell AnnouncementBatcher the chat was not told;
        // returning normally makes it delete the pending row and lose the announcement.
        shouldThrow<AnnouncementNotSent> {
            sink.deliver(
                listOf(Announcement(GROUP, "text", emptyList(), listOf(a.refToken), listOf(1L))),
                emptyList(),
            )
        }
        sent.filter { it.path == "sendMessage" } shouldHaveSize 1 // the attempt was made
        f.messages.logged(GROUP, 1L) shouldBe null // and nothing was recorded as sent
    }

    "a refused announcement does not stop the ones already sent from being recorded" {
        // Documents the at-least-once trade: the batch is retried whole, so a chat may be
        // told twice rather than never.
        val f = PrivateFixture("sinkpartial")
        val a = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val sent = mutableListOf<Call>()
        var calls = 0
        val bot = TelegramBot(
            token = "000:fake-token-for-tests",
            httpClient = HttpClient(MockEngine { request ->
                val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
                sent += Call(request.url.encodedPath.substringAfterLast('/'), bytes.decodeToString())
                calls++
                if (calls == 1) {
                    respond(
                        """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-100,"type":"group"}}}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        """{"ok":false,"error_code":403,"description":"Forbidden"}""",
                        HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }),
        )
        shouldThrow<AnnouncementNotSent> {
            telegramSink(bot).deliver(
                listOf(
                    Announcement(GROUP, "first", emptyList(), listOf(a.refToken), listOf(1L)),
                    Announcement(-200L, "second", emptyList(), listOf(a.refToken), listOf(1L)),
                ),
                emptyList(),
            )
        }
        f.messages.logged(GROUP, 1L).shouldNotBeNull().text shouldBe "first"
        f.messages.logged(-200L, 1L) shouldBe null
    }

    // ---- Fix 3: the calls that publish the menus, not only their contents ----

    "all three command menus are actually published, each with its own scope" {
        val sent = mutableListOf<Call>()
        registerCommandMenus(recordingBot(sent))
        val calls = sent.filter { it.path == "setMyCommands" }
        calls shouldHaveSize 3
        val scopes = calls.map { call ->
            Regex(""""type":"([a-z_]+)"""").find(call.body)!!.groupValues[1]
        }
        scopes shouldContainExactlyInAnyOrder listOf("default", "all_group_chats", "all_private_chats")
        // Every entry in each list really reaches the wire — `menu()` maps all of them.
        val privateBody = calls.single { it.body.contains(""""type":"all_private_chats"""") }.body
        PRIVATE_COMMANDS.forEach { (name, description) ->
            privateBody shouldContain """"command":"$name""""
            privateBody shouldContain description
        }
        val groupBody = calls.single { it.body.contains(""""type":"all_group_chats"""") }.body
        GROUP_COMMANDS.forEach { (name, _) -> groupBody shouldContain """"command":"$name"""" }
    }

    // ---- Fix 4: the two Telegram adapters ----

    "the membership probe reads every status Telegram can answer with" {
        listOf("creator", "administrator", "member", "restricted_member").forEach { status ->
            telegramMembership(botReplying(memberResult(status))).isMember(GROUP, 1L) shouldBe true
        }
        // `restricted` with `is_member: false` is somebody restricted and NOT in the chat.
        // Reading the status string alone would fan a showing — with their handle on it —
        // out to a group they do not belong to.
        listOf("left", "kicked", "restricted_left").forEach { status ->
            telegramMembership(botReplying(memberResult(status))).isMember(GROUP, 1L) shouldBe false
        }
    }

    "the membership probe denies on a Telegram refusal and on a transport failure" {
        // A hiccup must never read as "probably a member" — same rule as AdminCommands.isAdmin.
        telegramMembership(refusingBot(mutableListOf())).isMember(GROUP, 1L) shouldBe false
        telegramMembership(throwingBot()).isMember(GROUP, 1L) shouldBe false
    }

    "the name lookup reads a handle live, and answers null when it cannot" {
        telegramNames(botReplying(chatResult("bob", "Bob", null))).handleFor(1L) shouldBe Handle("bob", "Bob")
        // No first name: the title, then the stand-in — never an empty label.
        telegramNames(botReplying(chatResult("bob", null, "The Group"))).handleFor(1L) shouldBe
            Handle("bob", "The Group")
        telegramNames(botReplying(chatResult(null, null, null))).handleFor(1L) shouldBe
            Handle(null, "this person")
        // Null is what GiveUpService already reads as "no route".
        telegramNames(refusingBot(mutableListOf())).handleFor(1L) shouldBe null
        telegramNames(throwingBot()).handleFor(1L) shouldBe null
    }

    "the give-up died notice reaches each person, and names nobody" {
        val sent = mutableListOf<Call>()
        telegramGiveUpDied(recordingBot(sent))(listOf(7L, 8L))

        sent shouldHaveSize 2
        sent.map { it.path } shouldBe listOf("sendMessage", "sendMessage")
        // A private chat's id IS the person's user id, so these are the two recipients.
        sent[0].body shouldContain """"chat_id":7"""
        sent[1].body shouldContain """"chat_id":8"""
        // Names nobody: no handle, no mention link, no counterparty id, no amount.
        sent.forEach { call ->
            call.body shouldNotContain "@"
            call.body shouldNotContain "tg://user"
            call.body shouldContain "nothing was passed on"
        }
    }

    "one unreachable person does not cost everybody after them their notice" {
        val sent = mutableListOf<Call>()
        // The rows are already deleted when this runs, so a throw is not retryable —
        // it would simply lose the rest of the list.
        telegramGiveUpDied(throwingOnceBot(sent))(listOf(7L, 8L, 9L))

        sent shouldHaveSize 2
        sent[0].body shouldContain """"chat_id":8"""
        sent[1].body shouldContain """"chat_id":9"""
    }

    // ---- Fix 5: the presser is never told a delivery that did not happen ----

    "a disclosure that could not be delivered is not reported as delivered" {
        val f = PrivateFixture("disclosefail")
        val a = f.requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        // Both sides have agreed, so the second press discloses.
        f.giveUps.record(b.refToken, a.refToken, 2L, Stance.OFFERED)
        val sent = mutableListOf<Call>()
        giveUpCallback(a.refToken, b.refToken, callbackFrom(1L), refusingBot(sent))
        val alert = sent.single { it.path == "answerCallbackQuery" }.body
        alert shouldContain "couldn't get a message through to either of you"
        alert shouldNotContain "I've passed your names"
        // Nothing was recorded, because nothing arrived.
        f.messages.logged(1L, 1L) shouldBe null
        f.messages.logged(2L, 1L) shouldBe null
    }

    "a disclosure that lands is still reported as delivered" {
        val f = PrivateFixture("discloseok")
        val a = f.requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        f.giveUps.record(b.refToken, a.refToken, 2L, Stance.OFFERED)
        val sent = mutableListOf<Call>()
        giveUpCallback(a.refToken, b.refToken, callbackFrom(1L), recordingBot(sent))
        sent.single { it.path == "answerCallbackQuery" }.body shouldContain "I've passed your names"
    }

    // ---- Fix 2: the hint every remaining refusal shares ----

    "the group-only hint claims nothing about admins, since the check runs before the admin one" {
        PrivateFixture("hintwording")
        val sent = mutableListOf<Call>()
        fanout(updateFor(DM, ChatType.Private, "/fanout on"), recordingBot(sent))
        val body = sent.single().body
        body shouldContain "group chat"
        body shouldNotContain "admin"
    }

    // ---- C-1: a private /done closes nothing on one person's word ----

    "a private /done closes nothing until the counterparty answers" {
        val f = PrivateFixture("donestranger")
        // Mallory states one interest of her own, purely to have a short id to type.
        val mine = f.requests.create(
            NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        val victim = f.requests.create(
            NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2",
        )
        val showing = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()

        done(mentionUpdate(DM, ChatType.Private, "/done ${mine.shortId} @ann"), recordingBot(sent))

        // The declarer is told an ask went out, and told plainly that it settled nothing.
        sent.joinToString { it.body } shouldContain "Nothing's closed yet"
        // And nothing closed: not the other person's interest, not its showing, not the caller's.
        f.requests.byRefToken(victim.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(showing.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
    }

    "a private /done naming somebody nothing here belongs to is refused, and names nobody" {
        val f = PrivateFixture("doneoracle")
        val mine = f.requests.create(
            NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val toResting = mutableListOf<Call>()
        val toNobody = mutableListOf<Call>()

        // @ann rests an opposite-side interest; @zoe rests nothing at all.
        done(mentionUpdate(DM, ChatType.Private, "/done ${mine.shortId} @ann"), recordingBot(toResting))
        done(mentionUpdate(DM, ChatType.Private, "/done ${mine.shortId} @zoe"), recordingBot(toNobody))

        // The refusal names nobody, and says nothing about anyone the caller may have meant.
        toNobody.single().body shouldNotContain "zoe"
        toNobody.single().body shouldContain "aren't a pair"
        // Neither closed the caller's own interest, and neither closed anybody else's.
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        // Two messages now go out for an ask: the declarer's own notice, and the question
        // to the counterparty (Task 7) — keyed on text, not count, for the same reason the
        // sibling test above is.
        toResting.first { it.body.contains("Nothing's closed yet") }.shouldNotBeNull()
    }

    "a private /done closes both interests once the counterparty confirms" {
        val f = PrivateFixture("doneconsented")
        val mine = f.requests.create(
            NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        val theirs = f.requests.create(
            NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2",
        )
        val showing = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()

        done(mentionUpdate(DM, ChatType.Private, "/done ${mine.shortId} @ann"), recordingBot(sent))

        // The typed form only asks; nothing has closed on bob's word alone.
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
        sent.first { it.path == "sendMessage" }.body shouldContain "@ann"

        // Ann answers. Task 7 gives that answer a button; the service is what decides.
        Registry.lifecycle.confirm(2L, mine.refToken, theirs.refToken)
            .shouldBeInstanceOf<ActionResult.Ok>()

        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.DONE
        f.requests.byRefToken(showing.refToken)!!.state shouldBe RequestState.DONE
    }

    // ---- Review follow-up: neither the ask nor the confirmation notice is pinned to
    // arrive in the RIGHT person's own chat by any test — a fixture where both parties
    // share one chat can't tell "sent to the right chat" apart from "sent to whichever
    // chat everything happens to share." Both people here speak privately, so their own
    // chat IS their user id, and the counterparty presses from a THIRD chat (a group)
    // that is neither of their own — so a message landing there by mistake is caught.

    "the ask reaches the counterparty's own chat, not wherever the declarer typed" {
        val f = PrivateFixture("done-routes-ask")
        val mine = f.requests.create(
            NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        f.requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()

        // bob types this in chat DM (555) — the question must reach ann's OWN chat,
        // her user id (2), not DM and not bob's own user id (1).
        done(mentionUpdate(DM, ChatType.Private, "/done ${mine.shortId} @ann"), recordingBot(sent))

        val ask = sent.first { it.body.contains("Did you?") }
        ask.body shouldContain """"chat_id":2"""
        ask.body shouldNotContain """"chat_id":555"""
        ask.body shouldNotContain """"chat_id":1"""
    }

    "the confirmation notice reaches the declarer's own chat, not wherever the peer pressed" {
        val f = PrivateFixture("done-routes-notice")
        val mine = f.requests.create(
            NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        val theirs = f.requests.create(
            NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2",
        )
        val sent = mutableListOf<Call>()

        // Ann presses Yes from the group — nowhere near either of their own chats. The
        // notice to bob must still land in HIS own chat, his user id (1), never the
        // group (-100) she actually pressed from.
        confirmDoneCallback(mine.refToken, theirs.refToken, callbackFrom(2L, GROUP), recordingBot(sent))

        val notice = sent.first { it.body.contains("confirmed") }
        notice.body shouldContain """"chat_id":1"""
        notice.body shouldNotContain """"chat_id":-100"""
    }

    // ---- I-1: one dead chat must not starve the announcements behind it ----

    "a chat that refuses does not stop the chat after it from being told" {
        val f = PrivateFixture("sinkstarve")
        val a = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val sent = mutableListOf<Call>()
        // Keyed on the text, not on call order, so a client-side retry cannot change which
        // announcement is the refused one.
        val bot = TelegramBot(
            token = "000:fake-token-for-tests",
            httpClient = HttpClient(MockEngine { request ->
                val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
                sent += Call(request.url.encodedPath.substringAfterLast('/'), body)
                if (body.contains("first")) {
                    respond(
                        """{"ok":false,"error_code":403,"description":"Forbidden"}""",
                        HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-100,"type":"group"}}}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }),
        )
        shouldThrow<AnnouncementNotSent> {
            telegramSink(bot).deliver(
                listOf(
                    Announcement(GROUP, "first", emptyList(), listOf(a.refToken), listOf(1L)),
                    Announcement(-200L, "second", emptyList(), listOf(a.refToken), listOf(1L)),
                ),
                emptyList(),
            )
        }
        // The refused chat costs its own announcement and nothing else: the one behind it
        // was attempted, landed, and was recorded.
        f.messages.logged(GROUP, 1L) shouldBe null
        f.messages.logged(-200L, 1L).shouldNotBeNull().text shouldBe "second"
        sent.map { it.body }.any { it.contains("second") } shouldBe true
    }

    // ---- I-2: a private Undo writes no chat_settings row ----

    "a private Undo writes no chat_settings row for the person's own chat" {
        val f = PrivateFixture("undoprivate")
        val mine = f.requests.create(
            NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        val showing = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.CANCELLED)
        f.requests.transition(showing.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val sent = mutableListOf<Call>()

        reopenCallback(mine.refToken, callbackFrom(1L), recordingBot(sent))

        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(showing.refToken)!!.state shouldBe RequestState.OPEN
        // A private chat's id IS a person's user id, so a default row written here would be
        // a stored record of the person themselves — a fan-out candidate no /forget erases.
        f.chats.allChats().map { it.chatId } shouldBe listOf(GROUP)
    }

    // ---- I-5: a wrongly closed private interest has a recovery command ----

    "a private /reopen brings the interest back, every showing with it" {
        val f = PrivateFixture("privreopen")
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        val shortId = f.requests.resting(NO_CHAT_ID).single().shortId
        cancel(updateFor(DM, ChatType.Private, "/cancel $shortId"), recordingBot(sent))
        f.requests.resting(NO_CHAT_ID).shouldBeEmpty()
        sent.clear()

        reopen(updateFor(DM, ChatType.Private, "/reopen"), recordingBot(sent))

        f.requests.resting(NO_CHAT_ID) shouldHaveSize 1
        f.requests.resting(GROUP) shouldHaveSize 1
        sent.first { it.path == "sendMessage" }.body shouldContain "Resting again"
        // And the same phantom row I-2 is about: reading a chat's settings for a DM writes one.
        f.chats.allChats().map { it.chatId } shouldBe listOf(GROUP)
    }
})
