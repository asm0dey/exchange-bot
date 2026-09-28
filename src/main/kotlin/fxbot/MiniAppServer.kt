package fxbot

import io.ktor.http.CacheControl
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val logger = LoggerFactory.getLogger("fxbot.MiniAppServer")

/**
 * `getChatMember` answers, remembered briefly. Memory only: which chats a person is in is
 * never written anywhere (same rule as `telegramMembership`). A failed probe answers false,
 * and that false answer is cached for the TTL exactly like any other answer.
 *
 * Bounded: a looked-up entry found expired is dropped on that same read, and once the map
 * holds more than [maxEntries] entries a sweep drops every expired entry before the new
 * answer is inserted — so a caller who probes an unbounded stream of distinct (chat, user)
 * pairs (e.g. looping `GET /api/chat/{n}` over arbitrary ids) can't grow this map forever.
 * If the sweep still leaves the map over the bound (every entry still live), the whole map
 * is cleared; it costs nothing but fresh probes to refill.
 */
class MembershipCache(
    private val probe: MembershipProbe,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofSeconds(60),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val seen = ConcurrentHashMap<Pair<Long, Long>, Pair<Boolean, Instant>>()

    /** Exposed for tests only — the bound is otherwise an internal memory concern. */
    val size: Int get() = seen.size

    suspend fun isMember(chatId: Long, userId: Long): Boolean {
        val key = chatId to userId
        val now = clock.instant()
        seen[key]?.let { (answer, at) ->
            if (at.plus(ttl).isAfter(now)) return answer
            seen.remove(key)
        }
        val answer = try {
            probe.isMember(chatId, userId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (seen.size > maxEntries) {
            seen.entries.removeIf { (_, v) -> !v.second.plus(ttl).isAfter(now) }
            if (seen.size > maxEntries) seen.clear()
        }
        seen[key] = answer to now
        return answer
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 10_000
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
    // encodeDefaults: MiniAppDto's default fields (e.g. CardDto.counterparties,
    // ChatView.rateStale) must always be sent — api.ts declares them required, and
    // Task 9's card.counterparties.length would throw on an omitted default.
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }

    // Without this, an unhandled exception falls through to Ktor's own default handler
    // (DefaultEnginePipeline.handleFailure), which logs "$status: $method - $path" plus the
    // full stack trace (with its message) at ERROR — and a mini app path can hold a ref
    // token (/api/requests/{token}/cancel) or a chat id (/api/chat/{id}), so that log line
    // would leak them. Installing StatusPages intercepts the exception inside the routing
    // pipeline, so it never propagates out to that default handler at all — nothing further
    // needs to be silenced. Only the exception's class name is logged, never its message,
    // the path, or a stack trace (same rule as everywhere else in this codebase).
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            logger.error("exchange-bot: mini app error class=${cause.javaClass.simpleName}")
            call.respond(HttpStatusCode.InternalServerError, MessageDto("Something went wrong."))
        }
    }

    routing {
        // The jar is built reproducibly, so every `static/` entry carries the same fixed
        // 1980 mtime — Ktor sends that as Last-Modified, with no Cache-Control, and a
        // webview then caches index.html indefinitely. After a redeploy, that stale
        // index.html points at asset filenames the new build no longer ships, so the app
        // fails to load until the cache is somehow cleared. index.html (and "/", which
        // resolves to it) gets `no-cache` so the browser always revalidates; hashed
        // `/assets/**` files get a long-lived immutable cache since a content change always
        // produces a new hashed filename. `CacheControl.MaxAge` can't render the
        // `immutable` extension itself, so that one line is set directly via `modify`.
        staticResources("/", "static") {
            cacheControl { url -> if (url.path.endsWith("/index.html")) listOf(CacheControl.NoCache(null)) else emptyList() }
            modify { url, call ->
                if (url.path.contains("/assets/")) call.response.header(HttpHeaders.CacheControl, "max-age=31536000, immutable")
            }
        }
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
