package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

class ChatMigrationTest : StringSpec({
    "everything the chat owned follows it to the new id" {
        val ds = memDataSource("chatmigrate")
        migrate(ds)
        val crypto = testCrypto()
        val requests = RequestRepository(ds, crypto)
        val settings = ChatSettingsRepository(ds, crypto)
        val log = MessageLogRepository(ds, crypto)

        requests.create(-100L, 1L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        settings.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        log.record(-100L, 10L, listOf("tokA"), listOf(1L))

        ChatMigrationService(requests, settings, log).migrate(-100L, -1001L) shouldBe 1

        val moved = requests.resting(-1001L)
        moved shouldHaveSize 1
        moved[0].username shouldBe "alice"
        settings.get(-1001L).pair shouldBe CurrencyPair("USD", "GBP")
        log.messagesForToken("tokA", 10).first().chatId shouldBe -1001L
        requests.resting(-100L) shouldHaveSize 0
    }
})
