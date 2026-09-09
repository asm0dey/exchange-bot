package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.answer.answerCallbackQuery
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.CallbackQueryUpdate
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import eu.vendeli.tgbot.types.component.getOrNull
import eu.vendeli.tgbot.types.component.getUser

private const val BROKEN_BUTTON = "That button looks broken — try the /command instead."

/**
 * callback_data is a suggestion from a client, not an authorization: every
 * handler re-derives who is acting from `callback_query.from.id` (via
 * `update.getUser()`) and lets [LifecycleService] do the real authorization
 * check against the database — exactly as the command handlers do.
 *
 * Every bound parameter is declared nullable: a legitimate button always fills
 * them in, but a hand-crafted payload (`done` with no `a`) can omit one. KSP
 * emits a plain `parameters["a"]` lookup (no `!!`) for a nullable parameter —
 * confirmed by inspecting the generated `ActivitiesData.kt` — so a missing key
 * arrives here as `null` instead of throwing before the handler body even runs.
 */
@CommandHandler.CallbackQuery(["done"], autoAnswer = false)
suspend fun doneCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val result = if (a == null || b == null) {
        ActionResult.Denied(BROKEN_BUTTON)
    } else {
        Registry.lifecycle.done(update.getUser().id, a, b)
    }
    logCommand("done_button", result.outcomeLabel())
    respond(result, update, bot)
}

@CommandHandler.CallbackQuery(["cancel"], autoAnswer = false)
suspend fun cancelCallback(t: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val result = if (t == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.cancelByToken(update.getUser().id, t)
    logCommand("cancel_button", result.outcomeLabel())
    respond(result, update, bot)
}

/**
 * A chat's settings are read only when there IS a chat: a private chat's id is the
 * person's own user id, and `ChatSettingsRepository.get` persists a default row on a miss,
 * so reading it here would write a `chat_settings` row keyed to the person themselves —
 * a fan-out candidate, a pair to price, and a record of them that no `/forget` path
 * erases, which the spec's Privacy section forbids. Nothing is lost by not reading it: for
 * an interest, `LifecycleService.reopen` gives each showing its own chat's time in force
 * and ignores this argument entirely.
 */
@CommandHandler.CallbackQuery(["reopen"], autoAnswer = false)
suspend fun reopenCallback(t: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val tif = if (update.isGroupChat()) Registry.settings.get(chat.id).tifDays else NO_CHAT_TIF_DAYS
    val result = if (t == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.reopen(chat.id, update.getUser().id, tif, t)
    logCommand("reopen_button", result.outcomeLabel())
    respond(result, update, bot, undoable = false)
}

/**
 * [undoable] is false for the reopen button: its touched requests are RESTING again, so
 * offering to reopen them could only ever answer "already waiting". The rewrite below
 * still runs either way — it is what takes a stale "withdrawn"/"done" line back off the
 * messages that carried the request.
 */
private suspend fun respond(
    result: ActionResult,
    update: ProcessedUpdate,
    bot: TelegramBot,
    undoable: Boolean = true,
) {
    val user = update.getUser()
    val queryId = (update as? CallbackQueryUpdate)?.callbackQuery?.id
    when (result) {
        is ActionResult.Ok -> {
            // Dismisses the client's loading spinner without a popup — the outcome is
            // announced to the whole group below, since it may affect the other side too.
            queryId?.let { answerCallbackQuery(it).send(user.id, bot) }
            // HTML: the text may carry a `mention(...)` link/@name built by LifecycleService.
            val reply = message { result.text }.options { parseMode = ParseMode.HTML }
            if (result.touchedTokens.isEmpty() || !undoable) {
                reply.send(update.getChat().id, bot)
            } else {
                // Undo, one press away, plus whatever the swap left the presser holding. The
                // undo names the presser's own closed request, so pressing it never risks
                // reviving a request that belongs to whoever else was named above.
                reply.inlineKeyboardMarkup { decisionButtons(result).forEach { b -> b.label callback b.data; br() } }
                    .send(update.getChat().id, bot)
            }
            if (result.touchedTokens.isNotEmpty()) Registry.buttons.refreshFor(result.touchedTokens, bot)
        }
        is ActionResult.Asked -> {
            // Task 7 delivers the question.
            queryId?.let { answerCallbackQuery(it).send(user.id, bot) }
            // HTML: the text names the counterparty via `mention(...)`.
            message { result.text }.options { parseMode = ParseMode.HTML }.send(update.getChat().id, bot)
        }
        is ActionResult.Denied, is ActionResult.Gone -> {
            // Private to the presser: a refusal is not the group's business. This also
            // clears the presser's spinner on a malformed/forged payload, instead of the
            // stack trace + stuck spinner a `parameters["x"]!!` crash would have left.
            queryId?.let {
                answerCallbackQuery(it).options { text = result.text; showAlert = true }.send(user.id, bot)
            }
        }
        is ActionResult.Choose -> {
            // No callback today produces this — `doneByShortId` is the only source, and
            // it is reached from the typed `/done` command through `replyToDecision`, not
            // through here. This branch only keeps `respond`'s `when` exhaustive; Task 7
            // wires `chooseButtons` onto whichever surface ends up sending it.
            queryId?.let { answerCallbackQuery(it).send(user.id, bot) }
            message { result.text }.send(update.getChat().id, bot)
        }
    }
}

/**
 * Offering to pass a name. Consent is read from the table, not from this payload: a
 * hand-crafted `b` claiming the other side agreed achieves nothing, and [GiveUpService]
 * refuses outright unless the presser — re-derived from `callback_query.from.id` — owns
 * the request `a` names.
 */
@CommandHandler.CallbackQuery(["giveup"], autoAnswer = false)
suspend fun giveUpCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    if (a == null || b == null) return respond(ActionResult.Denied(BROKEN_BUTTON), update, bot)
    when (val r = Registry.giveUpService.offer(update.getUser().id, a, b)) {
        is GiveUpResult.Asked -> {
            logCommand("giveup_button", "asked")
            // Nothing about the presser reaches the peer here — the peer is told about
            // THEIR OWN interest (`theirs`), and both requests arrive with their
            // usernames stripped, so `describe` cannot print a handle either way.
            //
            // Deliberately not recorded in the message log: its buttons name the
            // presser's ref token, and recording it would store the presser's user ref
            // against this peer's private chat before the peer has agreed to anything.
            message { "Someone matching your ${describe(r.theirs)} has offered to pass their name. Pass yours back?" }
                .inlineKeyboardMarkup {
                    "🤝 Pass my name" callback Cb.giveUp(r.peerRefToken, r.myRefToken); br()
                    "🚫 No thanks" callback Cb.decline(r.peerRefToken, r.myRefToken)
                }
                .send(r.peerUserId, bot)
            ackCallback(update, bot, "I've asked them. I'll tell you if they agree.")
        }
        is GiveUpResult.Disclosed -> {
            // Recorded in the message log so forgetting can redact them (ADR 0005).
            val toA = discloseTo(r.a, r.b, bot)
            val toB = discloseTo(r.b, r.a, bot)
            // Which of the two the presser is decides what is true to tell them, so the
            // answer is picked from the delivery that was actually theirs.
            val presserIsA = update.getUser().id == r.a.userId
            logCommand("giveup_button", if (toA && toB) "disclosed" else "disclosed_undelivered")
            ackCallback(
                update, bot,
                disclosureReply(mine = if (presserIsA) toA else toB, theirs = if (presserIsA) toB else toA),
            )
        }
        is GiveUpResult.Recorded -> {
            logCommand("giveup_button", "recorded")
            ackCallback(update, bot, r.text)
        }
        is GiveUpResult.Refused -> {
            logCommand("giveup_button", "refused")
            ackCallback(update, bot, r.text)
        }
    }
}

/**
 * Saying no to one pairing. Written, so neither side is offered the other again while the
 * two requests rest — and authorized exactly as the give-up is: [GiveUpService.decline]
 * writes nothing unless the presser owns the request `a` names.
 *
 * The answer is private to the presser and names nobody, in both directions: a decline is
 * not the other person's business, and telling them would turn "no" into a message they
 * would have to read.
 */
@CommandHandler.CallbackQuery(["decline"], autoAnswer = false)
suspend fun declineCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    if (a == null || b == null) return respond(ActionResult.Denied(BROKEN_BUTTON), update, bot)
    val r = Registry.giveUpService.decline(update.getUser().id, a, b)
    logCommand("decline_button", if (r is GiveUpResult.Recorded) "recorded" else "refused")
    ackCallback(update, bot, giveUpText(r))
}

private const val RESTATE_GONE = "That request is gone, so there's nothing left to work out."

private const val RESTATE_NOT_YOURS = "That isn't your request."

private const val RESTATE_NOTHING_LEFT =
    "There's nothing left over on that one now, so there's nothing to state again."

/**
 * Stating what a done left the presser holding, as a brand new request. Everything is
 * re-derived at press time: the presser from `callback_query.from.id`, both rows from the
 * database, and the residual from those two rows and the current reference rate. The
 * original's stated amount is never rewritten (ADR 0003) — pressing twice states it twice
 * rather than editing anything.
 *
 * Where it goes is [restateGoesPrivate]'s decision, and the reply follows it: a residual
 * of a privately stated interest is a new interest, so its reply names counterparties found
 * with no chat and must go to the presser privately even when the button was pressed in
 * a group. A residual of a request typed in a group is restated in that group, and
 * answered there like any other post.
 */
@CommandHandler.CallbackQuery(["restate"], autoAnswer = false)
suspend fun restateCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    if (a == null || b == null) return respond(ActionResult.Denied(BROKEN_BUTTON), update, bot)
    val user = update.getUser()
    val mine = Registry.requests.byRefToken(a)
    if (mine == null) {
        logCommand("restate_button", "gone")
        return ackCallback(update, bot, RESTATE_GONE)
    }
    if (mine.userId != user.id) {
        logCommand("restate_button", "not_yours")
        return ackCallback(update, bot, RESTATE_NOT_YOURS)
    }
    val theirs = Registry.requests.byRefToken(b)
    if (theirs == null) {
        logCommand("restate_button", "gone")
        return ackCallback(update, bot, RESTATE_GONE)
    }
    // Recomputed now, not read off the button: the rate may have moved since the done,
    // and the button carries no amount for exactly that reason.
    val residual = residualOf(mine, theirs, Registry.rates.status(mine.pair).rate)
    if (residual == null) {
        logCommand("restate_button", "nothing_left")
        return ackCallback(update, bot, RESTATE_NOTHING_LEFT)
    }
    val verb = verbFor(mine.side, mine.statedCurrency, mine.pair)
    val amount = residual.toPlainString()
    if (restateGoesPrivate(mine)) {
        restatePrivately(user.id, user.username, verb, amount, mine, update, bot)
    } else {
        restateInChat(user.id, user.username, verb, amount, mine, update, bot)
    }
}

/** The other leg of a request's pair — the currency its stated amount is NOT in. */
private fun otherLeg(r: Request): String =
    if (r.statedCurrency == r.pair.base) r.pair.quote else r.pair.base

/**
 * A new interest, which fans out like any other and joins the current batch rather than
 * being refused — it pays the same cap and the same window as anything else the person
 * states. The reply goes to the presser's own chat with the bot, never to the chat the
 * button was pressed in: it lists counterparties found with no chat, and those are
 * the presser's business alone.
 */
private suspend fun restatePrivately(
    userId: Long,
    username: String?,
    verb: Verb,
    amount: String,
    mine: Request,
    update: ProcessedUpdate,
    bot: TelegramBot,
) {
    when (val result = Registry.interests.state(userId, username, verb, amount, mine.statedCurrency, otherLeg(mine))) {
        is InterestResult.Rejected -> {
            logCommand("restate_button", "rejected")
            ackCallback(update, bot, result.reason)
        }
        is InterestResult.Stated -> {
            logCommand("restate_button", "stated")
            val text = renderStated(result)
            val buttons = statedButtons(result)
            val sent = message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .sendReturning(userId, bot)
                .getOrNull()
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    userId, id,
                    listOf(result.interest.refToken) + result.found.map { it.request.refToken },
                    listOf(result.interest.userId) + result.found.map { it.request.userId },
                    text, buttons,
                )
            }
            Registry.batcher.enqueueAnnouncement(userId)
            Registry.batcher.enqueueAppeared(result.appeared)
            ackCallback(update, bot, "Stated. I've sent you the details privately.")
        }
    }
}

/**
 * A request typed in a group is restated in that group alone: fanning it out bot-wide
 * would put somebody on the bot-side who never asked, and consent is what puts them
 * there (ADR 0007).
 */
private suspend fun restateInChat(
    userId: Long,
    username: String?,
    verb: Verb,
    amount: String,
    mine: Request,
    update: ProcessedUpdate,
    bot: TelegramBot,
) {
    when (val result = Registry.service.post(mine.chatId, userId, username, verb, amount, mine.statedCurrency)) {
        is PostResult.Rejected -> {
            logCommand("restate_button", "rejected")
            ackCallback(update, bot, result.reason)
        }
        is PostResult.Posted -> {
            logCommand("restate_button", "posted")
            val book = nameBookFor(
                listOf(result.request) + result.found.map { it.request }, Registry.names,
            )
            val text = renderSuggestions(result.found, result.status, book)
            val buttons = suggestionButtons(result.request, result.found, book)
            val sent = message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .sendReturning(mine.chatId, bot)
                .getOrNull()
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    mine.chatId, id,
                    listOf(result.request.refToken) + result.found.map { it.request.refToken },
                    listOf(result.request.userId) + result.found.map { it.request.userId },
                    text, buttons,
                )
            }
            ackCallback(update, bot, "Stated.")
        }
    }
}

/** The text a give-up outcome carries, for the two branches that only ever answer. */
private fun giveUpText(r: GiveUpResult): String = when (r) {
    is GiveUpResult.Recorded -> r.text
    is GiveUpResult.Refused -> r.text
    // Neither is reachable from `decline`, which only ever writes or refuses.
    is GiveUpResult.Asked, is GiveUpResult.Disclosed -> BROKEN_BUTTON
}

/**
 * Hands [to] the other person's handle, with a Done button naming both requests, and
 * records the message against BOTH people so a `/forget` from either side reaches it
 * (ADR 0005). [Party.handle] is already a `mention(...)`, hence HTML.
 */
private suspend fun discloseTo(to: Party, other: Party, bot: TelegramBot): Boolean {
    val text = "You both agreed to pass names. This is ${other.handle}.\n$AGREE_LINE"
    val buttons = listOf(Button("✅ Done", Cb.done(to.refToken, other.refToken)))
    val sent = message { text }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .sendReturning(to.userId, bot)
        .getOrNull() ?: return false
    Registry.messages.record(
        to.userId, sent.messageId,
        listOf(to.refToken, other.refToken),
        listOf(to.userId, other.userId),
        text, buttons,
    )
    return true
}

/**
 * What to tell the presser, from what actually arrived. A person deciding what to believe
 * about their own name must not be told a delivery happened when it did not — and the
 * agreement itself is already written, so pressing again re-sends rather than re-asking.
 * The recovery each wording suggests is therefore real.
 */
private fun disclosureReply(mine: Boolean, theirs: Boolean): String = when {
    mine && theirs -> "You both agreed — I've passed your names."
    mine -> "You both agreed and I've sent you their details, but I couldn't get a message " +
        "through to them. They may have blocked me."
    theirs -> "You both agreed and I've told them, but I couldn't send you their details. " +
        "Make sure you haven't blocked me, then press again."
    else -> "You both agreed, but I couldn't get a message through to either of you. " +
        "Make sure you haven't blocked me, then press again."
}

/**
 * One way for the new handlers to answer a press. Deliberately NOT named
 * `answerCallbackQuery`: that is the framework's own action builder, imported into this
 * file, and a same-named local function would shadow it at every call site — including
 * inside this body.
 */
private suspend fun ackCallback(update: ProcessedUpdate, bot: TelegramBot, alert: String) {
    (update as? CallbackQueryUpdate)?.callbackQuery?.id?.let {
        answerCallbackQuery(it).options { text = alert; showAlert = true }.send(update.getUser().id, bot)
    }
}
