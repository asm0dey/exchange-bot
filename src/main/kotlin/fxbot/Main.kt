package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.setMyCommands
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

suspend fun main() {
    val cfg = loadConfig(System::getenv)
    val crypto = Crypto(cfg.dataKeyset, cfg.indexKeyset)
    val ds = createDataSource(cfg)
    migrate(ds)

    // The composition root owns the Exposed Database. Repositories take it rather than
    // registering their own: Exposed's TransactionManager keeps a static registry keyed by
    // Database, nothing unregisters, and a bare `transaction { }` would bind to whichever
    // registered first. Every call site passes its Database explicitly for the same reason.
    val db = connectExposed(ds)

    Registry.requests = RequestRepository(ds, crypto, db = db)
    Registry.settings = ChatSettingsRepository(ds, crypto, db = db)
    Registry.rates = RateService(RateClient(HttpClient(CIO)), RateRepository(ds, db = db))
    Registry.service = RequestService(Registry.requests, Registry.settings, Registry.rates)

    val bot = TelegramBot(cfg.botToken) {
        updatesListener { updatesPollingTimeout = 30 }
        httpClient {
            requestTimeoutMillis = 45_000L
            maxRequestRetry = 3
            retryDelay = 2_000L
            retryStrategy = retryOnTooManyRequests()
        }
    }

    setMyCommands {
        botCommand("sell", "Offer currency you're handing over")
        botCommand("buy", "Ask for currency you want to receive")
        botCommand("status", "Who's waiting in this chat")
        botCommand("cancel", "Withdraw one of your requests")
        botCommand("done", "Mark a swap as completed")
        botCommand("reopen", "Undo your last /done")
        botCommand("forget", "Erase what I store about you here")
        botCommand("settings", "This chat's currencies and limits")
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
