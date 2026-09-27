package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** Accepts "ok:<userId>" as initData, so route tests don't re-test the HMAC (Task 1 does). */
private val verify: (String) -> Viewer? = { raw -> raw.removePrefix("ok:").toLongOrNull()?.let { Viewer(it, "u$it", null) } }

private class FakeBackend : MiniAppBackend {
    var lastPostChat: Long? = null
    override suspend fun chat(viewer: Viewer, chatId: Long) = ApiResult.Ok(ChatView(chatId, "G", "EUR", "RUB", "94.12", false, emptyList()))
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

class MiniAppServerTest : StringSpec({
    val members = setOf(-1001L to 1L)
    val probeCalls = AtomicInteger()
    val probe = MembershipProbe { c, u -> probeCalls.incrementAndGet(); (c to u) in members }

    fun app(backend: FakeBackend = FakeBackend(), block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { miniApp(verify, backend, MembershipCache(probe)) }
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
})
