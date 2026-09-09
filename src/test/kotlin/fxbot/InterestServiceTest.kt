package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

private val T0 = Instant.parse("2026-09-06T12:00:00Z")
private val EURRUB = CurrencyPair("EUR", "RUB")
private const val GROUP = -100L
private const val OTHER_GROUP = -200L

/** Everything an InterestService needs, plus the repositories a test wants to look at. */
private class InterestFixture(
    name: String,
    feedBody: String? = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}""",
    val membership: MembershipProbe = MembershipProbe { _, _ -> true },
) {
    // `DB_CLOSE_DELAY=-1` keeps an in-memory database alive for the whole JVM, so two
    // test classes asking for the same name share one — and this fixture's crypto keys
    // are fresh per instance, so a shared database fails to open the other's payloads.
    // The prefix keeps every name in this file to itself.
    val ds = memDataSource("interest_$name").also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, clock)
    val chats = ChatSettingsRepository(ds, crypto, clock)
    val people = PersonSettingsRepository(ds, crypto, clock)
    val pending = PendingAnnouncementRepository(ds, crypto, clock)
    val rateRepo = RateRepository(ds)
    var feedCalls = 0
    val client = RateClient(HttpClient(MockEngine {
        feedCalls++
        if (feedBody == null) respondError(HttpStatusCode.ServiceUnavailable)
        else respond(feedBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }))
    val rates = RateService(client, rateRepo, clock)
    val svc = InterestService(requests, chats, people, rates, client, pending, membership)

    fun withRate() = apply { rateRepo.put("EUR", "RUB", BigDecimal("99.98"), T0) }
    fun chat(id: Long, pair: CurrencyPair = EURRUB, tolerance: Int = 20, tif: Int = 7, fanOut: Boolean = true) =
        apply { chats.save(ChatSettings(id, pair, tolerance, tif, fanOut)) }
}

class InterestServiceTest : StringSpec({
    "the pair is canonical whichever way round it is stated" {
        canonicalPair("RUB", "EUR") shouldBe EURRUB
        canonicalPair("EUR", "RUB") shouldBe EURRUB
    }

    "the two ways of saying the same thing land on the same pair and the same side" {
        // "sell 10 EUR for RUB" and "buy 10 RUB for EUR" both hand over EUR to receive
        // RUB. The canonical pair is what puts them in the same space; sideFor then
        // agrees they are the SAME side, so they are not counterparties. (They are the
        // same side but NOT the same size: 10 EUR against 10 RUB. Anyone opposite them
        // therefore meets them at very different sizes, which is a tolerance question
        // and is covered by its own tests below.)
        val f = InterestFixture("sameside").withRate()
        val a = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        val b = f.svc.state(2L, "ann", Verb.BUY, "10", "RUB", "EUR")
        a.shouldBeInstanceOf<InterestResult.Stated>()
        b.shouldBeInstanceOf<InterestResult.Stated>()
        a.interest.pair shouldBe EURRUB
        b.interest.pair shouldBe EURRUB
        b.interest.side shouldBe a.interest.side
        b.shown.flatMap { it.found }.shouldBeEmpty()
    }

    "an interest rests in the bot and in every fitting chat" {
        val f = InterestFixture("fanout").withRate()
            .chat(-100L, EURRUB)
            .chat(-200L, CurrencyPair("RUB", "EUR"))          // the same two currencies, other way round
            .chat(-300L, CurrencyPair("USD", "GBP"))          // a different pair
            .chat(-400L, EURRUB, fanOut = false)              // fan-out turned off
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.interest.chatId shouldBe NO_CHAT_ID
        r.showings.map { it.chatId }.toSet() shouldBe setOf(-100L, -200L)
        r.showings.map { it.interestToken }.toSet() shouldBe setOf(r.interest.interestToken)
    }

    "a showing takes its own chat's pair orientation, so its side is right there" {
        val f = InterestFixture("orientation").withRate().chat(-200L, CurrencyPair("RUB", "EUR"))
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        val showing = r.showings.single()
        showing.pair shouldBe CurrencyPair("RUB", "EUR")
        // Handing over EUR is handing over the QUOTE of RUB/EUR, so in that chat this is a Bid.
        showing.side shouldBe Side.BID
        showing.statedCurrency shouldBe "EUR"
        showing.statedAmount shouldBe BigDecimal("10")
    }

    "a showing's counterparties are judged at its chat's tolerance, not the person's" {
        val f = InterestFixture("counterparties_showing")
        f.chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))
        val showing = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val peer = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        f.svc.counterparties(showing).map { it.request.refToken } shouldBe listOf(peer.refToken)
    }

    "a showing is priced on its own pair, never the chat's current one, once an admin repairs it" {
        // bob's 1000 EUR and ann's 99980 RUB are an exact match at EUR/RUB = 99.98 — the
        // rate this fixture caches. Both rows carry EUR/RUB as their own pair permanently;
        // only the chat's CONFIGURED pair is about to move out from under them.
        val f = InterestFixture("showing_priced_own_pair").withRate()
        f.chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))
        val bob = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val ann = f.requests.create(GROUP, 2L, "ann", Side.BID, "RUB", BigDecimal("99980"), EURRUB, 7, "i2")
        f.svc.counterparties(bob).map { it.request.refToken } shouldBe listOf(ann.refToken)

        // An admin repairs the chat to EUR/USD. bob's and ann's showings still carry
        // EUR/RUB — only the chat's setting has moved. Pricing bob at the chat's NEW pair
        // would read ann's 99980 RUB against a EUR/USD rate instead, turning her tiny
        // residual into a huge one and dropping a pairing that has not changed at all.
        f.rateRepo.put("EUR", "USD", BigDecimal("1.1"), T0)
        f.chats.save(ChatSettings(GROUP, CurrencyPair("EUR", "USD"), 20, 7, true))
        f.svc.counterparties(bob).map { it.request.refToken } shouldBe listOf(ann.refToken)
    }

    "a chat the person is not in is dropped" {
        val f = InterestFixture("notmember", membership = MembershipProbe { chatId, _ -> chatId == -100L })
            .withRate().chat(-100L).chat(-200L)
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.showings.map { it.chatId } shouldBe listOf(-100L)
    }

    "every showing rests immediately, and every chat is owed an announcement" {
        val f = InterestFixture("restfirst").withRate().chat(-100L).chat(-200L)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.requests.resting(-100L) shouldHaveSize 1
        f.requests.resting(-200L) shouldHaveSize 1
        // Two rows is not the point — one per chat is. Two queued against the same chat
        // would count the same and leave the other chat silent.
        val owed = f.pending.allFor(1L)
        owed shouldHaveSize 2
        owed.count { f.pending.isFor(it, -100L) } shouldBe 1
        owed.count { f.pending.isFor(it, -200L) } shouldBe 1
    }

    "the bot-side row and each showing take their time in force from different places" {
        // Every other fixture chat uses 7, which is also NO_CHAT_TIF_DAYS, so nothing
        // else here can tell the two sources apart. This chat uses 3.
        val f = InterestFixture("tif").withRate().chat(-100L, tif = 3)
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.interest.expiresAt shouldBe T0.plus(NO_CHAT_TIF_DAYS.toLong(), ChronoUnit.DAYS)
        r.showings.single().expiresAt shouldBe T0.plus(3L, ChronoUnit.DAYS)
    }

    "a chat whose membership cannot be checked is dropped, and the interest still rests" {
        // The interest row already exists by the time fan-out runs, so a probe that throws
        // must cost one chat's showing, never the whole statement.
        val f = InterestFixture("probethrows", membership = MembershipProbe { chatId, _ ->
            if (chatId == -200L) error("telegram said no") else true
        }).withRate().chat(-100L).chat(-200L)
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.showings.map { it.chatId } shouldBe listOf(-100L)
        f.requests.resting(-200L).shouldBeEmpty()
        // And exactly one slot of the cap was spent, so a retry is not needed.
        f.requests.countOpenInterests(1L) shouldBe 1
    }

    "the two surfaces never meet" {
        val f = InterestFixture("surfaces").withRate().chat(-100L)
        // Someone who has only ever typed in a chat is invisible to the bot-side.
        f.requests.create(-100L, 9L, "chatonly", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7)
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        // The chatless row — the first entry — sees only what rests with no chat behind it.
        // bob's SHOWING in -100 does find that person, and says so in its own entry.
        r.shown.first().found.shouldBeEmpty()
    }

    "each side of a pairing with no chat is judged at its own tolerance" {
        val f = InterestFixture("owntolerance").withRate()
        f.people.save(PersonSettings(1L, 50))
        f.svc.state(1L, "bob", Verb.SELL, "4", "EUR", "RUB")
        val r = f.svc.state(2L, "ann", Verb.BUY, "2", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.shown.flatMap { it.found } shouldHaveSize 1 // ann has nothing left over; bob's 50% leftover is within his own 50

        val g = InterestFixture("owntolerance2").withRate()
        g.people.save(PersonSettings(1L, 20))
        g.svc.state(1L, "bob", Verb.SELL, "4", "EUR", "RUB")
        val s = g.svc.state(2L, "ann", Verb.BUY, "2", "EUR", "RUB")
        s.shouldBeInstanceOf<InterestResult.Stated>()
        s.shown.flatMap { it.found }.shouldBeEmpty() // bob's own 20 excludes it, so they are not counterparties
    }

    "everyone who gains a counterparty is named so they can be pinged" {
        val f = InterestFixture("appeared").withRate()
        f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        val r = f.svc.state(2L, "ann", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        r.appeared.map { it.userId } shouldBe listOf(1L)
        r.appeared.single().refToken shouldBe f.requests.resting(NO_CHAT_ID).single { it.userId == 1L }.refToken
    }

    "a sixth resting interest is refused, naming the cap and how to make room" {
        // A chat is configured so each interest is two rows but still one interest —
        // five interests here are ten resting requests, and it is the interests that count.
        val f = InterestFixture("cap").withRate().chat(-100L)
        repeat(5) { f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") }
        f.requests.resting(-100L) shouldHaveSize 5
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Rejected>()
        r.reason shouldContain "5"
        r.reason shouldContain "/cancel"
        f.requests.countOpenInterests(1L) shouldBe 5
    }

    "the cap counts only what is resting" {
        val f = InterestFixture("capfree").withRate().chat(-100L)
        repeat(5) { f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") }
        val token = f.requests.resting(NO_CHAT_ID).first { it.userId == 1L }.interestToken!!
        f.requests.closeInterest(token, RequestState.CANCELLED)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
    }

    "a pair no chat uses is priced once, live, before it is accepted" {
        val f = InterestFixture("pricelive") // no cached rate, no chats
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        f.feedCalls shouldBe 1
    }
    "a pair the feed answers without is refused" {
        val f = InterestFixture("pricemissing", feedBody = """{"result":"success","base_code":"EUR","rates":{"USD":1.1}}""")
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Rejected>()
        r.reason shouldContain "EUR/RUB"
        f.feedCalls shouldBe 1
        f.requests.resting(NO_CHAT_ID).shouldBeEmpty()
    }
    "a pair is accepted when the feed cannot be reached at all" {
        // Refusing a legitimate pair during an outage is the harder failure to explain.
        val f = InterestFixture("priceoutage", feedBody = null)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
        f.feedCalls shouldBe 1
    }
    "a pair some chat already uses costs no call at all" {
        val f = InterestFixture("pricechat", feedBody = null).chat(-100L, EURRUB)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
        f.feedCalls shouldBe 0
    }
    "a cached rate costs no call either" {
        val f = InterestFixture("pricecached", feedBody = null).withRate()
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
        f.feedCalls shouldBe 0
    }

    "an unreadable amount, an unknown code, and the same code twice are all refused" {
        val f = InterestFixture("badinput").withRate()
        (f.svc.state(1L, "bob", Verb.SELL, "lots", "EUR", "RUB") as InterestResult.Rejected).reason shouldContain "amount"
        (f.svc.state(1L, "bob", Verb.SELL, "10", "XYZ", "RUB") as InterestResult.Rejected).reason shouldContain "XYZ"
        (f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "EUR") as InterestResult.Rejected).reason shouldContain "two different"
    }

    "a statement reports counterparties from its showings as well as bot-wide" {
        val f = InterestFixture("stated_shown")
        f.chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))
        val inGroup = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        val r = f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.shown.first().request.chatId shouldBe NO_CHAT_ID
        r.shown.flatMap { s -> s.found.map { it.request.refToken } } shouldContain inGroup.refToken
        // The done pairs the counterparty with the row it was found AGAINST — the showing,
        // not the chatless interest. LifecycleService.done refuses a pairing whose two
        // tokens are in different scopes, so the wrong subject here is a dead button that
        // still renders perfectly.
        val showing = r.showings.single()
        val data = statedButtons(r).map { it.data }
        data shouldContain Cb.done(showing.refToken, inGroup.userId)
        data.contains(Cb.done(r.interest.refToken, inGroup.userId)) shouldBe false
    }

    "nobody is named twice in one reply" {
        val f = InterestFixture("stated_dedup")
        f.chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))
        f.chats.save(ChatSettings(OTHER_GROUP, EURRUB, 20, 7, fanOut = true))
        f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        f.requests.create(OTHER_GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        val r = f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.shown.flatMap { s -> s.found.map { it.request.userId } } shouldBe listOf(2L)
        // FIRST occurrence wins, and which one survives decides which pairing the done
        // offers: keeping the last would pass the check above and change every button.
        r.shown.drop(1).map { it.found.size } shouldBe listOf(1, 0)
    }

    "status lists each interest once, with where it still rests" {
        val f = InterestFixture("standings").withRate().chat(-100L).chat(-200L)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        val lapsed = f.requests.resting(-200L).single()
        f.requests.transition(lapsed.refToken, RequestState.OPEN, RequestState.EXPIRED)
        val standings = f.svc.standings(1L)
        standings shouldHaveSize 1
        standings.single().chatIds shouldContainExactlyInAnyOrder listOf(-100L)
    }
})
