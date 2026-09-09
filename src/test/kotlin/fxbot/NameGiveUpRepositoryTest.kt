package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private fun giveUps(name: String): Pair<NameGiveUpRepository, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    return NameGiveUpRepository(ds, crypto) to RequestRepository(ds, crypto)
}

class NameGiveUpRepositoryTest : StringSpec({
    "one side's offer is recorded and does not read as both" {
        val (g, _) = giveUps("oneside")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.stanceOf("a", "b") shouldBe Stance.OFFERED
        g.stanceOf("b", "a") shouldBe null
        g.bothOffered("a", "b") shouldBe false
    }
    "both offers together mean both consented" {
        val (g, _) = giveUps("bothsides")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.record("b", "a", 2L, Stance.OFFERED)
        g.bothOffered("a", "b") shouldBe true
        g.bothOffered("b", "a") shouldBe true
    }
    "one decline suppresses the pairing for both, whichever way round it is asked" {
        val (g, _) = giveUps("decline")
        g.record("b", "a", 2L, Stance.DECLINED)
        g.declined("a", "b") shouldBe true
        g.declined("b", "a") shouldBe true
        g.bothOffered("a", "b") shouldBe false
    }
    "a second press on the same direction replaces the stance, it does not add a row" {
        val (g, _) = giveUps("restance")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.record("a", "b", 1L, Stance.DECLINED)
        g.stanceOf("a", "b") shouldBe Stance.DECLINED
        g.declined("a", "b") shouldBe true
    }
    "rows die with their requests" {
        val (g, requests) = giveUps("dropclosed")
        val a = requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        val b = requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        g.record(a.refToken, b.refToken, 1L, Stance.OFFERED)
        g.record("gone-1", "gone-2", 3L, Stance.OFFERED) // neither request exists at all
        // Nobody is named: the row whose own request is gone has nobody left to tell.
        g.dropClosed() shouldBe emptyList<Long>()
        g.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED

        requests.closeInterest("i2", RequestState.DONE)
        // The peer's showing closed while the offerer's own is still resting, so the
        // offerer is named — read out of their OWN request's sealed payload, since
        // `user_ref` is a one-way MAC and cannot be reversed.
        g.dropClosed() shouldBe listOf(1L)
        g.stanceOf(a.refToken, b.refToken) shouldBe null
    }
    "when the offerer's own showing closed too there is nobody to tell" {
        val (g, requests) = giveUps("dropclosedsilent")
        val a = requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        val b = requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        g.record(a.refToken, b.refToken, 1L, Stance.OFFERED)
        requests.closeInterest("i1", RequestState.CANCELLED)
        requests.closeInterest("i2", RequestState.DONE)
        g.dropClosed() shouldBe emptyList<Long>()
        g.stanceOf(a.refToken, b.refToken) shouldBe null
    }
    "a give-up both sides already answered is dropped without telling anybody" {
        val (g, requests) = giveUps("dropcloseddisclosed")
        val a = requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        val b = requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        // Both sides pressed, so the names were passed. Nothing deletes these rows at
        // disclosure — they live on as OFFERED until one of the requests closes.
        g.record(a.refToken, b.refToken, 1L, Stance.OFFERED)
        g.record(b.refToken, a.refToken, 2L, Stance.OFFERED)
        g.bothOffered(a.refToken, b.refToken) shouldBe true

        requests.closeInterest("i2", RequestState.DONE)

        // Person 1 is holding person 2's handle. "The other side closed theirs before
        // answering, so nothing was passed on" would be a false statement to send them.
        g.dropClosed() shouldBe emptyList<Long>()
        g.stanceOf(a.refToken, b.refToken) shouldBe null
        g.stanceOf(b.refToken, a.refToken) shouldBe null
    }
    "a decline that dies with its requests tells nobody" {
        val (g, requests) = giveUps("dropclosedeclined")
        val a = requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        val b = requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        g.record(a.refToken, b.refToken, 1L, Stance.DECLINED)
        requests.closeInterest("i2", RequestState.DONE)
        // Their own "no" is what ended it; there is nothing they are still waiting on.
        g.dropClosed() shouldBe emptyList<Long>()
        g.stanceOf(a.refToken, b.refToken) shouldBe null
    }
    "one person is told once however many of their agreements died at the same time" {
        val (g, requests) = giveUps("dropcloseddedupe")
        val a = requests.create(NO_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        val b = requests.create(NO_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        val c = requests.create(NO_CHAT_ID, 3L, "cat", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i3")
        g.record(a.refToken, b.refToken, 1L, Stance.OFFERED)
        g.record(a.refToken, c.refToken, 1L, Stance.OFFERED)
        requests.closeInterest("i2", RequestState.DONE)
        requests.closeInterest("i3", RequestState.DONE)
        g.dropClosed() shouldBe listOf(1L)
    }
    "forgetting drops the rows the person wrote" {
        val (g, _) = giveUps("giveupforget")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.record("b", "a", 2L, Stance.OFFERED)
        g.deleteFor(1L)
        g.stanceOf("a", "b") shouldBe null
        g.stanceOf("b", "a") shouldBe Stance.OFFERED
    }
})
