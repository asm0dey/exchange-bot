package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

private const val BODY = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}"""

private fun admin(name: String): Pair<AdminService, ChatSettingsRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val settings = ChatSettingsRepository(ds, testCrypto())
    val client = RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }))
    return AdminService(settings, client) to settings
}

class AdminServiceTest : StringSpec({
    "sets a pair the feed can price" {
        val (svc, settings) = admin("pair")
        svc.setPair(-100L, "EUR", "RUB") shouldContain "EUR/RUB"
        settings.get(-100L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
    "refuses a code that is not ISO 4217" {
        val (svc, _) = admin("badiso")
        svc.setPair(-100L, "EUR", "XYZ") shouldContain "XYZ"
    }
    "refuses a pair the feed cannot price" {
        val (svc, settings) = admin("nofeed")
        svc.setPair(-100L, "EUR", "GBP") shouldContain "can't get a rate"
        settings.get(-100L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
    "refuses the same currency twice" {
        val (svc, _) = admin("same")
        svc.setPair(-100L, "EUR", "EUR") shouldContain "two different"
    }
    "tolerance must be a sensible percentage" {
        val (svc, settings) = admin("tol")
        svc.setTolerance(-100L, "5")
        settings.get(-100L).tolerancePct shouldBe 5
        svc.setTolerance(-100L, "0") shouldContain "between"
        svc.setTolerance(-100L, "101") shouldContain "between"
        svc.setTolerance(-100L, "lots") shouldContain "between"
    }
    "time in force must be a sensible number of days" {
        val (svc, settings) = admin("tif")
        svc.setTif(-100L, "30")
        settings.get(-100L).tifDays shouldBe 30
        svc.setTif(-100L, "0") shouldContain "between"
        svc.setTif(-100L, "400") shouldContain "between"
    }
})
