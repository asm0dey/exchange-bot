package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val EURRUB = CurrencyPair("EUR", "RUB")

class SideTest : StringSpec({
    // The spec's side table. Offer gives the base currency; Bid receives it.
    "sell base is an offer" { sideFor(Verb.SELL, "EUR", EURRUB) shouldBe Side.OFFER }
    "buy quote is an offer" { sideFor(Verb.BUY, "RUB", EURRUB) shouldBe Side.OFFER }
    "sell quote is a bid" { sideFor(Verb.SELL, "RUB", EURRUB) shouldBe Side.BID }
    "buy base is a bid" { sideFor(Verb.BUY, "EUR", EURRUB) shouldBe Side.BID }

    "the probe cases from the spec land on opposite sides" {
        sideFor(Verb.SELL, "EUR", EURRUB) shouldBe Side.OFFER
        sideFor(Verb.BUY, "EUR", EURRUB) shouldBe Side.BID
        sideFor(Verb.BUY, "RUB", EURRUB) shouldBe Side.OFFER
        sideFor(Verb.SELL, "RUB", EURRUB) shouldBe Side.BID
    }
    "sell base and buy quote are the SAME side and must not match" {
        sideFor(Verb.SELL, "EUR", EURRUB) shouldBe sideFor(Verb.BUY, "RUB", EURRUB)
    }

    "pair membership and the other leg" {
        EURRUB.contains("EUR") shouldBe true
        EURRUB.contains("USD") shouldBe false
        EURRUB.other("EUR") shouldBe "RUB"
        EURRUB.other("RUB") shouldBe "EUR"
    }

    "currency codes are upper-cased and validated against ISO 4217" {
        parseCurrency("eur") shouldBe "EUR"
        parseCurrency(" RUB ") shouldBe "RUB"
        parseCurrency("XYZ").shouldBeNull()
        parseCurrency("euro").shouldBeNull()
    }
})
