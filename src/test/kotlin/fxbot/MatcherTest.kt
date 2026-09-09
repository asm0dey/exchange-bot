package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")
private val RATE = BigDecimal("99.98")

private var seq = 0L
private fun req(
    verb: Verb,
    amount: String,
    ccy: String,
    userId: Long = ++seq,
    chatId: Long = -100L,
    state: RequestState = RequestState.OPEN,
    pair: CurrencyPair = EURRUB,
): Request {
    // Bumped once here (regardless of whether `userId` also bumped it via its default),
    // so refToken/shortId are still unique per call the way they were when this used to
    // ride on the now-deleted `rowId = ++seq` field assignment.
    val n = ++seq
    return Request(
        refToken = "tok${n}".padEnd(22, 'x'),
        chatId = chatId,
        userId = userId,
        username = "u$userId",
        shortId = "a${n % 10}",
        side = sideFor(verb, ccy, pair),
        statedCurrency = ccy,
        statedAmount = BigDecimal(amount),
        pair = pair,
        state = state,
        createdAt = Instant.EPOCH,
        expiresAt = Instant.EPOCH.plusSeconds(604_800),
    )
}

class MatcherTest : StringSpec({
    "notional passes a base amount straight through" {
        notional(req(Verb.SELL, "1000", "EUR"), RATE) shouldBe BigDecimal("1000")
    }
    "notional divides a quote amount by the rate" {
        val n = notional(req(Verb.SELL, "95000", "RUB"), RATE)!!
        n.setScale(2, java.math.RoundingMode.HALF_UP) shouldBe BigDecimal("950.19")
    }
    "notional of a quote amount is unknown without a rate" {
        notional(req(Verb.SELL, "95000", "RUB"), null) shouldBe null
    }

    // The spec's four worked cases.
    "sell 999 EUR matches buy 1000 EUR" {
        val subject = req(Verb.SELL, "999", "EUR")
        findCounterparties(subject, listOf(req(Verb.BUY, "1000", "EUR")), RATE, 20) shouldHaveSize 1
    }
    "buy 100 RUB matches buy 1 EUR" {
        val subject = req(Verb.BUY, "100", "RUB")
        findCounterparties(subject, listOf(req(Verb.BUY, "1", "EUR")), RATE, 20) shouldHaveSize 1
    }
    "buy 20 RUB matches sell 20 RUB" {
        val subject = req(Verb.BUY, "20", "RUB")
        findCounterparties(subject, listOf(req(Verb.SELL, "20", "RUB")), RATE, 20) shouldHaveSize 1
    }
    "sell 1000 EUR does NOT match buy 95000 RUB — same side" {
        val subject = req(Verb.SELL, "1000", "EUR")
        findCounterparties(subject, listOf(req(Verb.BUY, "95000", "RUB")), RATE, 20).shouldBeEmpty()
    }

    "the size tolerance boundary is inclusive" {
        val subject = req(Verb.SELL, "1000", "EUR")
        // 800 is exactly 20% below 1000 when measured against the larger.
        findCounterparties(subject, listOf(req(Verb.BUY, "800", "EUR")), RATE, 20) shouldHaveSize 1
        findCounterparties(subject, listOf(req(Verb.BUY, "799", "EUR")), RATE, 20).shouldBeEmpty()
    }

    "never matches the same person with themselves" {
        val subject = req(Verb.SELL, "1000", "EUR", userId = 7)
        findCounterparties(subject, listOf(req(Verb.BUY, "1000", "EUR", userId = 7)), RATE, 20).shouldBeEmpty()
    }
    "never matches another chat" {
        val subject = req(Verb.SELL, "1000", "EUR", chatId = -100)
        findCounterparties(subject, listOf(req(Verb.BUY, "1000", "EUR", chatId = -200)), RATE, 20).shouldBeEmpty()
    }
    "never matches a request on another pair" {
        val subject = req(Verb.SELL, "1000", "EUR")
        val other = req(Verb.BUY, "1000", "EUR", pair = CurrencyPair("EUR", "USD"))
        findCounterparties(subject, listOf(other), RATE, 20).shouldBeEmpty()
    }
    "never matches a request that is not resting" {
        val subject = req(Verb.SELL, "1000", "EUR")
        val closed = req(Verb.BUY, "1000", "EUR", state = RequestState.DONE)
        findCounterparties(subject, listOf(closed), RATE, 20).shouldBeEmpty()
    }

    "closest size first, capped at five" {
        val subject = req(Verb.SELL, "1000", "EUR")
        val resting = listOf("1100", "1010", "900", "1050", "950", "1001").map { req(Verb.BUY, it, "EUR") }
        val found = findCounterparties(subject, resting, RATE, 20)
        found shouldHaveSize 5
        // The whole ordering, and which one got dropped: 900 is the farthest away.
        found.map { it.request.statedAmount } shouldBe
            listOf("1001", "1010", "1050", "950", "1100").map { BigDecimal(it) }
    }

    "without a rate, same-denomination requests still match" {
        val subject = req(Verb.SELL, "95000", "RUB")
        findCounterparties(subject, listOf(req(Verb.BUY, "90000", "RUB")), null, 20) shouldHaveSize 1
    }
    "without a rate, cross-denomination requests do not match" {
        val subject = req(Verb.SELL, "1000", "EUR")
        findCounterparties(subject, listOf(req(Verb.SELL, "95000", "RUB")), null, 20).shouldBeEmpty()
    }

    "a subject that is no longer resting matches nobody" {
        val subject = req(Verb.SELL, "1000", "EUR", state = RequestState.CANCELLED)
        findCounterparties(subject, listOf(req(Verb.BUY, "1000", "EUR")), RATE, 20).shouldBeEmpty()
    }

    "a non-positive rate is treated as no rate at all" {
        val r = req(Verb.SELL, "95000", "RUB")
        notional(r, BigDecimal.ZERO) shouldBe null
        notional(r, BigDecimal("-99.98")) shouldBe null
    }

    // --- ADR 0006: each side judges its own residual against its own tolerance.

    "a smaller counterparty is a counterparty when the larger side accepts the leftover" {
        // A sells 4 EUR with a 50% tolerance; B buys 2. A's residual is 2 of 4 = 50%.
        val a = req(Verb.SELL, "4", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "2", "EUR")), RATE, 50) shouldHaveSize 1
    }
    "the larger side's own tolerance can exclude a pairing the smaller side accepts" {
        // Same shapes, A's tolerance is 20: 50% > 20, so A refuses even though B has no residual.
        val a = req(Verb.SELL, "4", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "2", "EUR")), RATE, 20).shouldBeEmpty()
    }
    "the smaller side has no residual of its own, so it accepts however tight its tolerance is" {
        // B is the subject now, with a tolerance of 1: B's residual is 0, A's is not B's business.
        val b = req(Verb.BUY, "2", "EUR")
        findCounterparties(b, listOf(req(Verb.SELL, "4", "EUR")), RATE, 1, peerTolerancePct = { 50 })
            .shouldHaveSize(1)
    }
    "the peer's own tolerance is consulted, not the subject's" {
        // The subject is small and generous; the peer is large and strict, so the peer refuses.
        val b = req(Verb.BUY, "2", "EUR")
        findCounterparties(b, listOf(req(Verb.SELL, "4", "EUR")), RATE, 100, peerTolerancePct = { 20 })
            .shouldBeEmpty()
    }
    "a residual exactly equal to the tolerance is accepted" {
        val a = req(Verb.SELL, "1000", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "800", "EUR")), RATE, 20) shouldHaveSize 1
        findCounterparties(a, listOf(req(Verb.BUY, "799", "EUR")), RATE, 20).shouldBeEmpty()
    }
    "a near-equal pairing leaves a residual well inside a 20 percent tolerance" {
        val a = req(Verb.SELL, "1000", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "999", "EUR")), RATE, 20) shouldHaveSize 1
    }
})
