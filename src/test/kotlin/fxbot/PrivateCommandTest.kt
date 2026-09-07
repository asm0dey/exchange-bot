package fxbot

import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.common.CallbackQuery
import eu.vendeli.tgbot.types.common.Update
import eu.vendeli.tgbot.types.component.CallbackQueryUpdate
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.msg.Message
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
        Registry.lifecycle = LifecycleService(requests, chats, rates)
        Registry.buttons = ButtonService(messages, requests)
        Registry.forget = ForgetService(requests, messages)
        Registry.admin = AdminService(chats, client)
        Registry.interests = InterestService(
            requests, chats, people, rates, client, giveUps, pending,
            MembershipProbe { _, _ -> true },
        )
        Registry.giveUpService = GiveUpService(requests, giveUps, NameLookup { handles[it] })
        Registry.batcher = AnnouncementBatcher(
            requests, chats, pending, Registry.interests, rates,
            { announcements, pings -> delivered += announcements to pings },
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clock,
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

class PrivateCommandTest : StringSpec({
    "a private /sell without 'for' is refused with the example" {
        val f = PrivateFixture("privsellnofor")
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR"), recordingBot(sent))
        sent.single().body shouldContain "/sell 10 EUR for RUB"
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
    }

    "a private /sell with 'for' states an interest and replies at once" {
        val f = PrivateFixture("privsell")
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        f.requests.resting(NO_NAMES_CHAT_ID) shouldHaveSize 1
        // Fan-out reached the configured group, and the showing rests there immediately.
        f.requests.resting(GROUP) shouldHaveSize 1
        sent.last().path shouldBe "sendMessage"
        sent.last().body shouldContain "1 group"
    }

    "the private reply is recorded against every ref token its buttons name" {
        val f = PrivateFixture("privrecord")
        // Somebody to be found, so the reply carries give-up buttons naming THEIR token.
        val peer = f.requests.create(
            NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2",
        )
        val sent = mutableListOf<Call>()
        sell(updateFor(DM, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        val mine = f.requests.resting(NO_NAMES_CHAT_ID).single { it.userId == 1L }
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
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
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
        val shortId = f.requests.resting(NO_NAMES_CHAT_ID).single().shortId
        cancel(updateFor(DM, ChatType.Private, "/cancel $shortId"), recordingBot(sent))
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
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
        val a = f.requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
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
        val a = f.requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
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
        val a = f.requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
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
        val a = f.requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Call>()
        declineCallback(a.refToken, b.refToken, callbackFrom(1L), recordingBot(sent))
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.DECLINED
        f.giveUps.declined(b.refToken, a.refToken) shouldBe true
        sent.single().path shouldBe "answerCallbackQuery"
        sent.single().body shouldNotContain "@ann"
    }

    "a decline pressed by someone who owns neither request writes nothing" {
        val f = PrivateFixture("declineforged")
        val a = f.requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
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
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
    }

    "restating a privately stated interest states it again privately, and it fans out" {
        val f = PrivateFixture("restatepriv")
        val mine = f.requests.create(
            NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1",
        )
        val theirs = f.requests.create(
            NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7, "i2",
        )
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(theirs.refToken, RequestState.OPEN, RequestState.DONE)
        val sent = mutableListOf<Call>()
        restateCallback(mine.refToken, theirs.refToken, callbackFrom(1L), recordingBot(sent))
        f.requests.resting(NO_NAMES_CHAT_ID).single().statedAmount shouldBe BigDecimal("400")
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
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
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
        // link two people on a no-names basis before either pressed anything.
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
            "sell", "buy", "tolerance", "status", "cancel", "done", "settings", "forget", "help",
        )
        // Admin-only commands have no private meaning, so they are not suggested there.
        PRIVATE_COMMANDS.map { it.first } shouldNotContain "pair"
        PRIVATE_COMMANDS.map { it.first } shouldNotContain "tif"
        PRIVATE_COMMANDS.map { it.first } shouldNotContain "fanout"
    }
})
