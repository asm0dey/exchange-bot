package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat

/**
 * A URL button, not a web_app one: Telegram allows web_app buttons only in private chats.
 * The group's id rides in `startapp`; the server re-checks membership on every call, so the
 * link proves nothing by itself.
 */
@CommandHandler(["/app"])
suspend fun app(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val link = Registry.miniAppLink
    if (link == null) {
        logCommand("app", "not_configured")
        message { "The app isn't set up on this bot yet." }.send(chat.id, bot)
        return
    }
    logCommand("app", "shown")
    val url = if (update.isGroupChat()) "$link?startapp=c${chat.id}" else link
    message { if (update.isGroupChat()) "Requests in this chat, in an app:" else "Your requests, in an app:" }
        .inlineKeyboardMarkup { "Open exchange" url url }
        .send(chat.id, bot)
}
