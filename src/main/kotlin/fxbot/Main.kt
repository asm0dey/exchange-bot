package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.getMe
import eu.vendeli.tgbot.api.botactions.setMyCommands
import eu.vendeli.tgbot.types.component.UpdateType
import eu.vendeli.tgbot.types.component.isSuccess
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

private val logger = LoggerFactory.getLogger("fxbot.Main")

suspend fun main(): Unit = coroutineScope {
    val cfg = loadConfig(System::getenv)
    // Config shape, never values: whether DB_PATH was left at its default or overridden.
    // Every other field is a required secret (see Config.toString's redaction) and has no
    // "shape" worth reporting beyond "present", which loadConfig already enforces by throwing.
    logger.info("exchange-bot starting: dbPath=" + if (cfg.dbPath == DEFAULT_DB_PATH) "default" else "custom")
    val crypto = Crypto(cfg.dataKeyset, cfg.indexKeyset)
    val ds = createDataSource(cfg)
    migrate(ds)

    // The composition root owns the Exposed Database. Repositories take it rather than
    // registering their own: Exposed's TransactionManager keeps a static registry keyed by
    // Database, nothing unregisters, and a bare `transaction { }` would bind to whichever
    // registered first. Every call site passes its Database explicitly for the same reason.
    val db = connectExposed(ds)

    // Shared across every RateClient consumer — it's a stateless wrapper over one GET,
    // so there's no reason to pay for a second connection pool.
    val rateClient = RateClient(HttpClient(CIO))

    Registry.requests = RequestRepository(ds, crypto, db = db)
    Registry.settings = ChatSettingsRepository(ds, crypto, db = db)
    Registry.rates = RateService(rateClient, RateRepository(ds, db = db))
    Registry.service = RequestService(Registry.requests, Registry.settings, Registry.rates)
    Registry.lifecycle = LifecycleService(Registry.requests)
    Registry.messages = MessageLogRepository(ds, crypto, db = db)
    Registry.buttons = ButtonService(Registry.messages)
    Registry.forget = ForgetService(Registry.requests, Registry.messages)
    Registry.admin = AdminService(Registry.settings, rateClient)
    Registry.migration = ChatMigrationService(Registry.requests, Registry.settings, Registry.messages, db)

    val housekeeping = Housekeeping(Registry.requests, Registry.settings, Registry.rates, Registry.messages)
    startScheduler(ds, housekeeping)
    // Warm the rate cache without waiting a day for the scheduler's first run — but never
    // gate startup on it. A slow feed must not stop the bot from listening, and ktor
    // delivers a request timeout as a CancellationException, which RateClient rethrows.
    launch { runCatching { housekeeping.refreshRates() } }

    val bot = TelegramBot(cfg.botToken) {
        // Without this the parser never breaks on a space: "/sell 1000 EUR" is taken as
        // the whole command name, matches nothing in the registry, and the update is
        // dropped silently. The framework's native style is "/sell?a=1000&c=EUR", which
        // is not what this bot documents.
        commandParsing { restrictSpacesInCommands = true }
        updatesListener { updatesPollingTimeout = 30 }
        httpClient {
            requestTimeoutMillis = 45_000L
            maxRequestRetry = 3
            retryDelay = 2_000L
            retryStrategy = retryOnTooManyRequests()
        }
    }

    // A revoked, mistyped, or whitespace-mangled token is the overwhelmingly common startup
    // failure, and it must surface here, where an operator watching a deployment sees it —
    // not later, silently, inside the polling loop below. See `validateBotToken`'s doc comment
    // for why this checks a Response rather than relying on a throw.
    when (val validation = validateBotToken(bot)) {
        is TokenValidation.Rejected -> {
            // NEVER log the response body or an exception message here — see the restart-loop
            // comment below for why (the same request-URL-embeds-the-token reasoning applies).
            logger.error("exchange-bot: Telegram rejected the bot token at startup; exiting so the orchestrator notices")
            exitProcess(1)
        }
        is TokenValidation.Unknown -> logger.warn(
            "exchange-bot: could not reach Telegram to validate the bot token at startup " +
                "(${validation.causeClass}); proceeding — this says nothing about the token",
        )
        TokenValidation.Valid -> {}
    }

    setMyCommands {
        botCommand("sell", "Hand over currency you have")
        botCommand("buy", "Ask for currency you want to receive")
        botCommand("status", "Who's waiting in this chat")
        botCommand("cancel", "Withdraw your request")
        botCommand("done", "Mark a swap done")
        botCommand("reopen", "Undo your last done")
        botCommand("settings", "This chat's currencies and limits")
        botCommand("pair", "Admins: change what this chat swaps")
        botCommand("tolerance", "Admins: how close amounts must be to match")
        botCommand("tif", "Admins: how many days a request waits")
        botCommand("forget", "Erase your data — add 'all' in a private chat for every group")
        botCommand("help", "What I can do")
    }.send(bot)

    logger.info("exchange-bot: listening")

    // This bounds genuine thrown exceptions from the polling loop: a SerializationException
    // on a malformed payload, an HttpRequestTimeoutException, or (if throwExOnActionsFailure
    // is ever flipped to true) a TgFailureException — the Fatal conditions
    // TgUpdateHandler.classify promotes to a throw. It does NOT bound a revoked or mistyped
    // bot token: getUpdates fails the exact same way getMe does (see the startup check
    // above) — as a Response.Failure, not a thrown exception, because throwExOnActionsFailure
    // defaults to false and is never overridden in this codebase — so classify's
    // ClientRequestException/401/403 branch is unreachable here under this bot's actual
    // configuration. Retrying an unthrown failure forever, silently, is exactly how a dead
    // bot goes unnoticed; that risk is why the token is validated once at startup instead
    // (see above), not by this loop. This loop still earns its place for the exceptions it
    // does bound — count consecutive failures and give up loudly on those.
    var consecutiveFailures = 0
    while (true) {
        val sessionStart = System.nanoTime()
        try {
            // MESSAGE and CALLBACK_QUERY are the only update kinds this bot has handlers for
            // (see Commands.kt, LifecycleCommands.kt, AdminCommands.kt, ChatMigration.kt for
            // messages; Callbacks.kt for callback queries) — restricting to them is exposure
            // reduction against whatever other update kinds the library parses.
            bot.handleUpdates(listOf(UpdateType.MESSAGE, UpdateType.CALLBACK_QUERY))
        } catch (e: Exception) {
            // A session that ran at least one full long-poll cycle (30s, set above) almost
            // certainly completed at least one successful poll before this failure, so it
            // isn't part of a "no successful poll at all" streak — reset instead of counting
            // it toward the giving-up threshold.
            if ((System.nanoTime() - sessionStart) >= HEALTHY_SESSION_NANOS) consecutiveFailures = 0
            consecutiveFailures++

            // NEVER log e.message (or any cause's message) here. Every request this bot makes
            // goes to https://api.telegram.org/bot<TOKEN>/..., so any exception whose message
            // embeds the request URL (a ClientRequestException, if one ever does reach this
            // catch — see the comment above on why it normally doesn't) would put the bot
            // token in stderr and the container log if printed. Class names carry enough
            // signal to diagnose without ever touching a message. The next person tempted to
            // "improve" this by adding e.message: don't — see above.
            val diagnosis = generateSequence(e as Throwable) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName }
            logger.warn("exchange-bot: listener error ($diagnosis); failure $consecutiveFailures/$MAX_CONSECUTIVE_FAILURES")
            runCatching { bot.update.stopListener() }

            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                logger.error(
                    "exchange-bot: giving up after $consecutiveFailures consecutive failures with no " +
                        "successful poll; exiting so the orchestrator notices",
                )
                exitProcess(1)
            }
            delay(RESTART_DELAY.seconds)
        }
    }
}

private const val MAX_CONSECUTIVE_FAILURES = 5
private const val RESTART_DELAY = 5L

// Slightly above the 30s updatesPollingTimeout configured above — see the comment at the
// catch site for why this is the "did at least one poll probably succeed" threshold.
private val HEALTHY_SESSION_NANOS = 35.seconds.inWholeNanoseconds

/** Outcome of [validateBotToken] — three-way, not a Boolean, because "could not tell" is a
 * real third state and must never be conflated with "confirmed rejected" (see below). */
internal sealed interface TokenValidation {
    data object Valid : TokenValidation
    data object Rejected : TokenValidation
    data class Unknown(val causeClass: String) : TokenValidation
}

/**
 * Calls `getMe` once and classifies the result. Checks `.isSuccess()` on the returned
 * `Response`, not a thrown exception: `throwExOnActionsFailure` defaults to false and is
 * never overridden in this codebase, so a Telegram-side rejection (401/403 for a bad token)
 * comes back as `Response.Failure`, never a throw — the exact assumption every other send
 * site here already makes (see `AdminCommands.isAdmin`). A bare `send()` cannot do this job:
 * it never surfaces a Telegram-side failure at all.
 *
 * An exception thrown from the call itself is a different, genuinely ambiguous case: the
 * request never reached Telegram (DNS not up yet, connection refused, connect timeout), so
 * it says nothing about the token one way or the other — it is [TokenValidation.Unknown],
 * never [TokenValidation.Rejected]. Treating it as a rejection would turn an ordinary
 * few-second network blip at startup into a crash loop.
 */
internal suspend fun validateBotToken(bot: TelegramBot): TokenValidation = try {
    val identity = getMe().sendReturning(bot).await()
    if (identity.isSuccess()) TokenValidation.Valid else TokenValidation.Rejected
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    TokenValidation.Unknown(e.javaClass.simpleName)
}
