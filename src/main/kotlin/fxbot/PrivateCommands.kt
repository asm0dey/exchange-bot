package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.chat.getChat
import eu.vendeli.tgbot.api.chat.getChatMember
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.getChat
import eu.vendeli.tgbot.types.component.getOrNull
import eu.vendeli.tgbot.types.component.getUser
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/** `/sell 10 EUR for RUB` — the amount's currency, then `for`, then the other leg. */
private const val EXAMPLE = "Tell me both currencies, like: /sell 10 EUR for RUB"

/** What a private chat can actually do, in the same shape as the group's [HELP_TEXT]. */
internal val PRIVATE_HELP_TEXT = """
    /sell 10 EUR for RUB — you're handing over 10 EUR
    /buy 10 RUB for EUR — you want to receive 10 RUB
    /tolerance 5 — how much you'll accept being left with, 1-100
    /status — your interests and where each still rests
    /cancel a1 — withdraw an interest, every showing with it
    /done a1 @anna — you two swapped
    /settings — your size tolerance
    /forget — erase what I hold about you (add 'all' to reach every group)
""".trimIndent()

/**
 * `/sell` and `/buy` in a private chat. Everything the person stated rests the moment
 * this returns; only the announcements to the chats it is shown in wait for the batch,
 * so a counterparty is never missed because a message had not gone out yet.
 */
internal suspend fun handlePrivatePost(verb: Verb, update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val args = update.text.trim().split(Regex("\\s+")).drop(1)
    val command = verb.name.lowercase()
    if (args.size < 4 || !args[2].equals("for", ignoreCase = true)) {
        logCommand(command, "missing_args")
        message { EXAMPLE }.send(chat.id, bot)
        return
    }
    when (val result = Registry.interests.state(user.id, user.username, verb, args[0], args[1], args[3])) {
        is InterestResult.Rejected -> {
            logCommand(command, "rejected")
            message { result.reason }.send(chat.id, bot)
        }
        is InterestResult.Stated -> {
            logCommand(command, "stated")
            // At once, and never waiting for the batch: the counterparties found, and
            // where this is about to be shown.
            val text = renderStated(result)
            val buttons = statedButtons(result)
            val sent = message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .sendReturning(chat.id, bot)
                .getOrNull()
            // Every ref token the buttons name, not just the subject: ButtonService
            // rebuilds a message's keyboard only from what was recorded, so a token
            // named by a button but missing here silently loses that button.
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    chat.id, id,
                    listOf(result.interest.refToken) + result.found.map { it.request.refToken },
                    listOf(result.interest.userId) + result.found.map { it.request.userId },
                    text, buttons,
                )
            }
            Registry.batcher.enqueueAnnouncement(user.id)
            Registry.batcher.enqueueAppeared(result.appeared)
        }
    }
}

/** Each interest once, and where it still rests — never a chat's own resting list. */
internal suspend fun privateStatus(update: ProcessedUpdate, bot: TelegramBot) {
    val user = update.getUser()
    logCommand("status", "shown_private")
    message { renderStandings(Registry.interests.standings(user.id)) }
        .options { parseMode = ParseMode.HTML }
        .send(update.getChat().id, bot)
}

/** The person's own size tolerance, which never overrides a chat's. */
internal suspend fun privateSettings(update: ProcessedUpdate, bot: TelegramBot) {
    val user = update.getUser()
    logCommand("settings", "shown_private")
    message {
        "Your size tolerance is ${Registry.people.get(user.id).tolerancePct}%. Change it with /tolerance."
    }.send(update.getChat().id, bot)
}

/** `/tolerance` privately is the person's own; in a group it stays the admin command. */
internal suspend fun privateTolerance(update: ProcessedUpdate, bot: TelegramBot) {
    val user = update.getUser()
    val args = update.text.trim().split(Regex("\\s+")).drop(1)
    logCommand("tolerance", "handled_private")
    message { Registry.people.setTolerance(user.id, args.firstOrNull().orEmpty()) }.send(update.getChat().id, bot)
}

private val adapterLogger = LoggerFactory.getLogger("fxbot.adapters")

/** Probe failures, counted rather than identified — never which chat or which person. */
private val membershipFailures = AtomicLong()

/**
 * Membership is probed per fan-out and the answer is used and discarded — no record of
 * which chats a person belongs to is written anywhere. Denies on any failure, like
 * `AdminCommands.isAdmin`: a network hiccup must never read as "probably a member".
 * Cancellation is not a failure and is rethrown.
 *
 * The failure is logged HERE rather than in [InterestService], which catches a throwing
 * probe deliberately without a logger: this adapter is the boundary where the I/O
 * actually happens, so it is the only place that knows a call failed rather than
 * answering "no".
 */
fun telegramMembership(bot: TelegramBot) = MembershipProbe { chatId, userId ->
    val member = try {
        getChatMember(userId).sendReturning(chatId, bot).getOrNull()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        adapterLogger.warn("membership probe: outcome=threw count=${membershipFailures.incrementAndGet()}")
        null
    }
    // A Telegram-side refusal comes back as a null member, not a throw
    // (`throwExOnActionsFailure` is false everywhere in this codebase), and is
    // indistinguishable from "not a member" — so it is not counted as a failure.
    when (member?.status) {
        "creator", "administrator", "member", "restricted" -> true
        else -> false
    }
}

/**
 * Looked up live at give-up time, so what passes is current and nothing is stored
 * (ADR 0007). A private chat's id IS the person's user id, so `getChat` addressed by user
 * id is the person's own chat with the bot — and a person who has never opened one is not
 * reachable at all, which comes back here as null and reads to [GiveUpService] as "no
 * route", exactly the outcome it already handles.
 */
fun telegramNames(bot: TelegramBot) = NameLookup { userId ->
    val chat = try {
        getChat().sendReturning(userId, bot).getOrNull()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    chat?.let { Handle(it.username, it.firstName ?: it.title ?: "this person") }
}

/**
 * Telegram refused a send, so the chat was NOT told. Carries no chat, no person and no
 * text: [AnnouncementBatcher] logs the class name of whatever escapes a flush, and that
 * class name is the whole of what this needs to say.
 */
class AnnouncementNotSent : RuntimeException("Telegram refused an announcement send")

/**
 * Sends what a flush produced and records every announcement against every token it
 * names. Recording is what makes [ButtonService.refreshFor] able to rebuild a batched
 * message later: it reads the stored text and the stored button list and nothing else, so
 * an unrecorded message degrades to an all-or-nothing keyboard strip and a token named by
 * a button but not recorded loses that button on the first refresh.
 *
 * A ping is deliberately NOT recorded. Its give-up buttons name the COUNTERPARTY's ref
 * token, so recording it would store that person's user ref against the recipient's
 * private chat — a stored link between two people on a no-names basis, before either of
 * them pressed anything. A give-up button that later goes dead instead degrades to
 * `GiveUpService`'s existing "no longer waiting" refusal, which names nobody.
 */
fun telegramSink(bot: TelegramBot) = AnnouncementSink { announcements, pings ->
    for (a in announcements) {
        // `.await()` then check, NOT `.getOrNull()`: `throwExOnActionsFailure` is false
        // everywhere in this codebase, so Telegram refusing the send (bot kicked from the
        // group, a 429 that outlived its retries) arrives as a `Response.Failure` and
        // `getOrNull()` would quietly turn it into null. Returning normally after that
        // tells the batcher the chat was told, and it then deletes the pending row — the
        // announcement would be lost for good while the showing kept resting there.
        val sent = message { a.text }
            .options { parseMode = ParseMode.HTML }
            .inlineKeyboardMarkup { a.buttons.forEach { b -> b.label callback b.data; br() } }
            .sendReturning(a.chatId, bot)
            .await()
            .getOrNull() ?: throw AnnouncementNotSent()
        Registry.messages.record(a.chatId, sent.messageId, a.refTokens, a.userIds, a.text, a.buttons)
    }
    for (p in pings) {
        // Not checked, and deliberately: nothing about a ping is written down, so there is
        // nothing to retry — `AnnouncementBatcher` drains the token set before the send.
        // Throwing here would only re-send the announcements above, which DID land.
        message { p.text }
            .inlineKeyboardMarkup { p.buttons.forEach { b -> b.label callback b.data; br() } }
            .send(p.userId, bot)
    }
}
