package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun service(name: String): RequestService {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val clock = Clock.fixed(T0, ZoneOffset.UTC)
    val rates = RateRepository(ds)
    rates.put("EUR", "RUB", BigDecimal("99.98"), T0)
    return RequestService(
        RequestRepository(ds, crypto, clock),
        ChatSettingsRepository(ds, crypto, clock),
        RateService(RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })), rates, clock),
    )
}

class RequestServiceTest : StringSpec({
    "posting a sell records an offer and finds nobody at first" {
        val svc = service("post")
        val result = svc.post(-100L, 1L, "bob", Verb.SELL, "1000", "EUR")
        result.shouldBeInstanceOf<PostResult.Posted>()
        result.request.side shouldBe Side.OFFER
        result.found.size shouldBe 0
    }
    "the second person is matched with the first" {
        val svc = service("match")
        svc.post(-100L, 1L, "bob", Verb.SELL, "1000", "EUR")
        val result = svc.post(-100L, 2L, "alice", Verb.BUY, "1000", "EUR")
        result.shouldBeInstanceOf<PostResult.Posted>()
        result.found.size shouldBe 1
        result.found[0].request.username shouldBe "bob"
    }
    "a buy stated in the base currency is a bid" {
        val svc = service("bid")
        val result = svc.post(-100L, 1L, "bob", Verb.BUY, "1000", "EUR")
        result.shouldBeInstanceOf<PostResult.Posted>()
        result.request.side shouldBe Side.BID
        result.request.statedCurrency shouldBe "EUR"
        result.request.statedAmount shouldBe BigDecimal("1000")
    }
    "an unparseable amount is rejected with a usable message" {
        val r = service("badamount").post(-100L, 1L, "bob", Verb.SELL, "lots", "EUR")
        r.shouldBeInstanceOf<PostResult.Rejected>()
        r.reason shouldContain "amount"
    }
    "a non-positive amount is rejected" {
        service("zero").post(-100L, 1L, "bob", Verb.SELL, "0", "EUR").shouldBeInstanceOf<PostResult.Rejected>()
    }
    "an unknown currency is rejected" {
        val r = service("badccy").post(-100L, 1L, "bob", Verb.SELL, "10", "XYZ")
        r.shouldBeInstanceOf<PostResult.Rejected>()
        r.reason shouldContain "XYZ"
    }
    "a currency outside this chat's pair is rejected and names the pair" {
        val r = service("offpair").post(-100L, 1L, "bob", Verb.SELL, "10", "JPY")
        r.shouldBeInstanceOf<PostResult.Rejected>()
        r.reason shouldContain "EUR/RUB"
    }
    "posting works with no rate cached at all" {
        val ds = memDataSource("norate")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val svc = RequestService(
            RequestRepository(ds, crypto, clock),
            ChatSettingsRepository(ds, crypto, clock),
            RateService(RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })), RateRepository(ds), clock),
        )
        svc.post(-100L, 1L, "bob", Verb.BUY, "1000", "EUR").shouldBeInstanceOf<PostResult.Posted>()
    }
})
