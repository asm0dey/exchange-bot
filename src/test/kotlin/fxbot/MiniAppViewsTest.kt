package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import java.math.BigDecimal
import java.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")
private var n = 0L
private fun r(verb: Verb, amount: String, ccy: String, userId: Long = ++n) = Request(
    refToken = "t${++n}".padEnd(22, 'x'), chatId = -100, userId = userId, username = "u$userId",
    shortId = "a$n", side = sideFor(verb, ccy, EURRUB), statedCurrency = ccy,
    statedAmount = BigDecimal(amount), pair = EURRUB, state = RequestState.OPEN,
    createdAt = Instant.EPOCH, expiresAt = Instant.EPOCH.plusSeconds(604_800),
)

class MiniAppViewsTest : StringSpec({
    "the verb follows the typed currency, not only the side" {
        saysOf(r(Verb.SELL, "1000", "EUR")) shouldBe Says.GIVES   // offer, typed in base
        saysOf(r(Verb.BUY, "45000", "RUB")) shouldBe Says.WANTS   // offer, typed in quote
        saysOf(r(Verb.BUY, "950", "EUR")) shouldBe Says.WANTS     // bid, typed in base
        saysOf(r(Verb.SELL, "20000", "RUB")) shouldBe Says.GIVES  // bid, typed in quote
    }
    "the other leg is the currency not typed" {
        otherLeg(EURRUB, "EUR") shouldBe "RUB"
        otherLeg(EURRUB, "RUB") shouldBe "EUR"
    }
    "the estimate converts through the reference rate both ways" {
        approxOther(r(Verb.BUY, "950", "EUR"), BigDecimal("94.12"))!!.toInt() shouldBe 89414
        approxOther(r(Verb.BUY, "45000", "RUB"), BigDecimal("94.12"))!!.toInt() shouldBe 478
    }
    "no rate means no estimate" {
        approxOther(r(Verb.BUY, "950", "EUR"), null).shouldBeNull()
    }
    "Marko's range at 20% both ways" {
        val range = acceptedRange(BigDecimal(950), 20, 20)
        range.min.toDouble() shouldBe 760.0
        range.max!!.toDouble() shouldBe 1187.5
    }
    "a 100% own tolerance has no upper bound" {
        acceptedRange(BigDecimal(950), 20, 100).max.shouldBeNull()
    }
    "plainText strips markup and unescapes" {
        plainText("""Asked <a href="tg://user?id=1">Ann &amp; co</a> &lt;3""") shouldBe "Asked Ann & co <3"
    }
    "the range is exactly what findCounterparties accepts" {
        checkAll(Arb.int(100..100_000), Arb.int(100..100_000), Arb.int(1..100), Arb.int(1..100)) { s, theirs, tm, tt ->
            val range = acceptedRange(BigDecimal(theirs), tt, tm)
            val sd = s.toDouble()
            // Exact boundaries are decided by DECIMAL64 rounding on both sides; stay clear of them.
            val nearEdge = kotlin.math.abs(sd - range.min.toDouble()) < 0.01 ||
                (range.max != null && kotlin.math.abs(sd - range.max!!.toDouble()) < 0.01)
            if (!nearEdge) {
                val mine = r(Verb.SELL, s.toString(), "EUR", userId = 1)
                val peer = r(Verb.BUY, theirs.toString(), "EUR", userId = 2)
                val found = findCounterparties(mine, listOf(peer), null, tm, peerTolerancePct = { tt })
                val inside = sd >= range.min.toDouble() && (range.max == null || sd <= range.max!!.toDouble())
                if (inside) found shouldHaveSize 1 else found.shouldBeEmpty()
            }
        }
    }
})
