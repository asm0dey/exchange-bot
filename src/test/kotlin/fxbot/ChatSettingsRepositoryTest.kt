package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant

private fun settingsRepo(name: String): ChatSettingsRepository {
    val ds = memDataSource(name)
    migrate(ds)
    return ChatSettingsRepository(ds, testCrypto())
}

/**
 * Mirrors the shape of the private `SettingsPayload` in `ChatSettingsRepository` —
 * used only to reach into the sealed row directly, to prove `rewriteChatRef` writes
 * the NEW chat id into the resealed payload (not observable through the public
 * `ChatSettings` API, since `get`'s returned `chatId` is the caller's argument, not
 * whatever is stored inside the payload).
 */
@Serializable
private data class StoredPayload(val chatId: Long, val base: String, val quote: String, val tolerancePct: Int, val tifDays: Int)

private val testJson = Json { ignoreUnknownKeys = true }

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
    "get on an unknown chat persists the defaults, so the rate refresh sees the pair" {
        val r = settingsRepo("lazydefault")
        r.get(-100L)
        r.allPairs() shouldBe setOf(CurrencyPair("EUR", "RUB"))
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
    "a chat migration writes the new chat id into the resealed payload" {
        val ds = memDataSource("migrate-payload-chatid")
        migrate(ds)
        val crypto = testCrypto()
        val r = ChatSettingsRepository(ds, crypto)
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.rewriteChatRef(-100L, -1001L) shouldBe true

        val newRef = crypto.ref("-1001")
        val db = connectExposed(ds)
        val payloadJson = transaction(db) {
            val row = ChatSettingsTable.selectAll().where { ChatSettingsTable.chatRef eq newRef }.single()
            crypto.open(row[ChatSettingsTable.payload], newRef)
        }
        val decoded = testJson.decodeFromString<StoredPayload>(payloadJson)
        decoded.chatId shouldBe -1001L
    }
    "saving twice for the same chat updates in place, not a second row" {
        val r = settingsRepo("resave")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.save(ChatSettings(-100L, CurrencyPair("CHF", "JPY"), 9, 3))
        val s = r.get(-100L)
        s.pair shouldBe CurrencyPair("CHF", "JPY")
        s.tolerancePct shouldBe 9
        s.tifDays shouldBe 3
        r.allPairs() shouldBe setOf(CurrencyPair("CHF", "JPY"))
    }
    "fan-out is on unless an admin turned it off" {
        settingsRepo("fanoutdefault").get(-100L).fanOut shouldBe true
    }
    "a payload sealed before the flag existed decodes with fan-out on" {
        // Built from StoredPayload (the pre-fanOut field set) and sealed by hand, so the
        // JSON that lands in the row genuinely has no "fanOut" key — SettingsPayload never
        // touches it. This is the actual shape of every chat row that predates this feature.
        val ds = memDataSource("fanoutlegacypayload")
        migrate(ds)
        val crypto = testCrypto()
        val chatRef = crypto.ref("-100")
        val legacyJson = testJson.encodeToString(StoredPayload(-100L, "EUR", "RUB", 20, 7))
        val db = connectExposed(ds)
        transaction(db) {
            ChatSettingsTable.upsert {
                it[ChatSettingsTable.chatRef] = chatRef
                it[payload] = crypto.seal(legacyJson, chatRef)
                it[updatedAt] = Instant.now()
            }
        }

        val r = ChatSettingsRepository(ds, crypto)
        val viaGet = r.get(-100L)
        viaGet.fanOut shouldBe true
        viaGet.pair shouldBe CurrencyPair("EUR", "RUB")
        viaGet.tolerancePct shouldBe 20
        viaGet.tifDays shouldBe 7

        val viaAllChats = r.allChats().single()
        viaAllChats.chatId shouldBe -100L
        viaAllChats.fanOut shouldBe true
        viaAllChats.pair shouldBe CurrencyPair("EUR", "RUB")
        viaAllChats.tolerancePct shouldBe 20
        viaAllChats.tifDays shouldBe 7
    }
    "fan-out round-trips when it is turned off" {
        val r = settingsRepo("fanoutoff")
        r.save(ChatSettings(-100L, CurrencyPair("EUR", "RUB"), 20, 7, fanOut = false))
        r.get(-100L).fanOut shouldBe false
    }
    "every chat can be enumerated, with its own id, for the fan-out search" {
        val r = settingsRepo("allchats")
        r.save(ChatSettings(-100L, CurrencyPair("EUR", "RUB"), 20, 7))
        r.save(ChatSettings(-200L, CurrencyPair("USD", "GBP"), 5, 30, fanOut = false))
        r.allChats().map { it.chatId }.toSet() shouldBe setOf(-100L, -200L)
        r.allChats().single { it.chatId == -200L }.fanOut shouldBe false
        r.allChats().single { it.chatId == -200L }.pair shouldBe CurrencyPair("USD", "GBP")
    }
})
