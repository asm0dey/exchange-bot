package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.types.User
import eu.vendeli.tgbot.types.chat.Chat
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.common.CallbackQuery
import eu.vendeli.tgbot.types.common.Update
import eu.vendeli.tgbot.types.component.CallbackQueryUpdate
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.msg.Message
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.math.BigDecimal
import kotlin.time.Instant

/** The one group these fixtures use — mirrors `PrivateCommandTest`'s constant of the same name. */
private const val GROUP = -100L

/**
 * Covers the `replyToDecision` fix (`LifecycleCommands.kt`): HTML parse mode must apply
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
    val settings = ChatSettingsRepository(ds, crypto)
    val people = PersonSettingsRepository(ds, crypto)
    val refusals = DoneRefusalRepository(ds)

    /** Never reached: no rate is cached, so every status is unavailable without a request going out. */
    val rates = RateService(
        RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })),
        RateRepository(ds),
    )

    init {
        Registry.requests = requests
        Registry.messages = messages
        Registry.settings = settings
        Registry.names = NameLookup { null }
        Registry.lifecycle = LifecycleService(
            requests, settings, rates, people, refusals, Registry.names,
            AskLookup { token, payload -> messages.offered(token, payload) },
        )
        Registry.buttons = ButtonService(messages, requests)
    }

    /** A resting request, with sensible defaults for everything a done/ask test doesn't care about. */
    fun rest(chatId: Long, userId: Long, username: String, side: Side): Request =
        requests.create(chatId, userId, username, side, "EUR", BigDecimal("1000"), CurrencyPair("EUR", "RUB"), 7)

    /** A `/done ...`-shaped update, typed by [userId] in the fixture's one group. */
    fun groupUpdate(userId: Long, text: String): MessageUpdate = updateFor(GROUP, ChatType.Group, text, userId)

    /**
     * A bot whose every send gets its OWN message id, the way Telegram's does. [recordingBot]
     * answers 1 to everything, which is fine when a test looks at what was sent — but two
     * messages recorded under one id are one row in the log, so a test about WHICH messages
     * were recorded cannot use it. The counter is per fixture, so ids stay predictable
     * within a test and cannot collide across the two bots one test may build.
     */
    fun countingBot(sink: MutableList<Call>): TelegramBot {
        val client = HttpClient(MockEngine { request ->
            val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
            val path = request.url.encodedPath.substringAfterLast('/')
            sink += Call(path, bytes.decodeToString())
            val id = nextMessageId++
            respond(
                content = """{"ok":true,"result":{"message_id":$id,"date":0,"chat":{"id":$GROUP,"type":"group"}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        return TelegramBot(token = "000:fake-token-for-lifecycle-test", httpClient = client)
    }

    private var nextMessageId = 1L

    /** A press, in the fixture's one group — the shape `PrivateCommandTest`'s `callbackFrom` uses. */
    fun callback(userId: Long, chatId: Long = GROUP): CallbackQueryUpdate {
        val chat = Chat(id = chatId, type = ChatType.Group)
        val user = User(id = userId, isBot = false, firstName = "P$userId")
        val message = Message(messageId = 1L, date = Instant.fromEpochSeconds(0), chat = chat, from = user)
        val query = CallbackQuery(id = "q1", from = user, message = message, chatInstance = "ci")
        return CallbackQueryUpdate(updateId = 1, origin = Update(updateId = 1), callbackQuery = query)
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

    /**
     * The seam Ruling B12 was about: `/reopen` used to send its confirmation and stop
     * there, leaving every message that had carried the request permanently saying
     * "withdrawn" with its buttons stripped. Both halves of the fix are unit-tested
     * elsewhere — the service reports what it revived, ButtonService rewrites from live
     * state — but only driving the real handler proves they are wired together.
     */
    "/reopen rewrites the message that said the request was withdrawn" {
        val f = LifecycleCommandFixture("reopen-rewrites")
        val a = f.requests.create(
            -100L, 1L, "bob", Side.OFFER, "EUR", java.math.BigDecimal("1000"), CurrencyPair("EUR", "RUB"), 7,
        )
        f.messages.record(
            -100L, 10L, listOf(a.refToken), listOf(1L), "1 person matches:",
            listOf(Button("✖️ Cancel", Cb.cancel(a.refToken))),
        )
        f.requests.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val sent = mutableListOf<Call>()

        reopen(updateFor(-100L, ChatType.Group, "/reopen"), recordingBot(sent))

        val edits = sent.filter { it.path == "editMessageText" }
        edits shouldHaveSize 1
        edits.single().body shouldContain "1 person matches:"
        edits.single().body shouldNotContain "withdrawn" // the false outcome is off the screen
        edits.single().body shouldContain Cb.cancel(a.refToken) // and the button is back
        val confirmation = sent.first { it.path == "sendMessage" }
        confirmation.body shouldContain "Resting again"
        // No undo on a request that is resting again — it could only answer "already waiting".
        confirmation.body shouldNotContain Cb.reopen(a.refToken)
    }

    "a successful /cancel still uses HTML parse mode" {
        val f = LifecycleCommandFixture("cancel-ok-html")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", java.math.BigDecimal("1000"), CurrencyPair("EUR", "RUB"), 7)
        val sent = mutableListOf<LcSent>()

        cancel(updateFor(-100L, ChatType.Group, "/cancel ${a.shortId}"), recordingBot(sent))

        val confirmation = sent.first { it.path == "sendMessage" }
        confirmation.body shouldContain "\"parse_mode\":\"HTML\""
    }

    "a done sends the question to the counterparty and tells the declarer nothing closed" {
        val f = LifecycleCommandFixture("cmd_ask")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), bot)
        val sent = calls.filter { it.path == "sendMessage" }
        sent shouldHaveSize 2
        sent.first { it.body.contains("Did you?") }.body shouldContain "\"yes?a="
        sent.first { it.body.contains("Did you?") }.body shouldContain "\"no?a="
        sent.first { it.body.contains("Nothing's closed yet") }.shouldNotBeNull()
    }

    "a Yes closes both and tells the declarer where they spoke" {
        val f = LifecycleCommandFixture("cmd_yes")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        // Bob declares it first: a Yes is honoured only for a question really asked, and
        // `sendAsk` is what records that it was. Its own sends go to a separate sink so
        // the counts below stay about the confirmation alone.
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), recordingBot(mutableListOf<Call>()))
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        confirmDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), bot)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        calls.count { it.path == "sendMessage" && it.body.contains("Marked done") } shouldBe 2
    }

    "a No closes nothing and the declarer is not told" {
        val f = LifecycleCommandFixture("cmd_no")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), recordingBot(mutableListOf<Call>()))
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        refuseDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), bot)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        calls.none { it.path == "sendMessage" } shouldBe true
        // The presser still hears "Noted" — privately, as a popup, not a group message.
        calls.single { it.path == "answerCallbackQuery" }.body shouldContain "Noted"
    }

    // The Done button carries the counterparty's USER ID in `b`, not their ref token
    // (`Cb.done`): a token there was a bearer capability delivered to the client, and
    // whoever read the message could close the other person's whole interest with it.

    "the done button press resolves its counterparty from the user id in the payload" {
        val f = LifecycleCommandFixture("cmd_done_by_id")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        doneCallback(mine.refToken, "2", f.callback(1L), recordingBot(calls))
        // Ann is asked, in the chat she typed in, and nothing has closed on bob's word.
        calls.single { it.path == "sendMessage" }.body shouldContain "Did you?"
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
        // And the question really asked is the one that can now be answered.
        confirmDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), recordingBot(mutableListOf<Call>()))
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.DONE
    }

    "a done payload whose b is not a user id answers the presser instead of throwing" {
        val f = LifecycleCommandFixture("cmd_done_bad_id")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        doneCallback(mine.refToken, "not-a-number", f.callback(1L), recordingBot(calls))
        calls.none { it.path == "sendMessage" } shouldBe true
        calls.single { it.path == "answerCallbackQuery" }.body shouldContain "looks broken"
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
    }

    // Cb.confirm/refuse deliberately reverse `done`'s ownership: `a` is always the
    // declarer's request, and a press is honoured only when the presser owns `b`. These
    // pin that direction — the declarer pressing their own ask must not authorize it.
    "confirmDoneCallback only honours the peer who owns b, not the declarer who owns a" {
        val f = LifecycleCommandFixture("cmd_yes_wrong_presser")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        confirmDoneCallback(mine.refToken, theirs.refToken, f.callback(1L), bot)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    "refuseDoneCallback only honours the peer who owns b, not the declarer who owns a" {
        val f = LifecycleCommandFixture("cmd_no_wrong_presser")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        refuseDoneCallback(mine.refToken, theirs.refToken, f.callback(1L), bot)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    // ---- Review follow-up: `sendAsk`/`sendChoice` must actually record what they send,
    // not just send it — a button naming somebody that isn't recorded is both a stale-
    // button hazard (MessageLogRepository.record's own doc comment) and an ADR 0005 gap.

    "sendAsk records the question against both people, so /forget from either reaches it" {
        val f = LifecycleCommandFixture("cmd_ask_recorded")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), recordingBot(mutableListOf<Call>()))

        val logged = f.messages.logged(GROUP, 1L)
        logged.shouldNotBeNull()
        logged.refTokens shouldContainExactlyInAnyOrder listOf(mine.refToken, theirs.refToken)
        f.messages.messagesForUser(1L, GROUP) shouldHaveSize 1
        f.messages.messagesForUser(2L, GROUP) shouldHaveSize 1
    }

    "sendChoice records the declarer's own token plus every candidate's" {
        val f = LifecycleCommandFixture("cmd_choose_recorded")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val alice = f.rest(GROUP, 2L, "alice", Side.BID)
        val carol = f.rest(GROUP, 3L, "carol", Side.BID)
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), recordingBot(mutableListOf<Call>()))

        val logged = f.messages.logged(GROUP, 1L)
        logged.shouldNotBeNull()
        logged.refTokens shouldContainExactlyInAnyOrder listOf(mine.refToken, alice.refToken, carol.refToken)
        f.messages.messagesForUser(1L, GROUP) shouldHaveSize 1
        f.messages.messagesForUser(2L, GROUP) shouldHaveSize 1
        f.messages.messagesForUser(3L, GROUP) shouldHaveSize 1
    }

    /**
     * The notice names BOTH people — "@ann confirmed. Marked done: @ann and @bob." — so
     * both must be recorded against it (ADR 0005). It used to derive its tokens from its
     * own keyboard, which carries the confirmer's token only when the swap left the
     * recipient something over. These two amounts are equal, the modal case for this bot,
     * so the keyboard offers a Reopen and nothing else, and @ann was named in a message
     * her own `/forget` could not reach.
     */
    "the notice about an exactly equal swap is recorded against the confirmer it names" {
        val f = LifecycleCommandFixture("cmd_notice_recorded")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), f.countingBot(mutableListOf()))
        val calls = mutableListOf<Call>()
        // Ann confirms — Ok.notify is what sendNotice sends, addressed to bob where he spoke.
        confirmDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), f.countingBot(calls))

        // Equal amounts leave nothing over, so the notice's keyboard is a Reopen alone —
        // it names ann nowhere, which is exactly why her token has to be carried instead.
        val notice = calls.single { it.path == "sendMessage" && it.body.contains("confirmed. Marked done") }
        notice.body shouldContain Cb.reopen(mine.refToken)
        notice.body shouldNotContain Cb.RESTATE

        val reachableByAnn = f.messages.messagesForUser(2L, GROUP)
            .mapNotNull { f.messages.logged(it.chatId, it.messageId)?.text }
        reachableByAnn.any { it.contains("confirmed. Marked done") } shouldBe true
        val reachableByBob = f.messages.messagesForUser(1L, GROUP)
            .mapNotNull { f.messages.logged(it.chatId, it.messageId)?.text }
        reachableByBob.any { it.contains("confirmed. Marked done") } shouldBe true
    }

    /**
     * `refuse` closes nothing, so nothing triggers the refresh pass and the ask keeps its
     * live keyboard. Two refusals are meant to be two separate asks — a first No is an
     * honest mix-up, a second is a pattern — so one ask must not be able to supply both.
     */
    "a No comes off the ask it answered, and a second tap cannot spend the declarer's last chance" {
        val f = LifecycleCommandFixture("cmd_no_strips_itself")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), f.countingBot(mutableListOf()))
        val calls = mutableListOf<Call>()

        refuseDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), f.countingBot(calls))

        // The ask is rewritten with the Yes alone — never stripped bare, because a change
        // of mind must still work.
        val edit = calls.single { it.path == "editMessageReplyMarkup" }
        edit.body shouldContain Cb.confirm(mine.refToken, theirs.refToken)
        edit.body shouldNotContain Cb.refuse(mine.refToken, theirs.refToken)
        val asked = f.messages.messagesForToken(theirs.refToken, 10)
            .mapNotNull { f.messages.logged(it.chatId, it.messageId) }
            .single { m -> m.buttons.any { it.data == Cb.confirm(mine.refToken, theirs.refToken) } }
        asked.buttons.map { it.data } shouldBe listOf(Cb.confirm(mine.refToken, theirs.refToken))

        // A second tap on that same No is a question nobody was asked, so it counts nothing.
        refuseDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), f.countingBot(mutableListOf()))
        f.refusals.count(mine.refToken, theirs.refToken) shouldBe 1

        // And the Yes still closes both, which is the whole reason it was left in place.
        confirmDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), f.countingBot(mutableListOf()))
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.DONE
    }

    /**
     * The declarer's own reply names the counterparty ("Asked @ann to confirm…"), so it is
     * a message about her and her `/forget` has to reach it (ADR 0005) — same contract
     * `sendAsk` one function up already meets for the question itself.
     */
    "the declarer's 'asked' reply is recorded against both people, not just sent" {
        val f = LifecycleCommandFixture("cmd_asked_reply_recorded")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)

        done(f.groupUpdate(1L, "/done ${mine.shortId}"), f.countingBot(mutableListOf()))

        // Two messages went out — the declarer's reply and the question — and both name
        // both people, so both are reachable from either side.
        f.messages.messagesForUser(1L, GROUP) shouldHaveSize 2
        f.messages.messagesForUser(2L, GROUP) shouldHaveSize 2
        val reply = f.messages.logged(GROUP, 1L)
        reply.shouldNotBeNull()
        reply.text!! shouldContain "Nothing's closed yet"
        reply.refTokens shouldContainExactlyInAnyOrder listOf(mine.refToken, theirs.refToken)
    }
})
