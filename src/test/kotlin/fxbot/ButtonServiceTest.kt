package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private class ButtonFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val log = MessageLogRepository(ds, crypto)
    val svc = ButtonService(log, requests)
}

class ButtonServiceTest : StringSpec({
    "a closed request's message is rewritten to its own text plus a status line" {
        val f = ButtonFixture("editstatus")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        f.log.record(
            -100L, 10L, listOf(a.refToken), listOf(1L), "1 person matches:",
            listOf(Button("✖️ Cancel", Cb.cancel(a.refToken))),
        )
        f.requests.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken), recordingBot(calls))
        val edit = calls.single { it.path == "editMessageText" }
        edit.body shouldContain "1 person matches:"
        edit.body shouldContain "${a.shortId} — withdrawn"
        // The message's only button named the closed request, so the keyboard goes out
        // empty — not as a stray blank row, and not left behind.
        edit.body shouldContain "\"inline_keyboard\":[]"
        edit.body shouldNotContain a.refToken
    }
    "the status line says how it closed" {
        val f = ButtonFixture("statuswords")
        val done = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val lapsed = f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        f.log.record(-100L, 10L, listOf(done.refToken), listOf(1L), "x", emptyList())
        f.log.record(-100L, 11L, listOf(lapsed.refToken), listOf(2L), "x", emptyList())
        f.requests.transition(done.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(lapsed.refToken, RequestState.OPEN, RequestState.EXPIRED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(done.refToken, lapsed.refToken), recordingBot(calls))
        val bodies = calls.filter { it.path == "editMessageText" }.joinToString("\n") { it.body }
        // The whole status line, not a bare word: "done" alone is a substring of a JSON
        // body full of method names and escaped text, and could pass on the wrong thing.
        bodies shouldContain "${done.shortId} — done"
        bodies shouldContain "${lapsed.shortId} — lapsed"
    }
    "closing one interest keeps the buttons of the others on a batched message" {
        val f = ButtonFixture("keepothers")
        val closing = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val staying = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("2"), EURRUB, 7)
        f.log.record(
            -100L, 10L, listOf(closing.refToken, staying.refToken), listOf(1L, 1L), "two interests",
            listOf(
                Button("✖️ Cancel a", Cb.cancel(closing.refToken)),
                Button("✖️ Cancel b", Cb.cancel(staying.refToken)),
            ),
        )
        f.requests.transition(closing.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(closing.refToken), recordingBot(calls))
        val edit = calls.single { it.path == "editMessageText" }
        edit.body shouldContain staying.refToken
        edit.body shouldNotContain closing.refToken
    }
    "a done button naming a closed counterparty goes, even though the presser's own is still resting" {
        val f = ButtonFixture("keepacross")
        val mine = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val gone = f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        val here = f.requests.create(-100L, 3L, "cat", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        f.log.record(
            -100L, 10L, listOf(mine.refToken, gone.refToken, here.refToken), listOf(1L, 2L, 3L), "2 people match:",
            listOf(
                Button("✅ Done with ann", Cb.done(mine.refToken, gone.shortId)),
                Button("✅ Done with cat", Cb.done(mine.refToken, here.shortId)),
                Button("✖️ Cancel my request", Cb.cancel(mine.refToken)),
            ),
        )
        f.requests.transition(gone.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(gone.refToken), recordingBot(calls))
        val edit = calls.single { it.path == "editMessageText" }
        // The button that named the departed side is gone. It never carried her token — a done
        // names her ROW by short id now — so the payload itself is what must be absent.
        edit.body shouldNotContain Cb.done(mine.refToken, gone.shortId)
        edit.body shouldContain Cb.done(mine.refToken, here.shortId) // the other pairing is still pressable
        edit.body shouldContain Cb.cancel(mine.refToken) // and so is the presser's own cancel
    }
    "a done for another chat's row survives a close that freed the same short id here" {
        val f = ButtonFixture("shortidscope")
        // One private reply can carry buttons for several chats at once (`statedButtons`
        // does), and a short id is unique only inside its own chat — so "b" here and "b"
        // there are two different rows, and only one of them closed.
        val mineHere = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val goneHere = f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        val mineThere = f.requests.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val stillThere = f.requests.create(-200L, 3L, "cat", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        goneHere.shortId shouldBe stillThere.shortId
        f.log.record(
            1L, 10L,
            listOf(mineHere.refToken, goneHere.refToken, mineThere.refToken, stillThere.refToken),
            listOf(1L, 2L, 1L, 3L), "two groups",
            listOf(
                Button("✅ Done with ann", Cb.done(mineHere.refToken, goneHere.shortId)),
                Button("✅ Done with cat", Cb.done(mineThere.refToken, stillThere.shortId)),
            ),
        )
        f.requests.transition(goneHere.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(goneHere.refToken), recordingBot(calls))
        val edit = calls.single { it.path == "editMessageText" }
        edit.body shouldNotContain Cb.done(mineHere.refToken, goneHere.shortId)
        edit.body shouldContain Cb.done(mineThere.refToken, stillThere.shortId)
    }
    "a reopened request gets its buttons back and its status line dropped" {
        val f = ButtonFixture("reopenrefresh")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.log.record(
            -100L, 10L, listOf(a.refToken), listOf(1L), "1 person matches:",
            listOf(Button("✖️ Cancel", Cb.cancel(a.refToken))),
        )
        f.requests.closeInterest("i1", RequestState.CANCELLED)
        val closed = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken), recordingBot(closed))
        closed.single { it.path == "editMessageText" }.body shouldContain "withdrawn"

        f.requests.reopenInterest("i1", RequestState.CANCELLED) { 7 }
        val reopened = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken), recordingBot(reopened))
        val edit = reopened.single { it.path == "editMessageText" }
        // Re-read from what was actually rendered: the false outcome is gone from the text,
        // and the button it had stripped is offered again.
        edit.body shouldNotContain "withdrawn"
        edit.body shouldContain "1 person matches:"
        edit.body shouldContain Cb.cancel(a.refToken)
    }
    "a message recorded before the text was stored falls back to stripping its keyboard" {
        val f = ButtonFixture("legacystrip")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.log.record(-100L, 10L, listOf(a.refToken), listOf(1L)) // no text, no buttons
        f.requests.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken), recordingBot(calls))
        calls.filter { it.path == "editMessageReplyMarkup" } shouldHaveSize 1
        calls.filter { it.path == "editMessageText" } shouldHaveSize 0
    }
    "each carrier message is edited once, however many of its tokens closed" {
        val f = ButtonFixture("onceper")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val b = f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        f.log.record(-100L, 10L, listOf(a.refToken, b.refToken), listOf(1L, 2L), "both", emptyList())
        f.requests.transition(a.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(b.refToken, RequestState.OPEN, RequestState.DONE)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken, b.refToken), recordingBot(calls))
        calls.filter { it.path == "editMessageText" } shouldHaveSize 1
    }
    "an interest shown in two chats has both its messages rewritten" {
        val f = ButtonFixture("bothchats")
        val here = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        val there = f.requests.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.log.record(-100L, 10L, listOf(here.refToken), listOf(1L), "shown here", emptyList())
        f.log.record(-200L, 11L, listOf(there.refToken), listOf(1L), "shown there", emptyList())
        val closed = f.requests.closeInterest("i1", RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(closed, recordingBot(calls))
        calls.filter { it.path == "editMessageText" } shouldHaveSize 2
    }
})
