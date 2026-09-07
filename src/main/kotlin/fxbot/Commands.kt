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
    "That one is for a group chat's admins. Add me to your group and use it there."

private val cmdLogger = LoggerFactory.getLogger("fxbot.commands")

/** The only shape a command-surface log line may take: the command name (fixed, from the
 *  handler registry, never user input) and a fixed outcome label — never who sent it, what
 *  chat it was sent in, or what they typed. Internal so every command file shares it. */
internal fun logCommand(command: String, outcome: String) = cmdLogger.debug("command=$command outcome=$outcome")

/** Groups have a per-person sender to match, mention, and authorize; channels do not. */
internal fun ProcessedUpdate.isGroupChat(): Boolean =
    getChat().type == ChatType.Group || getChat().type == ChatType.Supergroup

/**
 * Kept for `/pair` and `/tif` only. Every other command now has a private meaning: a
 * person states an interest to the bot privately and it is shown in the chats they share
 * with it, so a blanket "add me to a group" refusal would refuse the whole point.
 * Internal (not private) so every command file in this package shares one guard.
 */
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
    if (!update.isGroupChat()) return handlePrivatePost(verb, update, bot)
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
                    text,
                    buttons,
                )
            }
        }
    }
}

@CommandHandler(["/status"])
suspend fun status(update: ProcessedUpdate, bot: TelegramBot) {
    if (!update.isGroupChat()) return privateStatus(update, bot)
    val chat = update.getChat()
    val user = update.getUser()
    logCommand("status", "shown")
    message { renderStatus(Registry.requests.resting(chat.id), user.id) }
        .options { parseMode = ParseMode.HTML }
        .send(chat.id, bot)
}

@CommandHandler(["/settings"])
suspend fun settings(update: ProcessedUpdate, bot: TelegramBot) {
    if (!update.isGroupChat()) return privateSettings(update, bot)
    val chat = update.getChat()
    val s = Registry.settings.get(chat.id)
    logCommand("settings", "shown")
    message {
        "This chat swaps ${s.pair}. A counterparty matches when what they'd leave you is within " +
            "${s.tolerancePct}% of your own amount, and a request waits ${s.tifDays} days before it lapses. " +
            (if (s.fanOut) "I also show interests people state to me privately here. "
             else "I don't show interests people state to me privately here. ") +
            "Admins can change this with /pair, /tolerance, /tif and /fanout."
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
    /fanout on — admins: whether I show interests stated to me privately here
    /forget — erase your data in this chat (send /forget all to me privately for every chat)
""".trimIndent()

@CommandHandler(["/help"])
suspend fun help(update: ProcessedUpdate, bot: TelegramBot) {
    logCommand("help", "shown")
    message { if (update.isGroupChat()) HELP_TEXT else PRIVATE_HELP_TEXT }.send(update.getChat().id, bot)
}

/**
 * The spec promises this reply; without it, opening a DM and tapping Start gets
 * silence. Each place gets the help for what it can actually do: a private chat can now
 * state interests, so the old "add me to a group" hint would be the wrong answer there.
 */
@CommandHandler(["/start"])
suspend fun start(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    logCommand("start", "shown")
    message { if (update.isGroupChat()) HELP_TEXT else PRIVATE_HELP_TEXT }.send(chat.id, bot)
}
