package fxbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")

/**
 * A clock that can be made to fail on demand, so a test can interrupt an action BETWEEN
 * the two closes a `/done` performs: [RequestRepository.closeInterest] reads the clock
 * exactly once per call, so arming it at one lets the presser's own interest close and
 * makes the counterparty's throw. Nothing in production code knows this exists — the
 * clock is a constructor parameter the repository already takes.
 */
private class BreakableClock(private val at: Instant) : Clock() {
    private var reads = 0
    private var failAfter = Int.MAX_VALUE
    override fun instant(): Instant {
        reads++
        check(reads <= failAfter) { "clock deliberately broken by the test" }
        return at
    }
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    /** Lets [n] more clock reads through, then fails. */
    fun breakAfter(n: Int) { reads = 0; failAfter = n }
    fun mend() { reads = 0; failAfter = Int.MAX_VALUE }
}

/** The rate feed is never reached in these tests: no pair is cached, so every status is unavailable. */
private fun deadRateService(ds: javax.sql.DataSource, clock: Clock) =
    RateService(
        RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })),
        RateRepository(ds),
        clock,
    )

private fun lifecycle(
    name: String,
    clock: Clock = Clock.fixed(T0, ZoneOffset.UTC),
): Pair<LifecycleService, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val repo = RequestRepository(ds, crypto, clock)
    val settings = ChatSettingsRepository(ds, crypto, clock)
    return LifecycleService(
        repo,
        settings,
        deadRateService(ds, Clock.fixed(T0, ZoneOffset.UTC)),
        PersonSettingsRepository(ds, crypto, clock),
        DoneRefusalRepository(ds, clock),
        NameLookup { null },
    ) to repo
}

private fun RequestRepository.put(chatId: Long, userId: Long, name: String, side: Side) =
    create(chatId, userId, name, side, "EUR", BigDecimal("1000"), EURRUB, 7)

/** The wiring a done needs now: person tolerances for the candidate search, and the refusal count. */
private class AskFixture(name: String) {
    private val ds = memDataSource(name).also { migrate(it) }
    private val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    private val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto, clock)
    val refusals = DoneRefusalRepository(ds, clock)
    val svc = LifecycleService(
        requests,
        ChatSettingsRepository(ds, crypto, clock),
        deadRateService(ds, clock),
        PersonSettingsRepository(ds, crypto, clock),
        refusals,
        NameLookup { null },
    )

    fun rest(chatId: Long, userId: Long, name: String?, side: Side, interest: String? = null) =
        requests.create(chatId, userId, name, side, "EUR", BigDecimal("1000"), EURRUB, 7, interest)
}

class LifecycleServiceTest : StringSpec({
    "the owner can cancel their own request" {
        val (svc, repo) = lifecycle("cancel")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "nobody else can cancel it" {
        val (svc, repo) = lifecycle("cancelother")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 2L, a.shortId).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "cancelling an unknown short id says so" {
        val (svc, _) = lifecycle("cancelmissing")
        svc.cancel(-100L, 1L, "zz").shouldBeInstanceOf<ActionResult.Gone>()
    }

    "a done asks, and the counterparty's Yes closes both sides" {
        val (svc, repo) = lifecycle("done")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Asked>()
        // Nothing yet: the declaration alone closes neither side.
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.OPEN
        svc.confirm(2L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
    }
    "either counterparty may declare it, and the other one answers" {
        val (svc, repo) = lifecycle("doneeither")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        val asked = svc.done(2L, a.refToken, b.refToken)
        asked.shouldBeInstanceOf<ActionResult.Asked>()
        // Alice declared, so bob is the one asked — and the tokens come back the way he answers them.
        asked.peerUserId shouldBe 1L
        svc.confirm(1L, asked.myToken, asked.peerToken).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
    }
    "a third party may not" {
        val (svc, repo) = lifecycle("donethird")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(3L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "a done about a pairing that has already closed reports it was already closed" {
        val (svc, repo) = lifecycle("donetwice")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken)
        svc.confirm(2L, a.refToken, b.refToken)
        val again = svc.done(2L, a.refToken, b.refToken)
        again.shouldBeInstanceOf<ActionResult.Gone>()
        again.text shouldContain "already"
    }
    "a forged token for a request the presser does not own is denied" {
        val (svc, repo) = lifecycle("forged")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(99L, b.refToken, a.refToken).shouldBeInstanceOf<ActionResult.Denied>()
    }
    "a counterparty who has already gone cannot be asked, and nothing closes" {
        val (svc, repo) = lifecycle("peergone")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.cancel(-100L, 2L, b.shortId)
        val result = svc.done(1L, a.refToken, b.refToken)
        result.shouldBeInstanceOf<ActionResult.Gone>()
        // Says WHICH side is gone, so the declarer can tell their own request from the other's.
        result.text shouldBe "@alice's request isn't waiting anymore."
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }

    // --- Fix round 1: holding one valid token must not authorise closing an unrelated
    // second request. The forged-token test above only proves a NON-participant (userId
    // 99, owning neither token) is refused; these prove a genuine participant who owns
    // ONE of the two tokens cannot pair it with an unrelated second token to close it.

    "a presser who owns one token cannot pair it with an uninvolved third party's token in the same chat" {
        val (svc, repo) = lifecycle("forgedsamechat")
        val mine = repo.put(-100L, 2L, "alice", Side.BID)
        val third = repo.put(-100L, 3L, "carol", Side.BID) // same side as `mine` — not a plausible pair
        svc.done(2L, mine.refToken, third.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(third.refToken)!!.state shouldBe RequestState.OPEN
    }
    "the same forged pairing across two different chats is refused" {
        val (svc, repo) = lifecycle("forgedcrosschat")
        val mine = repo.put(-100L, 2L, "alice", Side.BID)
        val third = repo.put(-200L, 4L, "dave", Side.OFFER) // opposite side, but an entirely different chat
        svc.done(2L, mine.refToken, third.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(third.refToken)!!.state shouldBe RequestState.OPEN
    }
    "two tokens on the same side are refused" {
        val (svc, repo) = lifecycle("samesidepair")
        val mine = repo.put(-100L, 1L, "bob", Side.OFFER)
        val other = repo.put(-100L, 5L, "erin", Side.OFFER)
        svc.done(1L, mine.refToken, other.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(other.refToken)!!.state shouldBe RequestState.OPEN
    }
    "a legitimate pair declared by the second counterparty still closes both, with each message phrased for its reader" {
        val (svc, repo) = lifecycle("secondpresser")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        val asked = svc.done(2L, a.refToken, b.refToken)
        asked.shouldBeInstanceOf<ActionResult.Asked>()
        // Phrased for alice, who declared it: the person named is the one being asked.
        asked.text shouldBe "Asked @bob to confirm. Nothing's closed yet."
        // Bob answers the question he was asked, so the tokens arrive as alice declared them.
        val result = svc.confirm(1L, asked.myToken, asked.peerToken)
        result.shouldBeInstanceOf<ActionResult.Ok>()
        // Names both owners — see the R42/R45 discussion this fix addresses: a bare
        // "Marked done" leaves a griefed party with no way to tell they were named.
        result.text shouldBe "Marked done: @bob and @alice. If that's wrong, /reopen."
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
    }
    "the outcome message reflects the presser's own request even when it sits in the button's second slot" {
        val (svc, repo) = lifecycle("reversedpeergone")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.cancel(-100L, 1L, a.shortId) // bob's request — payload slot "a" — closes first
        val result = svc.done(2L, a.refToken, b.refToken) // alice presses; her token sits in slot "b"
        result.shouldBeInstanceOf<ActionResult.Gone>()
        // Names bob, whose request is the gone one — proving the message is built from
        // `mine`/`theirs` as re-derived for the presser, not from which slot each token sits
        // in. A naive `mine = a` would have told alice her OWN request was the gone one.
        result.text shouldBe "@bob's request isn't waiting anymore."
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.OPEN
    }

    "reopen revives your most recent closure with a fresh clock" {
        val (svc, repo) = lifecycle("reopen")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId)
        svc.reopen(-100L, 1L, 7).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "reopen with nothing closed says so" {
        val (svc, _) = lifecycle("reopennothing")
        svc.reopen(-100L, 1L, 7).shouldBeInstanceOf<ActionResult.Gone>()
    }
    "reopen by token revives the request named, not merely the most recent closure" {
        val (svc, repo) = lifecycle("reopentoken")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 1L, "bobtoo", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId)
        svc.cancel(-100L, 1L, b.shortId)
        svc.reopen(-100L, 1L, 7, a.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "reopen refuses a token for a request the caller doesn't own" {
        val (svc, repo) = lifecycle("reopenforged")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId)
        svc.reopen(-100L, 2L, 7, a.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.CANCELLED
    }

    // --- An interest is a set of rows sharing an interest token: the bot-side row plus one
    // showing per chat. Done and cancel are decisions about the whole thing.

    "cancelling one showing withdraws the whole interest" {
        val (svc, repo) = lifecycle("cancelinterest")
        val noChat = repo.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val here = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val there = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val r = svc.cancel(-100L, 1L, here.shortId)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.touchedTokens.toSet() shouldBe setOf(noChat.refToken, here.refToken, there.refToken)
        repo.byRefToken(there.refToken)!!.state shouldBe RequestState.CANCELLED
        repo.byRefToken(noChat.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "a done closes both interests whole" {
        val (svc, repo) = lifecycle("doneinterest")
        val mineHere = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val mineThere = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val theirsThere = repo.create(-200L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        svc.done(1L, mineHere.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Asked>()
        svc.confirm(2L, mineHere.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        // The two sides are separate interests: closing the presser's does not close the peer's.
        mineHere.interestToken shouldBe "i1"
        theirs.interestToken shouldBe "i2"
        repo.byRefToken(mineThere.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(theirsThere.refToken)!!.state shouldBe RequestState.DONE
    }
    "a request typed in a chat still closes alone" {
        val (svc, repo) = lifecycle("loneclose")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-200L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.OPEN
    }
    "both sides of a confirmation close in one transaction, or neither does" {
        val clock = BreakableClock(T0)
        val (svc, repo) = lifecycle("doneatomic", clock)
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        // The confirmation is where both interests close now, so that is where the seam is.
        // One read through: the confirmer's interest closes, the declarer's blows up.
        clock.breakAfter(1)
        shouldThrow<IllegalStateException> { svc.confirm(2L, mine.refToken, theirs.refToken) }
        clock.mend()
        repo.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }
    "reopen brings back the siblings it closed" {
        val (svc, repo) = lifecycle("reopeninterestsvc")
        val here = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val there = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        svc.cancel(-100L, 1L, here.shortId)
        svc.reopen(-100L, 1L, 7).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(here.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(there.refToken)!!.state shouldBe RequestState.OPEN
    }
    "reopen reports what it revived, so the messages that carried it can be rewritten too" {
        val (svc, repo) = lifecycle("reopentouched")
        val here = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val there = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        svc.cancel(-100L, 1L, here.shortId)
        val r = svc.reopen(-100L, 1L, 7)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.touchedTokens.toSet() shouldBe setOf(here.refToken, there.refToken)
        r.text shouldContain "Resting again"
    }
    "a second press of reopen says it is already waiting" {
        val (svc, repo) = lifecycle("reopentwice")
        val here = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        svc.cancel(-100L, 1L, here.shortId)
        svc.reopen(-100L, 1L, 7, here.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        svc.reopen(-100L, 1L, 7, here.refToken).shouldBeInstanceOf<ActionResult.Gone>()
    }
    "a done that leaves the confirmer a residual offers it back to them" {
        val (svc, repo) = lifecycle("residualoffered")
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7)
        // Ann declares it, bob answers — so the residual on the Ok is bob's, the answerer's.
        svc.done(2L, theirs.refToken, mine.refToken).shouldBeInstanceOf<ActionResult.Asked>()
        val r = svc.confirm(1L, theirs.refToken, mine.refToken)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        val offer = r.restate.shouldNotBeNull()
        offer.amount shouldBe BigDecimal("400")
        offer.currency shouldBe "EUR"
        offer.myToken shouldBe mine.refToken
        offer.peerToken shouldBe theirs.refToken
        // The original is untouched: the residual is a new thing the person states.
        repo.byRefToken(mine.refToken)!!.statedAmount shouldBe BigDecimal("1000")
    }
    "the smaller side is offered nothing" {
        val (svc, repo) = lifecycle("noresidual")
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("600"), EURRUB, 7)
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        svc.done(2L, theirs.refToken, mine.refToken).shouldBeInstanceOf<ActionResult.Asked>()
        val r = svc.confirm(1L, theirs.refToken, mine.refToken)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.restate shouldBe null
    }
    "a done with nobody on the other side closes nothing at all" {
        val (svc, repo) = lifecycle("nopeernoresidual")
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("600"), EURRUB, 7)
        // A done is a statement about a swap, and a swap has a counterparty. With nobody
        // else here to have swapped with, this resolves to a refusal that points at
        // /cancel instead of closing the caller's own request alone.
        val r = svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Nobody)
        r.shouldBeInstanceOf<ActionResult.Gone>()
        r.text shouldContain "/cancel ${mine.shortId}"
        repo.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
    }

    // --- A done asks, and closes nothing until the counterparty answers.

    "a done asks the counterparty and closes nothing" {
        val f = AskFixture("ask_nothing_closes")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.done(1L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Asked>()
        r.peerUserId shouldBe 2L
        r.myToken shouldBe mine.refToken
        r.peerToken shouldBe theirs.refToken
        r.question shouldContain "@bob"
        r.question shouldContain "1,000 EUR"
        r.text shouldContain "Nothing's closed yet"
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    "the button and the typed form reach the same ask, and the log says which" {
        val f = AskFixture("ask_button_equals_typed")
        val mine = f.rest(NO_CHAT_ID, 1L, "bob", Side.OFFER, "i1")
        f.rest(NO_CHAT_ID, 2L, "ann", Side.BID, "i2")
        val theirs = f.requests.resting(NO_CHAT_ID).single { it.userId == 2L }
        val button = f.svc.done(1L, mine.refToken, theirs.refToken)
        val typed = f.svc.doneByShortId(NO_CHAT_ID, 1L, mine.shortId, NamedPeer.Somebody(2L))
        button.shouldBeInstanceOf<ActionResult.Asked>()
        typed.shouldBeInstanceOf<ActionResult.Asked>()
        button shouldBe typed
        // A name nothing here belongs to is still the one refusal, and the log says so.
        val unplaceable = f.svc.doneByShortId(NO_CHAT_ID, 1L, mine.shortId, NamedPeer.Unplaceable)
        unplaceable.shouldBeInstanceOf<ActionResult.Denied>()
        unplaceable.outcomeLabel() shouldBe "denied"
        button.outcomeLabel() shouldBe "asked"
    }

    "the ask goes to the chat somebody typed in, and privately to somebody who did not" {
        val f = AskFixture("ask_delivery")
        val typed = f.rest(-100L, 1L, "bob", Side.OFFER)
        val showing = f.rest(-100L, 2L, "ann", Side.BID, interest = "i2")
        val toShowing = f.svc.done(1L, typed.refToken, showing.refToken)
        toShowing.shouldBeInstanceOf<ActionResult.Asked>()
        toShowing.peerChatId shouldBe 2L
        val toTyped = f.svc.done(2L, showing.refToken, typed.refToken)
        toTyped.shouldBeInstanceOf<ActionResult.Asked>()
        toTyped.peerChatId shouldBe -100L
    }

    "only the counterparty's Yes closes anything" {
        val f = AskFixture("confirm_authorized")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.confirm(3L, mine.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        val ok = f.svc.confirm(2L, mine.refToken, theirs.refToken)
        ok.shouldBeInstanceOf<ActionResult.Ok>()
        ok.touchedTokens shouldContainExactlyInAnyOrder listOf(mine.refToken, theirs.refToken)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.DONE
        ok.text shouldContain "@bob"
        ok.text shouldContain "@ann"
        ok.notify.shouldNotBeNull().chatId shouldBe -100L
        ok.notify.reopenToken shouldBe mine.refToken
    }

    "a Yes about a request that has already closed refuses, and names which side" {
        val f = AskFixture("confirm_gone")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val r = f.svc.confirm(2L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Gone>()
        r.text shouldContain "@bob"
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    "a Yes from somebody whose own request has already closed refuses" {
        val f = AskFixture("confirm_mine_gone")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        // Ann withdrew after bob declared it; his is still waiting.
        f.requests.transition(theirs.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val r = f.svc.confirm(2L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Gone>()
        r.text shouldBe "Your own request is already closed."
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
    }

    "a No from somebody who owns neither request writes nothing" {
        val f = AskFixture("refuse_stranger")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        // The counter is what gates asking, so a third party who harvested both tokens must
        // not be able to poison it and lock a legitimate declarer out.
        val r = f.svc.refuse(3L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Denied>()
        r.text shouldBe "That isn't a question I asked you."
        f.refusals.count(mine.refToken, theirs.refToken) shouldBe 0
    }

    "a No closes nothing and leaves both resting" {
        val f = AskFixture("refuse_nothing")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.refuse(2L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.touchedTokens.shouldBeEmpty()
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
        f.refusals.count(mine.refToken, theirs.refToken) shouldBe 1
    }

    "a second No blocks a third ask, and the counterparty is not asked again" {
        val f = AskFixture("refuse_twice")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        val blocked = f.svc.done(1L, mine.refToken, theirs.refToken)
        blocked.shouldBeInstanceOf<ActionResult.Denied>()
        blocked.text shouldContain "twice"
    }

    "the block sits on whoever kept asking, not on the person refusing" {
        val f = AskFixture("refuse_one_way")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        f.svc.done(2L, theirs.refToken, mine.refToken).shouldBeInstanceOf<ActionResult.Asked>()
    }

    "a forged Yes claiming somebody else's request as the presser's own closes nothing, but one naming their own is an ordinary done" {
        val f = AskFixture("confirm_forged")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val bystander = f.rest(-100L, 3L, "cat", Side.BID)
        f.svc.confirm(3L, mine.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        f.svc.confirm(3L, mine.refToken, bystander.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    "the same-chat force close now only asks its victim" {
        val f = AskFixture("force_close_asks")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val uninvolved = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.done(1L, mine.refToken, uninvolved.refToken)
        r.shouldBeInstanceOf<ActionResult.Asked>()
        f.requests.byRefToken(uninvolved.refToken)!!.state shouldBe RequestState.OPEN
    }

    // --- Task 6: /done with no handle typed resolves whoever the bot would have
    // suggested — one of them asked, several of them offered as a choice, none of them
    // pointed at /cancel.

    "one counterparty and no handle typed: they are asked" {
        val f = AskFixture("done_sole")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Nobody)
        r.shouldBeInstanceOf<ActionResult.Asked>()
        r.peerToken shouldBe theirs.refToken
    }

    "several counterparties and no handle typed: nothing is asked and nothing closes" {
        val f = AskFixture("done_several")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val one = f.rest(-100L, 2L, "ann", Side.BID)
        val two = f.rest(-100L, 3L, "cat", Side.BID)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Nobody)
        r.shouldBeInstanceOf<ActionResult.Choose>()
        r.mineToken shouldBe mine.refToken
        r.candidates.map { it.refToken } shouldContainExactlyInAnyOrder listOf(one.refToken, two.refToken)
        f.requests.byRefToken(one.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(two.refToken)!!.state shouldBe RequestState.OPEN
    }

    "no counterparty at all: the refusal points at cancel" {
        val f = AskFixture("done_none")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Nobody)
        r.shouldBeInstanceOf<ActionResult.Gone>()
        r.text shouldContain "/cancel ${mine.shortId}"
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
    }

    "a named counterparty still resolves" {
        val f = AskFixture("done_named")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.rest(-100L, 3L, "cat", Side.BID)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Somebody(2L))
        r.shouldBeInstanceOf<ActionResult.Asked>()
        r.peerToken shouldBe theirs.refToken
    }

    "a name nothing here belongs to refuses" {
        val f = AskFixture("done_unplaceable")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Unplaceable)
            .shouldBeInstanceOf<ActionResult.Denied>()
    }
})
