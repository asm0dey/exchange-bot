package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import eu.vendeli.tgbot.types.component.getUser

private const val PRIVATE_HINT =
    "I introduce people who want to swap currency inside a group chat. Add me to your group to use me."

/** Channels have no per-person sender to match, mention, or authorize. */
private fun ProcessedUpdate.isGroupChat(): Boolean =
    getChat().type == ChatType.Group || getChat().type == ChatType.Supergroup

/** Every handler runs through this: the private-chat reply is bot behaviour, not a
 *  special case of posting. Returns true when the caller should carry on. */
private suspend fun inGroupOrExplain(update: ProcessedUpdate, bot: TelegramBot): Boolean {
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
    if (args.size < 2) {
        message { "Tell me the amount and the currency, like: /sell 1000 EUR" }.send(chat.id, bot)
        return
    }
    when (val result = Registry.service.post(chat.id, user.id, user.username, verb, args[0], args[1])) {
        is PostResult.Rejected -> message { result.reason }.send(chat.id, bot)
        is PostResult.Posted -> {
            val text = renderSuggestions(result.found, result.status)
            val buttons = suggestionButtons(result.request, result.found)
            message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .send(chat.id, bot)
        }
    }
}

@CommandHandler(["/status"])
suspend fun status(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val user = update.getUser()
    message { renderStatus(Registry.requests.resting(chat.id), user.id) }
        .options { parseMode = ParseMode.HTML }
        .send(chat.id, bot)
}

@CommandHandler(["/settings"])
suspend fun settings(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val s = Registry.settings.get(chat.id)
    message {
        "This chat swaps ${s.pair}. Amounts match within ${s.tolerancePct}%, " +
            "and a request waits ${s.tifDays} days before it lapses."
    }.send(chat.id, bot)
}

@CommandHandler(["/help"])
suspend fun help(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    message {
        """
        /sell 1000 EUR — you're handing over 1000 EUR
        /buy 1000 EUR — you want to receive 1000 EUR
        /status — who's waiting in this chat
        /cancel a1 — withdraw your request
        /done a1 @someone — you two swapped
        /reopen — undo your last /done
        /forget — erase what I store about you here
        /settings — this chat's currencies and limits
        """.trimIndent()
    }.send(chat.id, bot)
}
