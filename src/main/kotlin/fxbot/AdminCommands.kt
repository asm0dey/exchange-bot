package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.chat.getChatMember
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import eu.vendeli.tgbot.types.component.getOrNull
import eu.vendeli.tgbot.types.component.getUser

private const val NOT_ADMIN = "Only this chat's admins can change that."

/**
 * Denies on an API failure rather than assuming permission — a network hiccup on
 * `getChatMember` must never read as "probably fine". `throwExOnActionsFailure`
 * defaults to false, so a Telegram-side failure lands here as a null `ChatMember`
 * (via `getOrNull`'s `Response.Failure` -> null), not a thrown exception; the
 * `try/catch` only exists to also fail closed on a genuine transport exception.
 *
 * Cancellation is not a failure, so it is rethrown before the general catch —
 * `kotlin.runCatching` would swallow it, same class of bug `RateClient.fetch`
 * documents and fixes for the identical shape (a cancelled caller must not be
 * told "denied" and left thinking the check ran to completion).
 *
 * `ChatMember.status` is a real Telegram API string ("creator", "administrator",
 * "member", "restricted", "left", "kicked") derived from the sealed subtype's own
 * `@SerialName` — not a Kotlin enum, so there is no `.name` to read.
 */
private suspend fun isAdmin(chatId: Long, userId: Long, bot: TelegramBot): Boolean {
    val member = try {
        getChatMember(userId).sendReturning(chatId, bot).getOrNull()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    val status = member?.status ?: return false
    return status == "creator" || status == "administrator"
}

/**
 * `command` is a fixed label from the call site, never derived from user input. The
 * outcome category deliberately stops at "handled" / "not_admin": [body] returns free-form
 * reply text (a validation message or a confirmation) that this wrapper never inspects, so
 * there is no structured result to log a finer category from without string-matching our
 * own reply text — see docs/logging-notes.md.
 */
private suspend fun adminOnly(
    command: String,
    update: ProcessedUpdate,
    bot: TelegramBot,
    body: suspend (List<String>) -> String,
) {
    if (!inGroupOrExplain(update, bot)) return
    val chat = update.getChat()
    val user = update.getUser()
    if (!isAdmin(chat.id, user.id, bot)) {
        logCommand(command, "not_admin")
        message { NOT_ADMIN }.send(chat.id, bot)
        return
    }
    val args = update.text.trim().split(Regex("\\s+")).drop(1)
    val text = body(args)
    logCommand(command, "handled")
    message { text }.send(chat.id, bot)
}

@CommandHandler(["/pair"])
suspend fun pair(update: ProcessedUpdate, bot: TelegramBot) = adminOnly("pair", update, bot) { args ->
    if (args.size < 2) "Tell me both currencies, like /pair EUR RUB"
    else Registry.admin.setPair(update.getChat().id, args[0], args[1])
}

@CommandHandler(["/tolerance"])
suspend fun tolerance(update: ProcessedUpdate, bot: TelegramBot) = adminOnly("tolerance", update, bot) { args ->
    Registry.admin.setTolerance(update.getChat().id, args.firstOrNull().orEmpty())
}

@CommandHandler(["/tif"])
suspend fun tif(update: ProcessedUpdate, bot: TelegramBot) = adminOnly("tif", update, bot) { args ->
    Registry.admin.setTif(update.getChat().id, args.firstOrNull().orEmpty())
}
