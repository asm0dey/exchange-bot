package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.getOrNull

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
 * branches sent under it — and to nothing else. `Denied`/`Gone` text can carry raw user
 * input (e.g. a short id typed by the caller) that was never meant to be parsed as markup:
 * sending it under `ParseMode.HTML` risks a Telegram entity-parse rejection (silently
 * swallowed — the reply never arrives) or, worse, an attacker-authored tag rendering as a
 * live link. Those two branches name a counterparty too, and name them with [plainName] for
 * the same reason: their text also reaches an `answerCallbackQuery` toast, which parses
 * nothing, so a `mention(...)` link there would be read out as its own markup.
 */
internal suspend fun replyToDecision(
    chatId: Long,
    bot: TelegramBot,
    result: ActionResult,
    undoable: Boolean = true,
) {
    when (result) {
        is ActionResult.Asked -> {
            sendAskedReply(chatId, result, bot)
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
 * What the declarer is told, in the chat they typed in: the counterparty was asked and
 * nothing closed. HTML, because the text names that counterparty via `mention(...)` — and
 * recorded against both people for exactly that reason, so the counterparty's `/forget`
 * reaches a message naming them (ADR 0005), same as [sendAsk] one function down.
 */
private suspend fun sendAskedReply(chatId: Long, r: ActionResult.Asked, bot: TelegramBot) {
    val sent = message { r.text }
        .options { parseMode = ParseMode.HTML }
        .sendReturning(chatId, bot)
        .getOrNull()
    sent?.messageId?.let { id ->
        Registry.messages.record(
            chatId, id,
            listOf(r.myToken, r.peerToken),
            listOf(r.declarerUserId, r.peerUserId),
            r.text,
        )
    }
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

/**
 * The other side of a confirmed done, told where THEY spoke rather than where the press
 * happened. Recorded against BOTH people the text names — the recipient and the person
 * who confirmed — because a `/forget` from either side must still reach it (ADR 0005),
 * same reasoning as [sendAsk], and said the same explicit way [sendAsk] says it.
 *
 * Both people come off the [Notice] rather than off the keyboard. The keyboard names the
 * confirmer only when the swap left the recipient something over, so a text reading
 * "@ann confirmed. Marked done: @ann and @bob." was recorded against @bob alone on every
 * exactly equal swap — the modal case — and @ann's `/forget` walked straight past a
 * message naming her.
 */
internal suspend fun sendNotice(n: Notice, bot: TelegramBot) {
    val buttons = noticeButtons(n)
    val sent = message { n.text }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .sendReturning(n.chatId, bot)
        .getOrNull()
    sent?.messageId?.let { id ->
        Registry.messages.record(
            n.chatId, id,
            listOf(n.reopenToken, n.otherToken),
            listOf(n.recipientUserId, n.otherUserId),
            n.text, buttons,
        )
    }
}

/**
 * Nobody is asked and nothing closes — the declarer just picks. Recorded against the
 * declarer's own request plus every candidate's, same as [suggestionButtons]' senders:
 * every button here names somebody, and each of them must be reachable by `/forget`
 * (ADR 0005), and each candidate token must be known to [ButtonService.refreshFor] so a
 * candidate who closes elsewhere first doesn't leave a stale button behind.
 */
internal suspend fun sendChoice(chatId: Long, r: ActionResult.Choose, bot: TelegramBot) {
    val book = nameBookFor(r.candidates, Registry.names)
    val buttons = chooseButtons(r, book)
    val sent = message { r.text }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .sendReturning(chatId, bot)
        .getOrNull()
    sent?.messageId?.let { id ->
        val mineUserId = Registry.requests.byRefToken(r.mineToken)?.userId
        if (mineUserId != null) {
            Registry.messages.record(
                chatId, id,
                listOf(r.mineToken) + r.candidates.map { it.refToken },
                listOf(mineUserId) + r.candidates.map { it.userId },
                r.text, buttons,
            )
        }
    }
}

/**
 * The private reply a statement earns, sent at once and never waiting for the batch: the
 * counterparties found, and where this is about to be shown. Both routes in — the typed
 * `/sell`/`/buy` and the Restate button — send exactly this, so the recording contract below
 * is written once rather than twice.
 *
 * [chatId] is where the reply goes and what the message is recorded under; [userId] is whose
 * announcements are queued. Telegram makes a private chat's id the person's own user id, so
 * these are the same number in production — they are kept apart because the message log is
 * keyed by chat and the batcher by person, and collapsing them would silently re-key one of
 * the two.
 *
 * Two different sets of people, deliberately:
 *  - **`record` gets every row.** The buttons name the stater's own rows too, and
 *    `ButtonService` rebuilds a keyboard only from what was recorded, so a token named by a
 *    button but missing here silently loses that button.
 *  - **the book gets only the COUNTERPARTIES.** They are the only people either the text or a
 *    button label ever names, and a lookup is a live round-trip to Telegram.
 */
internal suspend fun sendStated(
    chatId: Long,
    userId: Long,
    result: InterestResult.Stated,
    bot: TelegramBot,
) {
    val everyone = result.shown.flatMap { s -> listOf(s.request) + s.found.map { it.request } }
    val book = nameBookFor(result.shown.flatMap { s -> s.found.map { it.request } }, Registry.names)
    val text = renderStated(result, book)
    val buttons = statedButtons(result, book)
    val sent = message { text }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .sendReturning(chatId, bot)
        .getOrNull()
    sent?.messageId?.let { id ->
        Registry.messages.record(
            chatId, id,
            everyone.map { it.refToken }, everyone.map { it.userId },
            text, buttons,
        )
    }
    Registry.batcher.enqueueAnnouncement(userId)
    Registry.batcher.enqueueAppeared(result.appeared)
}

/**
 * A request posted in a group: the suggestions reply, recorded against the poster and every
 * counterparty it names, then the privately stated interests it just paired with are told.
 * The `/sell`/`/buy` command and the mini app both post through here.
 */
internal suspend fun deliverPosted(chatId: Long, result: PostResult.Posted, bot: TelegramBot) {
    val book = nameBookFor(
        listOf(result.request) + result.found.map { it.request }, Registry.names,
    )
    val text = renderSuggestions(result.found, result.status, book)
    val buttons = suggestionButtons(result.request, result.found, book)
    val sent = message { text }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .sendReturning(chatId, bot)
        .getOrNull()
    // The buttons on this message name the poster's own request plus every
    // counterparty's — record all of them, so a later close on ANY of those
    // requests knows to strip this message's keyboard too.
    sent?.messageId?.let { id ->
        Registry.messages.record(
            chatId,
            id,
            listOf(result.request.refToken) + result.found.map { it.request.refToken },
            listOf(result.request.userId) + result.found.map { it.request.userId },
            text,
            buttons,
        )
    }
    Registry.batcher.enqueueAppeared(result.appeared)
}
