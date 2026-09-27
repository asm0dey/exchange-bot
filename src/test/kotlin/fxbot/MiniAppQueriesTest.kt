package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class MiniAppQueriesTest : StringSpec({
    val ds = memDataSource("miniapp-queries")
    migrate(ds)
    val db = connectExposed(ds)
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto, db = db)
    val messages = MessageLogRepository(ds, crypto, db = db)
    val pair = CurrencyPair("EUR", "RUB")

    "openFor lists one person's open rows in every chat, and nothing closed or anybody else's" {
        val a = requests.create(-100, 1, "ann", Side.OFFER, "EUR", BigDecimal(10), pair, 7)
        val b = requests.create(NO_CHAT_ID, 1, "ann", Side.BID, "EUR", BigDecimal(5), pair, 7)
        val c = requests.create(-100, 1, "ann", Side.BID, "EUR", BigDecimal(3), pair, 7)
        requests.transition(c.refToken, RequestState.OPEN, RequestState.CANCELLED)
        requests.create(-100, 2, "bob", Side.BID, "EUR", BigDecimal(10), pair, 7)
        requests.openFor(1).map { it.refToken }.toSet() shouldBe setOf(a.refToken, b.refToken)
    }

    "an ask is open while it offers both answers, and drops out once No is withdrawn" {
        val yes = Cb.confirm("DECL", "MINE")
        val no = Cb.refuse("DECL", "MINE")
        messages.record(1, 77, listOf("DECL", "MINE"), listOf(2, 1), "Did you swap?", listOf(Button("Yes", yes), Button("No", no)))
        messages.openAsksFor("MINE") shouldContainExactly listOf("DECL" to "MINE")
        messages.dropButton(1, 77, no)
        messages.openAsksFor("MINE").shouldBeEmpty()
    }
})
