package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private class GiveUpFixture(name: String, private val handles: Map<Long, Handle>) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val giveUps = NameGiveUpRepository(ds, crypto)
    var lookups = 0
    val svc = GiveUpService(requests, giveUps, NameLookup { id -> lookups++; handles[id] })

    fun rest(userId: Long, side: Side) =
        requests.create(NO_NAMES_CHAT_ID, userId, null, side, "EUR", BigDecimal("1000"), EURRUB, 7, "i$userId")

    /** Like [rest], but with the stored `username` populated — private interests carry one (InterestService). */
    fun restNamed(userId: Long, side: Side, username: String) =
        requests.create(NO_NAMES_CHAT_ID, userId, username, side, "EUR", BigDecimal("1000"), EURRUB, 7, "i$userId")
}

private fun handled(vararg pairs: Pair<Long, Handle>) = pairs.toMap()

class GiveUpServiceTest : StringSpec({
    "one side's offer discloses nothing and asks the other" {
        val f = GiveUpFixture("askpeer", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        // The negative case: a single-sided offer must be Asked, never Disclosed — the
        // sealed type itself is the proof that nothing was handed over yet.
        r.shouldBeInstanceOf<GiveUpResult.Asked>()
        r.peerUserId shouldBe 2L
        r.peerRefToken shouldBe b.refToken
        r.myRefToken shouldBe a.refToken
        r.mine.refToken shouldBe a.refToken
        r.theirs.refToken shouldBe b.refToken
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED
        f.giveUps.bothOffered(a.refToken, b.refToken) shouldBe false
    }

    "Asked never carries either side's stored username" {
        // Both requests carry a real, stored username (as a private interest's do —
        // InterestService populates it). Asked's recipient (the peer, who has not
        // consented) must not be able to read it off `mine` or `theirs` via the
        // ordinary mentionOf(r)/describe(r) idiom used everywhere else in this codebase.
        val f = GiveUpFixture("askedstripped", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.restNamed(1L, Side.OFFER, "bob")
        val b = f.restNamed(2L, Side.BID, "ann")
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Asked>()
        r.mine.username shouldBe null
        r.theirs.username shouldBe null
        // Everything else about the request is left alone — only the name is stripped.
        r.mine.refToken shouldBe a.refToken
        r.theirs.refToken shouldBe b.refToken
    }

    "a self-pairing offer is refused before any lookup, and writes nothing" {
        val f = GiveUpFixture("selfpair", handled(1L to Handle("bob", "Bob")))
        val a = f.rest(1L, Side.OFFER)
        val r = f.svc.offer(1L, a.refToken, a.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        f.giveUps.stanceOf(a.refToken, a.refToken) shouldBe null
        f.lookups shouldBe 0
    }

    "both offers disclose each side's handle to the other, once" {
        val f = GiveUpFixture("both", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken)
        val r = f.svc.offer(2L, b.refToken, a.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Disclosed>()
        setOf(r.a.userId, r.b.userId) shouldBe setOf(1L, 2L)
        // Not just "the aggregate contains both handles" — the RIGHT handle must be
        // attached to the RIGHT person's own request token, or two people could be
        // handed each other's names crossed.
        val bobParty = if (r.a.userId == 1L) r.a else r.b
        val annParty = if (r.a.userId == 2L) r.a else r.b
        bobParty.refToken shouldBe a.refToken
        bobParty.handle shouldContain "@bob"
        annParty.refToken shouldBe b.refToken
        annParty.handle shouldContain "@ann"
    }

    "names are looked up live at give-up time, never stored" {
        val f = GiveUpFixture("livelookup", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken)
        f.svc.offer(2L, b.refToken, a.refToken)
        // 4, not 6: `introducible`'s `||` short-circuits once the FIRST handleFor call
        // finds a username, and both fixture users have one — so each of the two offer
        // presses spends exactly 1 lookup on `introducible`, and the disclosure branch
        // spends the other 2 reading both handles again. Do not "fix" this back to 6;
        // that would describe a build without short-circuiting, which this one is not.
        f.lookups shouldBe 4
        // Nothing about a name is in the consent table.
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED
    }

    "neither side having a handle is refused before anyone consents" {
        val f = GiveUpFixture("nohandle", handled(1L to Handle(null, "Bob"), 2L to Handle(null, "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        r.text shouldContain "@username"
        r.text shouldNotContain "Bob"
        r.text shouldNotContain "Ann"
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe null
    }

    "one handle between the two is enough" {
        val f = GiveUpFixture("onehandle", handled(1L to Handle(null, "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken).shouldBeInstanceOf<GiveUpResult.Asked>()
    }

    "a handle that vanishes before disclosure blocks the name, though consent stands" {
        // The fixture map has no entry at all for user 2 — distinct from Handle(null, "Ann")
        // above: this models a lookup that fails outright (e.g. getChat errors, the account
        // is gone), not merely a user without a username. A stub that always answers with
        // SOME Handle could never show this path.
        val f = GiveUpFixture("vanished", handled(1L to Handle("bob", "Bob")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken).shouldBeInstanceOf<GiveUpResult.Asked>()
        val r = f.svc.offer(2L, b.refToken, a.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        r.text shouldNotContain "Bob"
        // Both sides genuinely consented — the failure is the lookup, not the consent.
        f.giveUps.bothOffered(a.refToken, b.refToken) shouldBe true
    }

    "a forged payload claiming the peer agreed discloses nothing" {
        val f = GiveUpFixture("forged", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        // Person 1 presses, but hands in the PEER's token as their own.
        f.svc.offer(1L, b.refToken, a.refToken).shouldBeInstanceOf<GiveUpResult.Refused>()
        f.giveUps.stanceOf(b.refToken, a.refToken) shouldBe null
        // And pressing legitimately still only records one side.
        f.svc.offer(1L, a.refToken, b.refToken).shouldBeInstanceOf<GiveUpResult.Asked>()
    }

    "a forged decline payload writes nothing and discloses nothing" {
        val f = GiveUpFixture("forgeddecline", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        // Person 1 presses decline, but hands in the PEER's token as their own.
        f.svc.decline(1L, b.refToken, a.refToken).shouldBeInstanceOf<GiveUpResult.Refused>()
        f.giveUps.stanceOf(b.refToken, a.refToken) shouldBe null
        f.giveUps.declined(a.refToken, b.refToken) shouldBe false
    }

    "decline refuses without writing when the peer request does not exist" {
        val f = GiveUpFixture("declinenopeer", handled(1L to Handle("bob", "Bob")))
        val a = f.rest(1L, Side.OFFER)
        val r = f.svc.decline(1L, a.refToken, "no-such-token")
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        f.giveUps.stanceOf(a.refToken, "no-such-token") shouldBe null
    }

    "decline refuses without writing when the presser's own token does not exist" {
        val f = GiveUpFixture("declinenomine", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val b = f.rest(2L, Side.BID)
        val r = f.svc.decline(1L, "no-such-token", b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        f.giveUps.stanceOf("no-such-token", b.refToken) shouldBe null
    }

    "a decline suppresses the pairing for both directions and names nobody" {
        val f = GiveUpFixture("decline", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        val r = f.svc.decline(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Recorded>()
        r.text shouldNotContain "Ann"
        // Symmetry, exercised in BOTH argument orders — not just the order the row
        // happened to be written in.
        f.giveUps.declined(a.refToken, b.refToken) shouldBe true
        f.giveUps.declined(b.refToken, a.refToken) shouldBe true
        // The peer, offering in their own order, is refused and told nothing.
        val peerAttempt = f.svc.offer(2L, b.refToken, a.refToken)
        peerAttempt.shouldBeInstanceOf<GiveUpResult.Refused>()
        peerAttempt.text shouldNotContain "Bob"
        f.giveUps.stanceOf(b.refToken, a.refToken) shouldBe null
    }

    "offering again after declining it yourself is refused with its own message" {
        val f = GiveUpFixture("selfdecline", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.decline(1L, a.refToken, b.refToken)
        val ownRetry = f.svc.offer(1L, a.refToken, b.refToken)
        ownRetry.shouldBeInstanceOf<GiveUpResult.Refused>()
        val peerRetry = f.svc.offer(2L, b.refToken, a.refToken)
        peerRetry.shouldBeInstanceOf<GiveUpResult.Refused>()
        // Distinct wording: the decliner is told about THEIR OWN action, not that the
        // peer vanished — the peer's own refusal text (still whatever the brief's
        // generic "gone" wording is) must not be reused for the decliner.
        ownRetry.text shouldNotContain "no longer waiting"
        ownRetry.text shouldNotContain "Bob"
        ownRetry.text shouldNotContain "Ann"
    }

    "an offer whose peer request has closed is refused, naming nobody, and the existing row is untouched" {
        val f = GiveUpFixture("peergone", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken)
        f.requests.closeInterest("i2", RequestState.CANCELLED)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        r.text shouldNotContain "Ann"
        r.text shouldNotContain "@ann"
        // The guard fired before any write: the earlier consent is exactly as it was.
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED
    }

    "declining, then the peer's request closing, still reports the decliner's own choice" {
        // Priority: YOU_DECLINED must win over the OPEN-state check even when the PEER'S
        // request has since closed — telling the decliner "that one is no longer waiting"
        // both misstates what happened (their own "no" is why they get nothing further)
        // and hands them a fresh fact about the peer they no longer need.
        val f = GiveUpFixture("declinedthenpeergone", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.decline(1L, a.refToken, b.refToken)
        f.requests.closeInterest("i2", RequestState.CANCELLED)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        r.text shouldNotContain "no longer waiting"
        r.text shouldNotContain "Ann"
    }

    "an offer whose OWN request has closed names what happened to it, not the peer" {
        val f = GiveUpFixture("minegone", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.requests.closeInterest("i1", RequestState.CANCELLED)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        // Distinct from PEER_GONE's wording ("that one ... nobody left to introduce you
        // to") — the request that closed was the PRESSER'S OWN, and the message must say
        // so rather than reusing the peer-shaped sentence for the wrong person.
        r.text shouldNotContain "nobody left to introduce you to"
        r.text shouldNotContain "Ann"
        r.text shouldNotContain "@ann"
    }

    "a decline dies with the requests" {
        val f = GiveUpFixture("declinedies", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.decline(1L, a.refToken, b.refToken)
        f.requests.closeInterest("i2", RequestState.DONE)
        f.giveUps.dropClosed()
        f.giveUps.declined(a.refToken, b.refToken) shouldBe false
    }
})
