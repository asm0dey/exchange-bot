package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.core.Activity
import eu.vendeli.tgbot.types.component.ProcessingContext
import eu.vendeli.tgbot.types.component.UpdateType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode

/**
 * Exercises the REAL framework parsing/dispatch pipeline (TelegramBot.update.parse ->
 * .handle -> DefaultParsingInterceptor -> DefaultMatchInterceptor -> DefaultInvokeInterceptor),
 * not a reimplementation of it — see Main.kt's `commandParsing { restrictSpacesInCommands = true }`
 * comment for why this line exists. Without it, `CommandParsingConfiguration`'s own default
 * (`commandDelimiter = '?'`, `restrictSpacesInCommands = false`) makes `parseCommand` treat a
 * space as an ordinary character, so "/probe 1000 EUR" is looked up in the command registry as
 * the literal string "/probe 1000 EUR" — which matches nothing, and the update is dropped with
 * no reply. That is exactly what silenced `/sell` and `/buy` before this fix.
 *
 * A synthetic "/probe" command (never registered by @CommandHandler) is used instead of "/sell"
 * so this test is isolated from Registry/RequestService and never attempts a real network send.
 */

private const val RAW_UPDATE = """
{
  "update_id": 1,
  "message": {
    "message_id": 1,
    "date": 1700000000,
    "chat": {"id": -100, "type": "supergroup"},
    "from": {"id": 1, "is_bot": false, "first_name": "Bob"},
    "text": "/probe 1000 EUR"
  }
}
"""

private class SpyActivity(private val onInvoke: () -> Unit) : Activity {
    override val id = 999_001
    override val qualifier = "fxbot.CommandParsingTest"
    override val function = "probe"
    override suspend fun invoke(context: ProcessingContext): Any? {
        onInvoke()
        return null
    }
}

/** Never dials out: any accidental send would hit this and fail loudly, not hang. */
private fun noNetworkClient() = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

private suspend fun probeReachesHandler(restrictSpaces: Boolean): Boolean {
    val bot = TelegramBot(
        token = "000:fake-token-for-parser-test",
        httpClient = noNetworkClient(),
    ) {
        commandParsing { restrictSpacesInCommands = restrictSpaces }
    }
    var invoked = false
    val activity = SpyActivity { invoked = true }
    bot.update.registry.registerActivity(activity)
    bot.update.registry.registerCommand("/probe", UpdateType.MESSAGE, activity.id)

    val update = bot.update.parse(RAW_UPDATE).await() ?: error("framework failed to parse the synthetic update")
    bot.update.handle(update)
    return invoked
}

class CommandParsingTest : StringSpec({
    "the framework's default command parsing drops a command with a space-separated argument (the Task 9 bug, reproduced against the real parser)" {
        probeReachesHandler(restrictSpaces = false) shouldBe false
    }
    "restrictSpacesInCommands = true makes the real framework split on the space and reach the handler (the fix)" {
        probeReachesHandler(restrictSpaces = true) shouldBe true
    }
})
