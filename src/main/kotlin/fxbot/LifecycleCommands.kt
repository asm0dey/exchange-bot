package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import eu.vendeli.tgbot.types.component.getUser
import eu.vendeli.tgbot.types.msg.EntityType

@CommandHandler(["/cancel"])
suspend fun cancel(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val user = update.getUser()
    val shortId = update.text.trim().split(Regex("\\s+")).getOrNull(1)
    if (shortId == null) {
        message { "Which one? Try /cancel a1 — /status lists them." }.send(chat.id, bot)
        return
    }
    message { Registry.lifecycle.cancel(chat.id, user.id, shortId).text }.send(chat.id, bot)
}

@CommandHandler(["/reopen"])
suspend fun reopen(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val tif = Registry.settings.get(chat.id).tifDays
    message { Registry.lifecycle.reopen(chat.id, update.getUser().id, tif).text }.send(chat.id, bot)
}

@CommandHandler(["/done"])
suspend fun done(update: ProcessedUpdate, bot: TelegramBot) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val user = update.getUser()
    val parts = update.text.trim().split(Regex("\\s+"))
    val shortId = parts.getOrNull(1)
    if (shortId == null) {
        message { "Which one? Try /done a1 @someone" }.send(chat.id, bot)
        return
    }
    val peerId = resolvePeer(update, chat.id)
    message { Registry.lifecycle.doneByShortId(chat.id, user.id, shortId, peerId).text }.send(chat.id, bot)
}

/**
 * Counterparties come from message entities (a reply, or a Telegram-recognized
 * @mention), never from a typed display name matched by hand, and only from
 * people who actually have something waiting here.
 */
private fun resolvePeer(update: ProcessedUpdate, chatId: Long): Long? {
    val message = (update as? MessageUpdate)?.message ?: return null
    message.replyToMessage?.from?.id?.let { return it }
    val entities = message.entities.orEmpty()
    entities.firstOrNull { it.user != null }?.user?.id?.let { return it }
    val mentioned = entities.firstOrNull { it.type == EntityType.Mention }
        ?.let { message.text?.substring(it.offset + 1, it.offset + it.length) }
        ?: return null
    return Registry.requests.resting(chatId).firstOrNull { it.username.equals(mentioned, ignoreCase = true) }?.userId
}
