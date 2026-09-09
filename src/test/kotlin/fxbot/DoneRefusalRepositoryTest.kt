package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private class RefusalFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val refusals = DoneRefusalRepository(ds)

    fun rest(userId: Long, side: Side) =
        requests.create(NO_CHAT_ID, userId, null, side, "EUR", BigDecimal("1000"), EURRUB, 7, "i$userId")
}

class DoneRefusalRepositoryTest : StringSpec({
    "an unrecorded pairing has no refusals" {
        val f = RefusalFixture("refusal_none")
        f.refusals.count("a", "b") shouldBe 0
    }

    "each refusal counts once and the count is returned" {
        val f = RefusalFixture("refusal_count")
        f.refusals.record("a", "b") shouldBe 1
        f.refusals.record("a", "b") shouldBe 2
        f.refusals.count("a", "b") shouldBe 2
    }

    "the count is one-directional" {
        val f = RefusalFixture("refusal_direction")
        f.refusals.record("a", "b")
        f.refusals.record("a", "b")
        f.refusals.count("b", "a") shouldBe 0
    }

    "a row dies when either request stops resting" {
        val f = RefusalFixture("refusal_dropclosed")
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.refusals.record(a.refToken, b.refToken)
        f.refusals.dropClosed() shouldBe 0
        f.requests.transition(b.refToken, RequestState.OPEN, RequestState.CANCELLED) shouldBe true
        f.refusals.dropClosed() shouldBe 1
        f.refusals.count(a.refToken, b.refToken) shouldBe 0
    }

    "forgetting a person's tokens erases their rows in both directions" {
        val f = RefusalFixture("refusal_forget")
        f.refusals.record("mine", "theirs")
        f.refusals.record("theirs", "mine")
        f.refusals.deleteFor(listOf("mine")) shouldBe 2
        f.refusals.count("mine", "theirs") shouldBe 0
        f.refusals.count("theirs", "mine") shouldBe 0
    }

    "forgetting nothing deletes nothing" {
        val f = RefusalFixture("refusal_forget_empty")
        f.refusals.record("a", "b")
        f.refusals.deleteFor(emptyList()) shouldBe 0
        f.refusals.count("a", "b") shouldBe 1
    }
})
