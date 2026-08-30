package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun repo(name: String, clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)): Pair<RequestRepository, javax.sql.DataSource> {
    val ds = memDataSource(name)
    migrate(ds)
    return RequestRepository(ds, testCrypto(), clock) to ds
}

/**
 * Advances by 1ms on every read, so calls that must be strictly ordered in
 * time (e.g. two `transition`s) get distinct instants — a fixed clock cannot
 * discriminate "closed later" from "closed earlier" at all.
 */
private class TickingClock(start: Instant) : Clock() {
    private var current = start
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current.also { current = current.plusMillis(1) }
}

class RequestRepositoryTest : StringSpec({
    "creates and reads back a resting request" {
        val (r, _) = repo("create")
        val created = r.create(-100L, 7L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        created.shortId shouldBe "a"
        created.expiresAt shouldBe T0.plusSeconds(7 * 86_400)

        val resting = r.resting(-100L)
        resting shouldHaveSize 1
        resting[0].username shouldBe "alice"
        resting[0].statedAmount shouldBe BigDecimal("1000")
        resting[0].side shouldBe Side.OFFER
        resting[0].pair shouldBe EURRUB
    }

    "the payload never holds a username in the clear" {
        val (r, ds) = repo("opaque")
        r.create(-100L, 7L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        ds.connection.use { c ->
            c.prepareStatement("SELECT payload FROM request").executeQuery().use { rs ->
                rs.next() shouldBe true
                val raw = String(rs.getBytes(1), Charsets.ISO_8859_1)
                raw.contains("alice") shouldBe false
                raw.contains("EUR") shouldBe false
            }
        }
    }

    "short ids are allocated in order and recycled after closing" {
        val (r, _) = repo("shortids")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val b = r.create(-100L, 2L, "b", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        a.shortId shouldBe "a"
        b.shortId shouldBe "b"

        r.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED) shouldBe true
        r.create(-100L, 3L, "c", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7).shortId shouldBe "a"
    }

    "short ids are per chat" {
        val (r, _) = repo("perchat")
        r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7).shortId shouldBe "a"
        r.create(-200L, 2L, "b", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7).shortId shouldBe "a"
    }

    "a transition only fires from the expected state" {
        val (r, _) = repo("transition")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.transition(a.refToken, RequestState.OPEN, RequestState.DONE) shouldBe true
        r.transition(a.refToken, RequestState.OPEN, RequestState.DONE) shouldBe false
        r.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
    }

    "finds by short id only within the chat" {
        val (r, _) = repo("byshort")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.byShortId(-100L, "a")!!.refToken shouldBe a.refToken
        r.byShortId(-200L, "a").shouldBeNull()
    }

    "most recently closed means closed, not created" {
        // Closed in REVERSE creation order, so a query ordering by row_id would
        // return the wrong one and this test would catch it. A ticking clock is
        // used because the repository's default fixed test clock would stamp
        // both transitions with the same closed_at and fail to discriminate.
        val (r, _) = repo("recent", TickingClock(T0))
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val b = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("2"), EURRUB, 7)
        r.transition(b.refToken, RequestState.OPEN, RequestState.DONE)
        r.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        r.mostRecentlyClosed(-100L, 1L)!!.refToken shouldBe a.refToken
        r.mostRecentlyClosed(-100L, 999L).shouldBeNull()
    }

    "expiry sweeps only what is due" {
        val (r, _) = repo("expiry")
        r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.expireDue(T0.plusSeconds(6 * 86_400)) shouldBe 0
        r.expireDue(T0.plusSeconds(8 * 86_400)) shouldBe 1
        r.resting(-100L) shouldHaveSize 0
    }

    "forgetting removes rows in one chat, or everywhere" {
        val (r, _) = repo("forget")
        val here = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.deleteFor(1L, -100L).single() shouldBe here.refToken
        r.resting(-200L) shouldHaveSize 1
        r.deleteFor(1L, null) shouldHaveSize 1
        r.resting(-200L) shouldHaveSize 0
    }

    "a fractional amount survives the payload round trip exactly" {
        val (r, _) = repo("precision")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1234.5670"), EURRUB, 7)
        r.byRefToken(a.refToken)!!.statedAmount shouldBe BigDecimal("1234.5670")
    }

    "a chat migration keeps payloads readable" {
        val (r, _) = repo("migrate")
        r.create(-100L, 1L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        r.rewriteChatRef(-100L, -1001L) shouldBe 1
        r.resting(-100L) shouldHaveSize 0
        val moved = r.resting(-1001L)
        moved shouldHaveSize 1
        moved[0].username shouldBe "alice"
        moved[0].chatId shouldBe -1001L
    }

    "byRefToken carries the chat id, because the payload holds it" {
        val (r, _) = repo("chatid")
        val a = r.create(-100L, 7L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        r.byRefToken(a.refToken)!!.chatId shouldBe -100L
    }

    "byRefToken returns null for an unknown token" {
        val (r, _) = repo("unknown")
        r.byRefToken("nope".padEnd(22, 'x')).shouldBeNull()
    }

    "resting excludes closed requests" {
        val (r, _) = repo("resting")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        r.resting(-100L).shouldHaveSize(0)
        r.byRefToken(a.refToken).shouldNotBeNull()
    }
})
