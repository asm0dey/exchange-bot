package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.math.BigDecimal
import java.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")

private fun r(verb: Verb, amount: String, ccy: String, user: Long, name: String?, token: String = "t".repeat(22)) =
    Request(
        refToken = token, chatId = -100L, userId = user, username = name,
        shortId = "a$user", side = sideFor(verb, ccy, EURRUB), statedCurrency = ccy,
        statedAmount = BigDecimal(amount), pair = EURRUB, state = RequestState.OPEN,
        createdAt = Instant.EPOCH, expiresAt = Instant.EPOCH,
    )

class RenderTest : StringSpec({
    "the give currency follows the side" {
        Side.OFFER.giveCurrency(EURRUB) shouldBe "EUR"
        Side.BID.giveCurrency(EURRUB) shouldBe "RUB"
    }
    "the verb is reconstructed from the side and the stated currency" {
        verbFor(Side.OFFER, "EUR", EURRUB) shouldBe Verb.SELL
        verbFor(Side.OFFER, "RUB", EURRUB) shouldBe Verb.BUY
        verbFor(Side.BID, "RUB", EURRUB) shouldBe Verb.SELL
        verbFor(Side.BID, "EUR", EURRUB) shouldBe Verb.BUY
    }
    "a request is described in the words its author used" {
        describe(r(Verb.SELL, "1000", "EUR", 1, "alice")) shouldBe "sell 1,000 EUR"
        describe(r(Verb.BUY, "900", "EUR", 2, "bob")) shouldBe "buy 900 EUR"
        describe(r(Verb.SELL, "95000", "RUB", 3, "carol")) shouldBe "sell 95,000 RUB"
    }

    "someone with a username is mentioned by it" {
        mention("alice", 42, "Alice") shouldBe "@alice"
    }
    "someone without a username gets a tg link" {
        mention(null, 42, "Alice") shouldBe """<a href="tg://user?id=42">Alice</a>"""
    }
    "display names are HTML-escaped" {
        mention(null, 42, "A<b>&") shouldContain "A&lt;b&gt;&amp;"
    }
    "usernames are HTML-escaped" {
        mention("a<b>&", 42, "Alice") shouldBe "@a&lt;b&gt;&amp;"
    }

    "callback data stays inside Telegram's 64 bytes" {
        val a = "a".repeat(22)
        val b = "b".repeat(22)
        Cb.done(a, b).toByteArray().size shouldBe 54
        (Cb.done(a, b).toByteArray().size <= 64) shouldBe true
        (Cb.cancel(a).toByteArray().size <= 64) shouldBe true
        (Cb.reopen(a).toByteArray().size <= 64) shouldBe true
    }
    "callback data uses the framework's query syntax" {
        Cb.cancel("tok") shouldBe "cancel?t=tok"
        Cb.done("x", "y") shouldBe "done?a=x&b=y"
    }

    "a suggestion names each counterparty and how to reach them" {
        val found = listOf(
            Counterparty(r(Verb.BUY, "900", "EUR", 2, "alice"), BigDecimal("900"), BigDecimal("0.1")),
            Counterparty(r(Verb.SELL, "95000", "RUB", 3, "carol"), BigDecimal("950.19"), BigDecimal("0.05")),
        )
        val text = renderSuggestions(found, RateStatus.Fresh(BigDecimal("99.98")))
        text shouldContain "@alice"
        text shouldContain "buy 900 EUR"
        text shouldContain "sell 95,000 RUB"
        text shouldContain "950.19"
        text shouldNotContain "notional"
        text shouldNotContain "Bid"
    }
    "no counterparty means a waitlist line, not an error" {
        val text = renderSuggestions(emptyList(), RateStatus.Fresh(BigDecimal("99.98")))
        text shouldContain "waitlist"
    }
    "a stale rate is admitted in the message" {
        val text = renderSuggestions(
            emptyList(),
            RateStatus.Stale(BigDecimal("95"), Instant.parse("2026-08-12T00:00:00Z")),
        )
        text shouldContain "12 Aug"
    }
    "an unavailable rate says so plainly" {
        val text = renderSuggestions(emptyList(), RateStatus.Unavailable)
        text shouldContain "can't check rates"
    }

    "one done button per counterparty, plus cancel" {
        val subject = r(Verb.SELL, "1000", "EUR", 1, "bob", token = "s".repeat(22))
        val found = listOf(Counterparty(r(Verb.BUY, "900", "EUR", 2, "alice", token = "c".repeat(22)), BigDecimal("900"), BigDecimal.ZERO))
        val buttons = suggestionButtons(subject, found)
        buttons.size shouldBe 2
        buttons[0].label shouldContain "alice"
        buttons[0].data shouldBe Cb.done("s".repeat(22), "c".repeat(22))
        buttons[1].data shouldBe Cb.cancel("s".repeat(22))
    }

    "status caps the list and says how many were left out" {
        val many = (1..25).map { r(Verb.SELL, "$it", "EUR", it.toLong(), "u$it") }
        val text = renderStatus(many, viewerId = 3, limit = 20)
        text shouldContain "+5 more"
        text shouldContain "yours"
    }
})
