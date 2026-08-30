package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import eu.vendeli.tgbot.types.component.getOrNull
import eu.vendeli.tgbot.types.component.getUser
import org.slf4j.LoggerFactory

private const val PRIVATE_HINT =
    "I introduce people who want to swap currency inside a group chat. Add me to your group to use me."

private val cmdLogger = LoggerFactory.getLogger("fxbot.commands")

/** The only shape a command-surface log line may take: the command name (fixed, from the
 *  handler registry, never user input) and a fixed outcome label — never who sent it, what
 *  chat it was sent in, or what they typed. Internal so every command file shares it. */
internal fun logCommand(command: String, outcome: String) = cmdLogger.debug("command=$command outcome=$outcome")

/** Channels have no per-person sender to match, mention, or authorize. */
private fun ProcessedUpdate.isGroupChat(): Boolean =
    getChat().type == ChatType.Group || getChat().type == ChatType.Supergroup

/** Every handler runs through this: the private-chat reply is bot behaviour, not a
 *  special case of posting. Returns true when the caller should carry on.
 *  Internal (not private) so every command file in this package shares one guard. */
internal suspend fun inGroupOrExplain(update: ProcessedUpdate, bot: TelegramBot): Boolean {
    if (update.isGroupChat()) return true
    message { PRIVATE_HINT }.send(update.getChat().id, bot)
    return false
}

@CommandHandler(["/sell"])
suspend fun sell(update: ProcessedUpdate, bot: TelegramBot) = handlePost(Verb.SELL, update, bot)

@CommandHandler(["/buy"])
suspend fun buy(update: ProcessedUpdate, bot: TelegramBot) = handlePost(Verb.BUY, update, bot)

private suspend fun handlePost(verb: Verb, update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val user = update.getUser()
    val args = update.text.trim().split(Regex("\\s+")).drop(1)
    val command = verb.name.lowercase()
    if (args.size < 2) {
        logCommand(command, "missing_args")
        message { "Tell me the amount and the currency, like: /sell 1000 EUR" }.send(chat.id, bot)
        return
    }
    when (val result = Registry.service.post(chat.id, user.id, user.username, verb, args[0], args[1])) {
        is PostResult.Rejected -> {
            logCommand(command, "rejected")
            message { result.reason }.send(chat.id, bot)
        }
        is PostResult.Posted -> {
            logCommand(command, "posted")
            val text = renderSuggestions(result.found, result.status)
            val buttons = suggestionButtons(result.request, result.found)
            val sent = message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .sendReturning(chat.id, bot)
                .getOrNull()
            // The buttons on this message name the poster's own request plus every
            // counterparty's — record all of them, so a later close on ANY of those
            // requests knows to strip this message's keyboard too.
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    chat.id,
                    id,
                    listOf(result.request.refToken) + result.found.map { it.request.refToken },
                    listOf(result.request.userId) + result.found.map { it.request.userId },
                )
            }
        }
    }
}

@CommandHandler(["/status"])
suspend fun status(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val user = update.getUser()
    logCommand("status", "shown")
    message { renderStatus(Registry.requests.resting(chat.id), user.id) }
        .options { parseMode = ParseMode.HTML }
        .send(chat.id, bot)
}

@CommandHandler(["/settings"])
suspend fun settings(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val s = Registry.settings.get(chat.id)
    logCommand("settings", "shown")
    message {
        "This chat swaps ${s.pair}. Amounts match within ${s.tolerancePct}%, " +
            "and a request waits ${s.tifDays} days before it lapses. " +
            "Admins can change this with /pair, /tolerance and /tif."
    }.send(chat.id, bot)
}

private val HELP_TEXT = """
    /sell 1000 EUR — you're handing over 1000 EUR
    /buy 1000 EUR — you want to receive 1000 EUR
    /status — who's waiting in this chat
    /cancel a1 — withdraw your request
    /done a1 @someone — you two swapped
    /reopen — undo your last /done
    /settings — this chat's currencies and limits
    /pair EUR RUB — admins: change what this chat swaps
    /tolerance 20 — admins: how close amounts must be to match
    /tif 7 — admins: how many days a request waits before it lapses
    /forget — erase your data in this chat (send /forget all to me privately for every chat)
""".trimIndent()

@CommandHandler(["/help"])
suspend fun help(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    logCommand("help", "shown")
    message { HELP_TEXT }.send(update.getChat().id, bot)
}

/**
 * The spec promises this reply; without it, opening a DM and tapping Start gets
 * silence. Mirrors [inGroupOrExplain]'s split but inverted — a private chat gets
 * the "add me to a group" hint, a group gets straight to [HELP_TEXT], since a
 * `/start` in a group is not asking to be told what a group chat is for.
 */
@CommandHandler(["/start"])
suspend fun start(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    logCommand("start", "shown")
    message { if (update.isGroupChat()) HELP_TEXT else PRIVATE_HINT }.send(chat.id, bot)
}
