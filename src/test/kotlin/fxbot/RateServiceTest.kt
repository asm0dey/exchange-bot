package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.bigdecimal.shouldBeEqualIgnoringScale
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")
private const val BODY = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98,"USD":1.08}}"""

private fun okClient() = HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
private fun deadClient() = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

private fun service(name: String, client: HttpClient, clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)): Pair<RateService, RateRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val repo = RateRepository(ds)
    return RateService(RateClient(client), repo, clock) to repo
}

/**
 * `FxRates.rate` is `decimal(30, 10)`, and Exposed's `DecimalColumnType.valueFromDB`
 * unconditionally calls `BigDecimal.setScale(10, RoundingMode.HALF_EVEN)` on every read
 * — independent of the driver, and independent of the scale a value was written with.
 * So a round-tripped rate always comes back at scale 10 (`99.9800000000`), never at the
 * scale it was written with (`99.98`). `BigDecimal.equals` is scale-sensitive (unlike
 * `compareTo`), so asserting a round-tripped rate with plain `shouldBe` against a
 * differently-scaled literal is testing storage artifacts, not the rate's value.
 * `shouldBeEqualIgnoringScale` (kotest-assertions-core) compares by value via
 * `compareTo`, which is what every assertion on a DB-read rate below actually means.
 */
class RateServiceTest : StringSpec({
    "a successful refresh caches the rate and reports it fresh" {
        val (svc, _) = service("fresh", okClient())
        svc.refresh(setOf(EURRUB))
        val status = svc.status(EURRUB)
        status.shouldBeInstanceOf<RateStatus.Fresh>()
        status.rate.shouldNotBeNull().shouldBeEqualIgnoringScale(BigDecimal("99.98"))
    }
    "a refresh stores every rate the feed returns, not just configured pairs" {
        val (svc, repo) = service("full-response", okClient())
        svc.refresh(setOf(EURRUB))
        repo.get("EUR", "USD")!!.rate.shouldBeEqualIgnoringScale(BigDecimal("1.08"))
    }
    "a dead feed with nothing cached is unavailable" {
        val (svc, _) = service("cold", deadClient())
        svc.refresh(setOf(EURRUB))
        svc.status(EURRUB).shouldBeInstanceOf<RateStatus.Unavailable>()
        svc.status(EURRUB).rate shouldBe null
    }
    "a dead feed with a warm cache keeps serving the cached rate" {
        val (svc, repo) = service("warm", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(3600))
        svc.refresh(setOf(EURRUB))
        val status = svc.status(EURRUB)
        status.shouldBeInstanceOf<RateStatus.Fresh>()
        status.rate.shouldNotBeNull().shouldBeEqualIgnoringScale(BigDecimal("95.00"))
    }
    "a cache older than seven days is marked stale but still used" {
        val (svc, repo) = service("stale", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(8 * 86_400))
        val status = svc.status(EURRUB)
        status.shouldBeInstanceOf<RateStatus.Stale>()
        status.rate.shouldNotBeNull().shouldBeEqualIgnoringScale(BigDecimal("95.00"))
    }
    "a cache exactly seven days old is still fresh" {
        val (svc, repo) = service("boundary-fresh", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(7 * 86_400))
        svc.status(EURRUB).shouldBeInstanceOf<RateStatus.Fresh>()
    }
    "a cache one second past seven days is stale" {
        val (svc, repo) = service("boundary-stale", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(7 * 86_400 + 1))
        svc.status(EURRUB).shouldBeInstanceOf<RateStatus.Stale>()
    }
    "a refresh failure never erases a cached rate" {
        val (svc, repo) = service("keep", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(3600))
        svc.refresh(setOf(EURRUB))
        repo.get("EUR", "RUB")!!.rate.shouldBeEqualIgnoringScale(BigDecimal("95.00"))
    }
    "putting twice for the same composite key updates in place, not a second row" {
        val ds = memDataSource("composite-upsert")
        migrate(ds)
        val repo = RateRepository(ds)
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(3600))
        repo.put("EUR", "RUB", BigDecimal("97.50"), T0)
        repo.get("EUR", "RUB")!!.rate.shouldBeEqualIgnoringScale(BigDecimal("97.50"))
        // A distinct pair sharing the base half of the composite key must not collide.
        repo.put("EUR", "USD", BigDecimal("1.08"), T0)
        repo.get("EUR", "RUB")!!.rate.shouldBeEqualIgnoringScale(BigDecimal("97.50"))
        repo.get("EUR", "USD")!!.rate.shouldBeEqualIgnoringScale(BigDecimal("1.08"))
    }
    "the client reads a rate through the JSON text, not through Double" {
        // 18 significant digits: beyond a Double's ~15-17 digit precision, so a
        // Double-then-toString hop would silently corrupt this value before it ever
        // reaches BigDecimal. Kept under the fx_rate column's 30-digit precision cap.
        val body = """{"result":"success","base_code":"EUR","rates":{"RUB":123456789012.345678}}"""
        val client = RateClient(HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }))
        val rates = client.fetch("EUR")
        rates.shouldNotBeNull()["RUB"] shouldBe BigDecimal("123456789012.345678")
    }
})
