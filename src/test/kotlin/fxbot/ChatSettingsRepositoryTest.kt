package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun settingsRepo(name: String): ChatSettingsRepository {
    val ds = memDataSource(name)
    migrate(ds)
    return ChatSettingsRepository(ds, testCrypto())
}

class ChatSettingsRepositoryTest : StringSpec({
    "an unknown chat gets EUR/RUB, 20 percent, seven days" {
        val s = settingsRepo("defaults").get(-100L)
        s.pair shouldBe CurrencyPair("EUR", "RUB")
        s.tolerancePct shouldBe 20
        s.tifDays shouldBe 7
    }
    "saved settings round-trip" {
        val r = settingsRepo("save")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        val s = r.get(-100L)
        s.pair shouldBe CurrencyPair("USD", "GBP")
        s.tolerancePct shouldBe 5
        s.tifDays shouldBe 30
    }
    "settings are per chat" {
        val r = settingsRepo("perchat")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.get(-200L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
    "every configured pair can be enumerated for the rate refresh" {
        val r = settingsRepo("pairs")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.save(ChatSettings(-200L, CurrencyPair("EUR", "RUB"), 20, 7))
        r.allPairs() shouldBe setOf(CurrencyPair("USD", "GBP"), CurrencyPair("EUR", "RUB"))
    }
    "a chat migration re-encrypts the row under its new ref" {
        val r = settingsRepo("migrate")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.rewriteChatRef(-100L, -1001L) shouldBe true
        r.get(-1001L).pair shouldBe CurrencyPair("USD", "GBP")
        r.get(-100L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
})
