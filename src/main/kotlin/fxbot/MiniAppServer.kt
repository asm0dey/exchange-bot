package fxbot

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * `getChatMember` answers, remembered briefly. Memory only: which chats a person is in is
 * never written anywhere (same rule as `telegramMembership`). A failed probe answers false.
 */
class MembershipCache(
    private val probe: MembershipProbe,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofSeconds(60),
) {
    private val seen = ConcurrentHashMap<Pair<Long, Long>, Pair<Boolean, Instant>>()

    suspend fun isMember(chatId: Long, userId: Long): Boolean {
        val key = chatId to userId
        val now = clock.instant()
        seen[key]?.let { (answer, at) -> if (at.plus(ttl).isAfter(now)) return answer }
        val answer = try {
            probe.isMember(chatId, userId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        seen[key] = answer to now
        return answer
    }
}

/** The ONLY way a handler learns who is calling. */
private suspend fun RoutingContext.viewer(verify: (String) -> Viewer?): Viewer? {
    val v = call.request.headers["Authorization"]?.removePrefix("tma ")?.let(verify)
    if (v == null) call.respond(HttpStatusCode.Unauthorized, MessageDto("Reopen from Telegram."))
    return v
}

/** Groups only (negative ids), and only for their members. */
private suspend fun RoutingContext.member(v: Viewer, membership: MembershipCache): Long? {
    val chatId = call.parameters["id"]?.toLongOrNull()?.takeIf { it < 0 }
    if (chatId == null || !membership.isMember(chatId, v.userId)) {
        call.respond(HttpStatusCode.Forbidden, MessageDto("You're not in that chat."))
        return null
    }
    return chatId
}

private suspend inline fun <reified T : Any> RoutingContext.body(): T? =
    runCatching { call.receive<T>() }.getOrNull().also {
        if (it == null) call.respond(HttpStatusCode.BadRequest, MessageDto("That request was malformed."))
    }

private suspend inline fun <reified T : Any> RoutingContext.reply(r: ApiResult<T>) = when (r) {
    is ApiResult.Ok -> call.respond(r.value)
    is ApiResult.Refused -> call.respond(HttpStatusCode.UnprocessableEntity, MessageDto(r.message))
}

fun Application.miniApp(verify: (String) -> Viewer?, backend: MiniAppBackend, membership: MembershipCache) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }

    routing {
        staticResources("/", "static")
        route("/api") {
            get("/chat/{id}") {
                val v = viewer(verify) ?: return@get
                val c = member(v, membership) ?: return@get
                reply(backend.chat(v, c))
            }
            post("/chat/{id}/requests") {
                val v = viewer(verify) ?: return@post
                val c = member(v, membership) ?: return@post
                val b = body<NewRequestBody>() ?: return@post
                reply(backend.postInChat(v, c, b))
            }
            get("/me") { val v = viewer(verify) ?: return@get; reply(backend.me(v)) }
            get("/browse") { val v = viewer(verify) ?: return@get; reply(backend.browse(v)) }
            post("/interests") {
                val v = viewer(verify) ?: return@post
                val b = body<NewInterestBody>() ?: return@post
                reply(backend.state(v, b))
            }
            post("/requests/{token}/cancel") {
                val v = viewer(verify) ?: return@post
                reply(backend.cancel(v, call.parameters["token"].orEmpty()))
            }
            post("/done") {
                val v = viewer(verify) ?: return@post
                val b = body<DoneBody>() ?: return@post
                reply(backend.done(v, b))
            }
            post("/confirm") {
                val v = viewer(verify) ?: return@post
                val b = body<AnswerBody>() ?: return@post
                reply(backend.confirm(v, b))
            }
            post("/refuse") {
                val v = viewer(verify) ?: return@post
                val b = body<AnswerBody>() ?: return@post
                reply(backend.refuse(v, b))
            }
            put("/me/tolerance") {
                val v = viewer(verify) ?: return@put
                val b = body<ToleranceBody>() ?: return@put
                reply(backend.setTolerance(v, b.pct))
            }
        }
    }
}

/** Started from `Main` only when MINIAPP_URL is set. */
fun startMiniApp(port: Int, verify: (String) -> Viewer?, backend: MiniAppBackend, membership: MembershipCache) =
    embeddedServer(CIO, port = port) { miniApp(verify, backend, membership) }.start(wait = false)
