package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.bigdecimal.shouldBeEqualIgnoringScale
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")
private const val BODY = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}"""

private fun housekeeping(name: String, at: Instant): Pair<Housekeeping, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val clock = Clock.fixed(at, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
    val settings = ChatSettingsRepository(ds, crypto, clock)
    val rates = RateService(
        RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
        RateRepository(ds), clock,
    )
    return Housekeeping(requests, settings, rates, MessageLogRepository(ds, crypto, clock), clock) to requests
}

class TasksTest : StringSpec({
    "the sweep lapses requests past their time in force" {
        val (hk, repo) = housekeeping("sweep", T0.plusSeconds(8 * 86_400))
        repo.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        hk.sweep() shouldBe 1
        repo.resting(-100L) shouldHaveSize 0
    }
    "the sweep leaves live requests alone" {
        val (hk, repo) = housekeeping("sweepalive", T0.plusSeconds(86_400))
        repo.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        hk.sweep() shouldBe 0
        repo.resting(-100L) shouldHaveSize 1
    }
    "the refresh caches a rate for every pair a chat has configured" {
        val ds = memDataSource("refresh")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val settings = ChatSettingsRepository(ds, crypto, clock)
        settings.save(ChatSettings(-100L, EURRUB, 20, 7))
        val rateRepo = RateRepository(ds)
        val rates = RateService(
            RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
            rateRepo, clock,
        )
        Housekeeping(RequestRepository(ds, crypto, clock), settings, rates, MessageLogRepository(ds, crypto, clock), clock)
            .refreshRates()
        // BigDecimal.equals is scale-sensitive; the DB read comes back scale-10
        // (DECIMAL(30, 10)) regardless of the scale it was written with — same
        // reason RateServiceTest compares by value, not by `shouldBe`.
        rateRepo.get("EUR", "RUB")!!.rate.shouldBeEqualIgnoringScale(BigDecimal("99.98"))
    }
    "startScheduler registers both tasks with no task_data" {
        // db-scheduler's task_data is an unencrypted BYTEA — nothing chat- or
        // pair-identifying may ever land in it. `Tasks.recurring(name, schedule)`
        // (no dataClass argument) structurally can't carry data today, but this
        // pins that down against a future switch to a data-carrying overload:
        // `Scheduler.start()` runs `executeOnStartup()` synchronously, so both
        // rows already exist by the time `startScheduler` returns.
        val ds = memDataSource("scheduled")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val hk = Housekeeping(
            RequestRepository(ds, crypto, clock),
            ChatSettingsRepository(ds, crypto, clock),
            RateService(
                RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
                RateRepository(ds), clock,
            ),
            MessageLogRepository(ds, crypto, clock),
            clock,
        )
        startScheduler(ds, hk)

        val taskData = ds.connection.use { conn ->
            conn.createStatement().executeQuery("SELECT task_name, task_data FROM scheduled_tasks").use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("task_name"), rs.getBytes("task_data"))
                }
            }
        }
        taskData.keys shouldBe setOf("sweep", "refresh-rates")
        taskData.values.all { it == null } shouldBe true
    }
})
