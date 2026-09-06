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
        val a = requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        val b = requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        g.record(a.refToken, b.refToken, 1L, Stance.OFFERED)
        g.record("gone-1", "gone-2", 3L, Stance.OFFERED) // neither request exists at all
        g.dropClosed() shouldBe 1
        g.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED

        requests.closeInterest("i2", RequestState.DONE)
        g.dropClosed() shouldBe 1
        g.stanceOf(a.refToken, b.refToken) shouldBe null
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
