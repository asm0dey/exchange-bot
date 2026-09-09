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
 *
 * On this handler `b` is the counterparty's USER ID, not their ref token — see [Cb.done].
 * It is bound as a `String?` and parsed here, like every other parameter, so a payload
 * carrying something that is not a number answers the presser with [BROKEN_BUTTON].
 * Resolving the id to a request, and refusing when it resolves to nothing, belongs to
 * [LifecycleService.doneWithPerson] — authorization is decided there, never here.
 */
@CommandHandler.CallbackQuery(["done"], autoAnswer = false)
suspend fun doneCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val peerUserId = b?.toLongOrNull()
    val result = if (a == null || peerUserId == null) {
        ActionResult.Denied(BROKEN_BUTTON)
    } else {
        Registry.lifecycle.doneWithPerson(update.getUser().id, a, peerUserId)
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
            if (result.touchedTokens.isEmpty()) {
                // Nothing changed state — `refuse` is the only source of this today.
                // Answered privately to the presser, same as `Denied`/`Gone` below: a
                // refusal is not the group's business, and the declarer is not told —
                // but the presser still gets `refuse`'s own text, not just a dismissed
                // spinner.
                queryId?.let {
                    answerCallbackQuery(it).options { text = result.text }.send(user.id, bot)
                }
                return
            }
            // Dismisses the client's loading spinner without a popup — the outcome is
            // announced to the whole group below, since it may affect the other side too.
            queryId?.let { answerCallbackQuery(it).send(user.id, bot) }
            // HTML: the text may carry a `mention(...)` link/@name built by LifecycleService.
            val reply = message { result.text }.options { parseMode = ParseMode.HTML }
            if (undoable) {
                // Undo, one press away, plus whatever the swap left the presser holding. The
                // undo names the presser's own closed request, so pressing it never risks
                // reviving a request that belongs to whoever else was named above.
                reply.inlineKeyboardMarkup { decisionButtons(result).forEach { b -> b.label callback b.data; br() } }
                    .send(update.getChat().id, bot)
            } else {
                reply.send(update.getChat().id, bot)
            }
            Registry.buttons.refreshFor(result.touchedTokens, bot)
            result.notify?.let { sendNotice(it, bot) }
        }
        is ActionResult.Asked -> {
            queryId?.let { answerCallbackQuery(it).options { text = result.text }.send(user.id, bot) }
            sendAsk(result, bot)
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
            queryId?.let { answerCallbackQuery(it).send(user.id, bot) }
            sendChoice(update.getChat().id, result, bot)
        }
    }
}

/**
 * The counterparty's answer. Nothing here is trusted: the presser is re-derived from
 * `callback_query.from.id`, both rows are re-read, and [LifecycleService.confirm] honours
 * the press only when the presser owns the request `b` names AND the bot really offered
 * them this exact button.
 */
@CommandHandler.CallbackQuery(["yes"], autoAnswer = false)
suspend fun confirmDoneCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val result = if (a == null || b == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.confirm(update.getUser().id, a, b)
    logCommand("confirm_button", result.outcomeLabel())
    respond(result, update, bot)
}

/**
 * Saying no. Nothing closes, both requests keep resting, and the refusal is counted
 * against the declarer — never against the person refusing.
 */
@CommandHandler.CallbackQuery(["no"], autoAnswer = false)
suspend fun refuseDoneCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val result = if (a == null || b == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.refuse(update.getUser().id, a, b)
    logCommand("refuse_button", result.outcomeLabel())
    // An empty `touchedTokens` means `respond` answers the press without a keyboard and
    // without a refresh pass — which is exactly right: nothing changed state.
    respond(result, update, bot)
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
            // Every row for `record` — the buttons name the stater's own rows too, and an
            // unrecorded token loses its button. Only the COUNTERPARTIES for the book: they
            // are the only people either the text or a button label ever names, and a lookup
            // is a live round-trip to Telegram.
            val everyone = result.shown.flatMap { s -> listOf(s.request) + s.found.map { it.request } }
            val book = nameBookFor(result.shown.flatMap { s -> s.found.map { it.request } }, Registry.names)
            val text = renderStated(result, book)
            val buttons = statedButtons(result, book)
            val sent = message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .sendReturning(userId, bot)
                .getOrNull()
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    userId, id,
                    everyone.map { it.refToken }, everyone.map { it.userId },
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
            Registry.batcher.enqueueAppeared(result.appeared)
            ackCallback(update, bot, "Stated.")
        }
    }
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
