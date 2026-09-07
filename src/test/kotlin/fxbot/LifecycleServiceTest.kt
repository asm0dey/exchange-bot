package fxbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    return LifecycleService(repo, settings, deadRateService(ds, Clock.fixed(T0, ZoneOffset.UTC))) to repo
}

private fun RequestRepository.put(chatId: Long, userId: Long, name: String, side: Side) =
    create(chatId, userId, name, side, "EUR", BigDecimal("1000"), EURRUB, 7)

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

    "done closes both sides" {
        val (svc, repo) = lifecycle("done")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
    }
    "either counterparty may press done" {
        val (svc, repo) = lifecycle("doneeither")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(2L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Ok>()
    }
    "a third party may not" {
        val (svc, repo) = lifecycle("donethird")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(3L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "a second done reports it was already closed" {
        val (svc, repo) = lifecycle("donetwice")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken)
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
    "closing with a counterparty who has already gone still closes your own" {
        val (svc, repo) = lifecycle("peergone")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.cancel(-100L, 2L, b.shortId)
        val result = svc.done(1L, a.refToken, b.refToken)
        result.shouldBeInstanceOf<ActionResult.Ok>()
        // Names both sides — R45's mitigation for the accepted force-close residual is that
        // the announcement names both people, so the wronged party can tell and /reopen.
        result.text shouldBe "Closed only @bob's — @alice's was already closed."
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
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
    "a legitimate pair pressed by the second counterparty still closes both, with the message phrased for the presser" {
        val (svc, repo) = lifecycle("secondpresser")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        val result = svc.done(2L, a.refToken, b.refToken)
        result.shouldBeInstanceOf<ActionResult.Ok>()
        // Names both owners — see the R42/R45 discussion this fix addresses: a bare
        // "Marked done" leaves a griefed party with no way to tell they were named.
        result.text shouldBe "Marked done: @alice and @bob. If that's wrong, /reopen."
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
    }
    "the outcome message reflects the presser's own request even when it sits in the button's second slot" {
        val (svc, repo) = lifecycle("reversedpeergone")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.cancel(-100L, 1L, a.shortId) // bob's request — payload slot "a" — closes first
        val result = svc.done(2L, a.refToken, b.refToken) // alice presses; her token sits in slot "b"
        result.shouldBeInstanceOf<ActionResult.Ok>()
        // "only [mine]'s" leads, naming the presser's own (alice's) request first — proving
        // the message is built from `mine`/`theirs` as re-derived for the presser, not from
        // the button's "a"/"b" argument order.
        result.text shouldBe "Closed only @alice's — @bob's was already closed."
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
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

    // --- An interest is a set of rows sharing an interest token: the no-names row plus one
    // showing per chat. Done and cancel are decisions about the whole thing.

    "cancelling one showing withdraws the whole interest" {
        val (svc, repo) = lifecycle("cancelinterest")
        val noNames = repo.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val here = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val there = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val r = svc.cancel(-100L, 1L, here.shortId)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.touchedTokens.toSet() shouldBe setOf(noNames.refToken, here.refToken, there.refToken)
        repo.byRefToken(there.refToken)!!.state shouldBe RequestState.CANCELLED
        repo.byRefToken(noNames.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "a done closes both interests whole" {
        val (svc, repo) = lifecycle("doneinterest")
        val mineHere = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val mineThere = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val theirsThere = repo.create(-200L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        svc.done(1L, mineHere.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Ok>()
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
    "both sides of a done close in one transaction, or neither does" {
        val clock = BreakableClock(T0)
        val (svc, repo) = lifecycle("doneatomic", clock)
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        // One read through: the presser's interest closes, the counterparty's blows up.
        clock.breakAfter(1)
        shouldThrow<IllegalStateException> { svc.done(1L, mine.refToken, theirs.refToken) }
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
    "a done that leaves the presser a residual offers it back to them" {
        val (svc, repo) = lifecycle("residualoffered")
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7)
        val r = svc.done(1L, mine.refToken, theirs.refToken)
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
        val r = svc.done(1L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.restate shouldBe null
    }
    "a done with nobody on the other side offers nothing to restate" {
        val (svc, repo) = lifecycle("nopeernoresidual")
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("600"), EURRUB, 7)
        val r = svc.done(1L, mine.refToken, null)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.restate shouldBe null
    }
})
