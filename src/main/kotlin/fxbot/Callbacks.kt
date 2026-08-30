package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.answer.answerCallbackQuery
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.CallbackQueryUpdate
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
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
    respond(result, update, bot)
}

@CommandHandler.CallbackQuery(["cancel"], autoAnswer = false)
suspend fun cancelCallback(t: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val result = if (t == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.cancelByToken(update.getUser().id, t)
    respond(result, update, bot)
}

@CommandHandler.CallbackQuery(["reopen"], autoAnswer = false)
suspend fun reopenCallback(t: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val tif = Registry.settings.get(chat.id).tifDays
    val result = if (t == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.reopen(chat.id, update.getUser().id, tif, t)
    respond(result, update, bot)
}

private suspend fun respond(result: ActionResult, update: ProcessedUpdate, bot: TelegramBot) {
    val user = update.getUser()
    val queryId = (update as? CallbackQueryUpdate)?.callbackQuery?.id
    when (result) {
        is ActionResult.Ok -> {
            // Dismisses the client's loading spinner without a popup — the outcome is
            // announced to the whole group below, since it may affect the other side too.
            queryId?.let { answerCallbackQuery(it).send(user.id, bot) }
            message { result.text }.send(update.getChat().id, bot)
            Registry.buttons.stripFor(result.closedTokens, update.getChat().id, bot)
        }
        is ActionResult.Denied, is ActionResult.Gone -> {
            // Private to the presser: a refusal is not the group's business. This also
            // clears the presser's spinner on a malformed/forged payload, instead of the
            // stack trace + stuck spinner a `parameters["x"]!!` crash would have left.
            queryId?.let {
                answerCallbackQuery(it).options { text = result.text; showAlert = true }.send(user.id, bot)
            }
        }
    }
}
