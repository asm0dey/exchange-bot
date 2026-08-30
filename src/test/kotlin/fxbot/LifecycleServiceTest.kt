package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun lifecycle(name: String): Pair<LifecycleService, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val repo = RequestRepository(ds, testCrypto(), Clock.fixed(T0, ZoneOffset.UTC))
    return LifecycleService(repo) to repo
}

private fun RequestRepository.put(chatId: Long, userId: Long, name: String, side: Side) =
    create(chatId, userId, name, side, "EUR", BigDecimal("1000"), EURRUB, 7)

class LifecycleServiceTest : StringSpec({
    "the owner can cancel their own request" {
        val (svc, repo) = lifecycle("cancel")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "nobody else can cancel it" {
        val (svc, repo) = lifecycle("cancelother")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 2L, a.shortId).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "cancelling an unknown short id says so" {
        val (svc, _) = lifecycle("cancelmissing")
        svc.cancel(-100L, 1L, "zz").shouldBeInstanceOf<ActionResult.Gone>()
    }

    "done closes both sides" {
        val (svc, repo) = lifecycle("done")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
    }
    "either counterparty may press done" {
        val (svc, repo) = lifecycle("doneeither")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(2L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Ok>()
    }
    "a third party may not" {
        val (svc, repo) = lifecycle("donethird")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(3L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "a second done reports it was already closed" {
        val (svc, repo) = lifecycle("donetwice")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken)
        val again = svc.done(2L, a.refToken, b.refToken)
        again.shouldBeInstanceOf<ActionResult.Gone>()
        again.text shouldContain "already"
    }
    "a forged token for a request the presser does not own is denied" {
        val (svc, repo) = lifecycle("forged")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(99L, b.refToken, a.refToken).shouldBeInstanceOf<ActionResult.Denied>()
    }
    "closing with a counterparty who has already gone still closes your own" {
        val (svc, repo) = lifecycle("peergone")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.cancel(-100L, 2L, b.shortId)
        val result = svc.done(1L, a.refToken, b.refToken)
        result.shouldBeInstanceOf<ActionResult.Ok>()
        result.text shouldContain "only your own"
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
    }

    "reopen revives your most recent closure with a fresh clock" {
        val (svc, repo) = lifecycle("reopen")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId)
        svc.reopen(-100L, 1L).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "reopen with nothing closed says so" {
        val (svc, _) = lifecycle("reopennothing")
        svc.reopen(-100L, 1L).shouldBeInstanceOf<ActionResult.Gone>()
    }
})
