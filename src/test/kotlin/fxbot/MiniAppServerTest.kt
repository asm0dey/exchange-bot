package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** Accepts "ok:<userId>" as initData, so route tests don't re-test the HMAC (Task 1 does). */
private val verify: (String) -> Viewer? = { raw -> raw.removePrefix("ok:").toLongOrNull()?.let { Viewer(it, "u$it", null) } }

private class FakeBackend : MiniAppBackend {
    var lastPostChat: Long? = null
    override suspend fun chat(viewer: Viewer, chatId: Long) = ApiResult.Ok(
        ChatView(chatId, "G", "EUR", "RUB", "94.12", false, listOf(CardDto("s1", true, Says.GIVES, "10", "EUR", "RUB", name = "Alice", createdAt = 0L, expiresAt = 0L))),
    )
    override suspend fun postInChat(viewer: Viewer, chatId: Long, body: NewRequestBody): ApiResult<MessageDto> {
        lastPostChat = chatId
        return if (body.amount == "bad") ApiResult.Refused("I couldn't read \"bad\" as an amount.") else ApiResult.Ok(MessageDto("Posted."))
    }
    override suspend fun me(viewer: Viewer) = ApiResult.Ok(MeView(20, 5, emptyList(), emptyList()))
    override suspend fun browse(viewer: Viewer) = ApiResult.Ok(BrowseView(emptyList(), emptyMap()))
    override suspend fun state(viewer: Viewer, body: NewInterestBody) = ApiResult.Ok(MessageDto("Posted."))
    override suspend fun cancel(viewer: Viewer, token: String) = ApiResult.Ok(MessageDto("Withdrawn."))
    override suspend fun done(viewer: Viewer, body: DoneBody) = ApiResult.Ok(MessageDto("Asked."))
    override suspend fun confirm(viewer: Viewer, body: AnswerBody) = ApiResult.Ok(MessageDto("Done."))
    override suspend fun refuse(viewer: Viewer, body: AnswerBody) = ApiResult.Ok(MessageDto("Noted."))
    override suspend fun setTolerance(viewer: Viewer, pct: Int) = ApiResult.Ok(MeView(pct, 5, emptyList(), emptyList()))
}

/** A backend whose every method throws, carrying a fake secret in the message so a test can
 * confirm neither the message nor the request path ever reach the log or the response. */
private class ThrowingBackend : MiniAppBackend {
    class Boom : RuntimeException("boom token=SECRET-TOKEN-i1xxxxxxxxxx")
    override suspend fun chat(viewer: Viewer, chatId: Long): Nothing = throw Boom()
    override suspend fun postInChat(viewer: Viewer, chatId: Long, body: NewRequestBody): Nothing = throw Boom()
    override suspend fun me(viewer: Viewer): Nothing = throw Boom()
    override suspend fun browse(viewer: Viewer): Nothing = throw Boom()
    override suspend fun state(viewer: Viewer, body: NewInterestBody): Nothing = throw Boom()
    override suspend fun cancel(viewer: Viewer, token: String): Nothing = throw Boom()
    override suspend fun done(viewer: Viewer, body: DoneBody): Nothing = throw Boom()
    override suspend fun confirm(viewer: Viewer, body: AnswerBody): Nothing = throw Boom()
    override suspend fun refuse(viewer: Viewer, body: AnswerBody): Nothing = throw Boom()
    override suspend fun setTolerance(viewer: Viewer, pct: Int): Nothing = throw Boom()
}

class MiniAppServerTest : StringSpec({
    val members = setOf(-1001L to 1L)
    val probeCalls = AtomicInteger()
    val probe = MembershipProbe { c, u -> probeCalls.incrementAndGet(); (c to u) in members }

    fun app(
        backend: MiniAppBackend = FakeBackend(),
        prefix: String = "",
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application { miniApp(verify, backend, MembershipCache(probe), prefix) }
        block()
    }

    "no initData is 401" {
        app { client.get("/api/me").status shouldBe HttpStatusCode.Unauthorized }
    }
    "bad initData is 401" {
        app { client.get("/api/me") { header("Authorization", "tma nope") }.status shouldBe HttpStatusCode.Unauthorized }
    }
    "a member reads their group" {
        app {
            val r = client.get("/api/chat/-1001") { header("Authorization", "tma ok:1") }
            r.status shouldBe HttpStatusCode.OK
            r.bodyAsText() shouldContain "\"base\":\"EUR\""
        }
    }
    "encodeDefaults sends a card's default fields, not just the ones it set" {
        app {
            val r = client.get("/api/chat/-1001") { header("Authorization", "tma ok:1") }
            val body = r.bodyAsText()
            body shouldContain "\"counterparties\":[]"
            body shouldContain "\"rateStale\":false"
        }
    }
    "a non-member is 403 and the backend is never asked" {
        val backend = FakeBackend()
        app(backend) {
            client.post("/api/chat/-1001/requests") {
                header("Authorization", "tma ok:2"); contentType(ContentType.Application.Json)
                setBody("""{"says":"GIVES","amount":"10","currency":"EUR"}""")
            }.status shouldBe HttpStatusCode.Forbidden
        }
        backend.lastPostChat shouldBe null
    }
    "a positive chat id is 403 without probing" {
        val before = probeCalls.get()
        app { client.get("/api/chat/1") { header("Authorization", "tma ok:1") }.status shouldBe HttpStatusCode.Forbidden }
        probeCalls.get() shouldBe before
    }
    "a service refusal is 422 with its message" {
        app {
            val r = client.post("/api/chat/-1001/requests") {
                header("Authorization", "tma ok:1"); contentType(ContentType.Application.Json)
                setBody("""{"says":"GIVES","amount":"bad","currency":"EUR"}""")
            }
            r.status shouldBe HttpStatusCode.UnprocessableEntity
            r.bodyAsText() shouldContain "couldn't read"
        }
    }
    "a malformed body is 400" {
        app {
            client.post("/api/interests") {
                header("Authorization", "tma ok:1"); contentType(ContentType.Application.Json); setBody("{")
            }.status shouldBe HttpStatusCode.BadRequest
        }
    }
    "index.html (and the root, which resolves to it) is no-cache; a hashed asset is long-lived and immutable" {
        app {
            val index = client.get("/")
            index.status shouldBe HttpStatusCode.OK
            index.headers[HttpHeaders.CacheControl] shouldBe "no-cache"

            val asset = client.get("/assets/x.js")
            asset.status shouldBe HttpStatusCode.OK
            asset.headers[HttpHeaders.CacheControl] shouldBe "max-age=31536000, immutable"
        }
    }
    "an unhandled backend exception is 500 with a safe message, and the response never carries a stack trace or the exception's message" {
        app(ThrowingBackend()) {
            val r = client.get("/api/me") { header("Authorization", "tma ok:1") }
            r.status shouldBe HttpStatusCode.InternalServerError
            val body = r.bodyAsText()
            body shouldContain "Something went wrong."
            body shouldNotContain "boom"
            body shouldNotContain "SECRET-TOKEN"
        }
    }
    "the logged line for an unhandled exception carries a fixed label and the exception's class only — never the path or its message" {
        val captured = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        // tinylog's ConsoleWriter reads System.out/System.err fresh on every write (not
        // cached at class-init), so swapping both here for the duration of the request
        // captures whatever this call logs regardless of which stream tinylog picked.
        val tee = PrintStream(captured, true)
        System.setOut(tee)
        System.setErr(tee)
        try {
            app(ThrowingBackend()) {
                client.get("/api/me") { header("Authorization", "tma ok:1") }
            }
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        val logged = captured.toString()
        logged shouldContain "exchange-bot: mini app error class=Boom"
        logged shouldNotContain "boom"
        logged shouldNotContain "SECRET-TOKEN"
        logged shouldNotContain "/api/me"
    }
    "membership is cached for the ttl" {
        val clock = object : Clock() {
            var now: Instant = Instant.EPOCH
            override fun instant() = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val calls = AtomicInteger()
        val cache = MembershipCache(MembershipProbe { _, _ -> calls.incrementAndGet(); true }, clock)
        cache.isMember(-1, 1); cache.isMember(-1, 1)
        calls.get() shouldBe 1
        clock.now = clock.now.plusSeconds(61)
        cache.isMember(-1, 1)
        calls.get() shouldBe 2
    }
    "the cache sweeps expired entries once past its bound, so probing unboundedly many chats doesn't grow it forever" {
        val clock = object : Clock() {
            var now: Instant = Instant.EPOCH
            override fun instant() = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
        }
        val cache = MembershipCache(MembershipProbe { _, _ -> true }, clock, maxEntries = 3)
        (1..3).forEach { cache.isMember(-it.toLong(), 1) }
        cache.size shouldBe 3
        clock.now = clock.now.plusSeconds(61)
        cache.isMember(-4, 1)
        cache.isMember(-5, 1)
        cache.size shouldBe 2
    }
    "a prefixed app: unauthenticated /exchange/api/me is 401" {
        app(prefix = "/exchange") {
            client.get("/exchange/api/me").status shouldBe HttpStatusCode.Unauthorized
        }
    }
    "a prefixed app: the bare prefix without a trailing slash redirects (not permanently) to the one with it" {
        app(prefix = "/exchange") {
            val noRedirect = createClient { followRedirects = false }
            val r = noRedirect.get("/exchange")
            r.status shouldBe HttpStatusCode.Found
            r.headers[HttpHeaders.Location] shouldBe "/exchange/"
        }
    }
    "a prefixed app: the prefix root serves index.html, no-cache" {
        app(prefix = "/exchange") {
            val r = client.get("/exchange/")
            r.status shouldBe HttpStatusCode.OK
            r.headers[HttpHeaders.CacheControl] shouldBe "no-cache"
        }
    }
    "a prefixed app: a prefixed asset is long-lived and immutable" {
        app(prefix = "/exchange") {
            val r = client.get("/exchange/assets/x.js")
            r.status shouldBe HttpStatusCode.OK
            r.headers[HttpHeaders.CacheControl] shouldBe "max-age=31536000, immutable"
        }
    }
    "a prefixed app: nothing is served outside the prefix except Ktor's default 404" {
        app(prefix = "/exchange") {
            client.get("/api/me").status shouldBe HttpStatusCode.NotFound
        }
    }
})
