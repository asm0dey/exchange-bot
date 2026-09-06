package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")
private val RATE = BigDecimal("100")

private fun req(verb: Verb, amount: String, ccy: String, chatId: Long = -100L, interest: String? = null) =
    Request(
        refToken = "t".repeat(22), chatId = chatId, userId = 1L, username = "bob", shortId = "a1",
        side = sideFor(verb, ccy, EURRUB), statedCurrency = ccy, statedAmount = BigDecimal(amount),
        pair = EURRUB, state = RequestState.DONE, createdAt = Instant.EPOCH, expiresAt = Instant.EPOCH,
        interestToken = interest,
    )

class ResidualTest : StringSpec({
    "the larger side keeps the difference, in its own stated currency" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "600", "EUR"), RATE) shouldBe BigDecimal("400")
    }
    "the smaller side has none" {
        residualOf(req(Verb.BUY, "600", "EUR"), req(Verb.SELL, "1000", "EUR"), RATE) shouldBe null
    }
    "equal sizes leave none" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "1000", "EUR"), RATE) shouldBe null
    }
    "across currencies it is expressed in the presser's own, at the reference rate" {
        // 1000 EUR against 60,000 RUB (= 600 EUR at 100) leaves 400 EUR.
        val left = residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "60000", "RUB"), RATE)!!
        left.setScale(2, RoundingMode.HALF_UP) shouldBe BigDecimal("400.00")
    }
    "a presser who stated the quote currency gets their residual back in it" {
        // 100,000 RUB (= 1000 EUR) against 600 EUR leaves 400 EUR, i.e. 40,000 RUB.
        val left = residualOf(req(Verb.SELL, "100000", "RUB"), req(Verb.BUY, "600", "EUR"), RATE)!!
        left.setScale(2, RoundingMode.HALF_UP) shouldBe BigDecimal("40000.00")
    }
    "across currencies with no rate there is nothing to state" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "60000", "RUB"), null) shouldBe null
    }
    "a non-positive rate is treated as no rate at all" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "60000", "RUB"), BigDecimal.ZERO) shouldBe null
    }
    "same-currency amounts need no rate" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "600", "EUR"), null) shouldBe BigDecimal("400")
    }

    "a residual of a privately stated interest is restated privately" {
        restateGoesPrivate(req(Verb.SELL, "1000", "EUR", chatId = NO_NAMES_CHAT_ID, interest = "i1")) shouldBe true
        restateGoesPrivate(req(Verb.SELL, "1000", "EUR", chatId = -100L, interest = "i1")) shouldBe true
    }
    "a residual of a request typed in a group stays in that group" {
        restateGoesPrivate(req(Verb.SELL, "1000", "EUR", chatId = -100L, interest = null)) shouldBe false
    }
})
