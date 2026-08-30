package fxbot

import eu.vendeli.tgbot.TelegramBot

/**
 * Strips the inline keyboard from any message whose buttons referenced a request
 * that just closed — a stale "Done"/"Cancel" button left on screen invites a
 * confusing second press. The seam is defined now so [LifecycleService] callers
 * (see `Callbacks.kt`) have something real to call; the implementation — finding
 * which sent messages carried a given ref token and editing their reply markup —
 * arrives in Task 11.
 */
class ButtonService {
    // Task 11 fills this in
    suspend fun stripFor(closedTokens: List<String>, chatId: Long, bot: TelegramBot) {
    }
}
