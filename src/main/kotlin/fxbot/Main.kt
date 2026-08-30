package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.setMyCommands
import eu.vendeli.tgbot.types.component.UpdateType
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

    // A revoked/mistyped token, or any other Fatal condition the library's polling loop
    // classifies (see TgUpdateHandler.classify — HttpRequestTimeoutException,
    // SerializationException, TgFailureException, and every ClientRequestException
    // including 401/403), makes handleUpdates() throw instead of looping forever. Retrying
    // that forever, silently, is exactly how a dead bot goes unnoticed: no orchestrator or
    // human ever learns. Count consecutive failures and give up loudly instead.
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

            // NEVER log e.message (or any cause's message) here. For a rejected-token
            // failure this is a ClientRequestException whose message embeds the full request
            // URL — and this bot's requests go to https://api.telegram.org/bot<TOKEN>/...,
            // so printing it would put the bot token in stderr and the container log. Class
            // names carry enough signal to diagnose (a ClientRequestException chain here
            // means Telegram rejected the request, most likely 401/403 = revoked/bad token)
            // without ever touching a message. The next person tempted to "improve" this by
            // adding e.message: don't — see above.
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
