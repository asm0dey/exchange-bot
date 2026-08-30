package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.deleteMessage
import eu.vendeli.tgbot.api.message.editMessageText
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.chat.ChatType
import eu.vendeli.tgbot.types.component.MessageUpdate
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import eu.vendeli.tgbot.types.component.getUser
import eu.vendeli.tgbot.types.component.isSuccess
import eu.vendeli.tgbot.types.msg.EntityType

/**
 * Command-path counterpart of [respond]'s Ok branch in Callbacks.kt: same HTML parse
 * mode (the text may carry a `mention(...)` link/@name), same Reopen button when a
 * request just closed, same button-cleanup on the messages that suggested it.
 */
private suspend fun replyToClose(chatId: Long, bot: TelegramBot, result: ActionResult) {
    val reply = message { result.text }.options { parseMode = ParseMode.HTML }
    if (result is ActionResult.Ok && result.closedTokens.isNotEmpty()) {
        reply.inlineKeyboardMarkup { "↩️ Reopen" callback Cb.reopen(result.closedTokens.first()) }
            .send(chatId, bot)
        Registry.buttons.stripFor(result.closedTokens, chatId, bot)
    } else {
        reply.send(chatId, bot)
    }
}

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
    replyToClose(chat.id, bot, Registry.lifecycle.cancel(chat.id, user.id, shortId))
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
    replyToClose(chat.id, bot, Registry.lifecycle.doneByShortId(chat.id, user.id, shortId, peerId))
}

private const val REDACTED = "(a message was edited at someone's request)"

/**
 * Erases a person's data. `/forget all` (global, every chat) is accepted only in
 * a private chat with the bot — from a group it would silently reach into the
 * caller's other groups, which nobody watching that group could see happen.
 * Plain `/forget` is the per-chat form and follows the usual group-only guard.
 * The two forms therefore can't share [inGroupOrExplain] unchanged: the global
 * form must work in exactly the place that guard rejects.
 */
@CommandHandler(["/forget"])
suspend fun forget(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val global = update.text.trim().split(Regex("\\s+")).getOrNull(1)?.lowercase() == "all"

    if (global) {
        if (chat.type != ChatType.Private) {
            message {
                "Send /forget all to me in a private chat — from here I'd be reaching into your other groups."
            }.send(chat.id, bot)
            return
        }
    } else if (!inGroupOrExplain(update, bot)) {
        return
    }

    val plan = Registry.forget.plan(user.id, if (global) null else chat.id)

    // Best-effort, and counted for real: a message already gone (too old to delete,
    // removed by a moderator, chat no longer reachable) must not stop the rest of the
    // cleanup, and must not be counted as tidied in the confirmation below — Telegram
    // reports per-call success via `Response`, not by throwing, so `sendReturning` +
    // `isSuccess()` is what tells attempts and successes apart; a plain `send()` here
    // would count every attempt as a success regardless of what Telegram actually did.
    var deletedMessages = 0
    for (m in plan.toDelete) {
        val ok = runCatching { deleteMessage(m.messageId).sendReturning(m.chatId, bot).await().isSuccess() }
            .getOrDefault(false)
        if (ok) deletedMessages++
    }
    var redactedMessages = 0
    for (m in plan.toRedact) {
        val ok = runCatching { editMessageText(m.messageId) { REDACTED }.sendReturning(m.chatId, bot).await().isSuccess() }
            .getOrDefault(false)
        if (ok) redactedMessages++
    }

    val touched = deletedMessages + redactedMessages
    message {
        "Erased ${plan.deletedRequests} request(s) and tidied $touched message(s). " +
            "I can't unsay what was already said, and I can't touch other people's messages."
    }.send(chat.id, bot)
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
