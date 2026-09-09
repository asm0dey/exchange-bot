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
import eu.vendeli.tgbot.types.component.getOrNull
import eu.vendeli.tgbot.types.component.getUser
import eu.vendeli.tgbot.types.component.isSuccess
import eu.vendeli.tgbot.types.msg.EntityType

/**
 * Command-path counterpart of [respond]'s Ok branch in Callbacks.kt: same HTML parse
 * mode (the text may carry a `mention(...)` link/@name), same [decisionButtons] when a
 * request just closed, same rewrite of the messages that carried it.
 *
 * [undoable] is false for `/reopen`: its touched requests are RESTING again, so there is
 * nothing to undo — a Reopen button on them could only ever answer "already waiting".
 * The rewrite still runs, and is the point: it takes the "withdrawn"/"done" line back off
 * the messages that carried them.
 *
 * HTML parse mode is applied to [ActionResult.Ok] and [ActionResult.Asked] text — the two
 * branches that embed a [mention] — and to nothing else. `Denied`/`Gone` text can carry raw
 * user input (e.g. a short id typed by the caller) that was never meant to be parsed as
 * markup: sending it under `ParseMode.HTML` risks a Telegram entity-parse rejection
 * (silently swallowed — the reply never arrives) or, worse, an attacker-authored tag
 * rendering as a live link.
 */
private suspend fun replyToDecision(
    chatId: Long,
    bot: TelegramBot,
    result: ActionResult,
    undoable: Boolean = true,
) {
    when (result) {
        is ActionResult.Asked -> {
            // HTML: the text names the counterparty via `mention(...)`.
            message { result.text }.options { parseMode = ParseMode.HTML }.send(chatId, bot)
            sendAsk(result, bot)
            return
        }
        is ActionResult.Choose -> {
            sendChoice(chatId, result, bot)
            return
        }
        else -> {}
    }
    val reply = message { result.text }.let {
        if (result is ActionResult.Ok) it.options { parseMode = ParseMode.HTML } else it
    }
    if (result !is ActionResult.Ok || result.touchedTokens.isEmpty()) {
        reply.send(chatId, bot)
        return
    }
    if (undoable) {
        reply.inlineKeyboardMarkup { decisionButtons(result).forEach { b -> b.label callback b.data; br() } }
            .send(chatId, bot)
    } else {
        reply.send(chatId, bot)
    }
    Registry.buttons.refreshFor(result.touchedTokens, bot)
    result.notify?.let { sendNotice(it, bot) }
}

/**
 * The question, where the counterparty spoke. Recorded against BOTH people, because it
 * names the declarer and a `/forget` from either side must reach it (ADR 0005).
 */
internal suspend fun sendAsk(r: ActionResult.Asked, bot: TelegramBot) {
    val buttons = askButtons(r)
    val sent = message { r.question }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .sendReturning(r.peerChatId, bot)
        .getOrNull()
    sent?.messageId?.let { id ->
        Registry.messages.record(
            r.peerChatId, id,
            listOf(r.myToken, r.peerToken),
            listOf(r.declarerUserId, r.peerUserId),
            r.question, buttons,
        )
    }
}

/** The other side of a confirmed done, told where THEY spoke rather than where the press happened. */
internal suspend fun sendNotice(n: Notice, bot: TelegramBot) {
    val buttons = noticeButtons(n)
    message { n.text }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .send(n.chatId, bot)
}

/** Nobody is asked and nothing closes — the declarer just picks. */
internal suspend fun sendChoice(chatId: Long, r: ActionResult.Choose, bot: TelegramBot) {
    val book = nameBookFor(r.candidates, Registry.names)
    val buttons = chooseButtons(r, book)
    message { r.text }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .send(chatId, bot)
}

/**
 * Privately, the short ids on offer are the ones the person's own interests carry in the bot —
 * [NO_CHAT_ID] is where those rest, and withdrawing one withdraws
 * every showing with it. In a group it is that chat's own short ids, as before.
 */
private fun ProcessedUpdate.scopeId(): Long = if (isGroupChat()) getChat().id else NO_CHAT_ID

@CommandHandler(["/cancel"])
suspend fun cancel(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val shortId = update.text.trim().split(Regex("\\s+")).getOrNull(1)
    if (shortId == null) {
        logCommand("cancel", "missing_args")
        message { "Which one? Try /cancel a1 — /status lists them." }.send(chat.id, bot)
        return
    }
    val result = Registry.lifecycle.cancel(update.scopeId(), user.id, shortId)
    logCommand("cancel", result.outcomeLabel())
    replyToDecision(chat.id, bot, result)
}

/**
 * Privately this brings back the interest that closed most recently, every showing with
 * it — the recovery a wrongly closed interest otherwise has no command for, since the Undo
 * button only ever reaches whoever pressed, who may not be the person whose interest was
 * closed. The time in force passed here is the bot default and is not what the
 * showings come back on: [LifecycleService.reopen] gives each one its own chat's number.
 */
@CommandHandler(["/reopen"])
suspend fun reopen(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val scopeId = update.scopeId()
    val tif = if (scopeId == NO_CHAT_ID) NO_CHAT_TIF_DAYS else Registry.settings.get(chat.id).tifDays
    val result = Registry.lifecycle.reopen(scopeId, update.getUser().id, tif)
    logCommand("reopen", result.outcomeLabel())
    replyToDecision(chat.id, bot, result, undoable = false)
}

@CommandHandler(["/done"])
suspend fun done(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val parts = update.text.trim().split(Regex("\\s+"))
    val shortId = parts.getOrNull(1)
    if (shortId == null) {
        logCommand("done", "missing_args")
        message { "Which one? Try /done a1 @someone" }.send(chat.id, bot)
        return
    }
    val scopeId = update.scopeId()
    val result = Registry.lifecycle.doneByShortId(scopeId, user.id, shortId, resolvePeer(update, scopeId))
    logCommand("done", result.outcomeLabel())
    replyToDecision(chat.id, bot, result)
}

private const val REDACTED = "(a message was edited at someone's request)"

/**
 * Erases a person's data, in one of three shapes:
 *  - in a group, that group alone — which may remove one showing of an interest whose
 *    siblings live on, because forgetting removes a RECORD, it does not withdraw an interest;
 *  - privately and plain, the person's own side of the bot: their bot-side requests, their
 *    size tolerance, their give-up consents, their pending announcements, and the messages
 *    in that private chat (a completed give-up named somebody there, so it is redacted
 *    rather than deleted — ADR 0005);
 *  - privately with `all`, the above and every group as well.
 *
 * `/forget all` is accepted only in a private chat with the bot — from a group it would
 * silently reach into the caller's other groups, which nobody watching that group could
 * see happen. That refusal is the ONLY guard here: plain `/forget` is valid in both a
 * group and a private chat now, so [inGroupOrExplain] does not apply to it.
 */
@CommandHandler(["/forget"])
suspend fun forget(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val global = update.text.trim().split(Regex("\\s+")).getOrNull(1)?.lowercase() == "all"
    val private = chat.type == ChatType.Private

    if (global && !private) {
        logCommand("forget", "private_chat_required")
        message {
            "Send /forget all to me in a private chat — from here I'd be reaching into your other groups."
        }.send(chat.id, bot)
        return
    }

    logCommand("forget", if (global) "erased_global" else if (private) "erased_private" else "erased_chat")
    val scope = when {
        global -> null                       // every chat, plus the bot-side
        private -> NO_CHAT_ID                // the person's own side of the bot
        else -> chat.id                      // this group alone
    }
    // Requests rest under the sentinel; messages are recorded under the real chat. Only
    // the private form pulls those two apart — see [ForgetService.plan].
    val plan = Registry.forget.plan(
        user.id, scope,
        personal = private || global,
        messageChatId = if (global) null else chat.id,
    )

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
 * people who actually have something waiting in [chatId] — which privately is the
 * bot-side space, so an `@username` there is matched against the handles on the requests
 * resting with no chat. Somebody with no `@username` cannot be addressed by the
 * typed form at all; the Done button on the give-up message is the reliable path, and no
 * second identifier scheme is invented to make them typeable.
 *
 * "Somebody was named and nothing resting here is theirs" comes back as
 * [NamedPeer.Unplaceable], NOT as [NamedPeer.Nobody]: privately those two answers must be
 * one answer, and this is the only place that still knows which of them happened. See
 * [LifecycleService.doneByShortId].
 */
private fun resolvePeer(update: ProcessedUpdate, chatId: Long): NamedPeer {
    val message = (update as? MessageUpdate)?.message ?: return NamedPeer.Nobody
    message.replyToMessage?.from?.id?.let { return NamedPeer.Somebody(it) }
    val entities = message.entities.orEmpty()
    entities.firstOrNull { it.user != null }?.user?.id?.let { return NamedPeer.Somebody(it) }
    val mentioned = entities.firstOrNull { it.type == EntityType.Mention }
        ?.let { message.text?.substring(it.offset + 1, it.offset + it.length) }
        ?: return NamedPeer.Nobody
    return Registry.requests.resting(chatId)
        .firstOrNull { it.username.equals(mentioned, ignoreCase = true) }
        ?.let { NamedPeer.Somebody(it.userId) }
        ?: NamedPeer.Unplaceable
}
