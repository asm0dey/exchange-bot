package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.setMyCommands
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

suspend fun main(): Unit = coroutineScope {
    val cfg = loadConfig(System::getenv)
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
        // Later tasks add their own entries as their handlers land. The menu must never
        // advertise a command that does nothing when tapped.
    }.send(bot)

    println("exchange-bot: listening")

    while (true) {
        try {
            bot.handleUpdates()
        } catch (e: Exception) {
            System.err.println("exchange-bot: listener error (${e.javaClass.simpleName}); restarting in 5s")
            runCatching { bot.update.stopListener() }
            delay(5.seconds)
        }
    }
}
