package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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
private val EURRUB = CurrencyPair("EUR", "RUB")
private const val GROUP = -100L

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

/**
 * [RequestService] wired against a real in-memory database, with the repository exposed so
 * a test can seed a row the service itself has no way to create — a showing already resting
 * with an [interestToken], standing in for one stated privately and fanned into this chat.
 */
private class ServiceFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, clock)
    val chats = ChatSettingsRepository(ds, crypto, clock)
    val rateRepo = RateRepository(ds).also { it.put("EUR", "RUB", BigDecimal("99.98"), T0) }
    val rates = RateService(
        RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })),
        rateRepo,
        clock,
    )
    val svc = RequestService(requests, chats, rates)
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
    "a group post owes a private word to whoever stated their side privately" {
        val f = ServiceFixture("post_appeared")
        val showing = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val typed = f.requests.create(GROUP, 3L, "cat", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        val r = f.svc.post(GROUP, 1L, "bob", Verb.SELL, "1000", "EUR")
        r.shouldBeInstanceOf<PostResult.Posted>()
        r.found.map { it.request.refToken } shouldContainExactlyInAnyOrder listOf(showing.refToken, typed.refToken)
        r.appeared.map { it.refToken } shouldBe listOf(showing.refToken)
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
