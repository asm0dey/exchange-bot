package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun log(name: String, clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)): MessageLogRepository {
    val ds = memDataSource(name)
    migrate(ds)
    return MessageLogRepository(ds, testCrypto(), clock)
}

/** The shape `sent_message.payload` had before V3 — deliberately missing `text`/`buttons`,
 *  so a test serialising this class (rather than the current `MessagePayload`) proves what
 *  a pre-V3 row actually decodes as, instead of a round-trip that can't tell the difference. */
@Serializable
private data class PreV3Payload(val chatId: Long)

class MessageLogRepositoryTest : StringSpec({
    "finds the messages carrying a request's buttons, newest first" {
        val l = log("bytoken")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.record(-100L, 11L, listOf("tokA", "tokB"), listOf(1L, 2L))
        val found = l.messagesForToken("tokA", limit = 10)
        found shouldHaveSize 2
        found.first().messageId shouldBe 11L
        found.first().chatId shouldBe -100L
    }
    "caps the fan-out" {
        val l = log("fanout")
        (1L..15L).forEach { l.record(-100L, it, listOf("tokA"), listOf(1L)) }
        l.messagesForToken("tokA", limit = 10) shouldHaveSize 10
    }
    "finds every message naming a person, in one chat or all of them" {
        val l = log("byuser")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.record(-200L, 20L, listOf("tokB"), listOf(1L))
        l.messagesForUser(1L, -100L) shouldHaveSize 1
        l.messagesForUser(1L, null) shouldHaveSize 2
    }
    "knows whether a message named anyone else" {
        val l = log("others")
        l.record(-100L, 10L, listOf("tokA", "tokB"), listOf(1L, 2L))
        l.record(-100L, 11L, listOf("tokA"), listOf(1L))
        l.namesOthers(10L, -100L, userId = 1L) shouldBe true
        l.namesOthers(11L, -100L, userId = 1L) shouldBe false
    }
    "forgetting removes a person's rows only" {
        val l = log("forget")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.record(-100L, 11L, listOf("tokB"), listOf(2L))
        l.forget(1L, -100L)
        l.messagesForUser(1L, -100L) shouldHaveSize 0
        l.messagesForUser(2L, -100L) shouldHaveSize 1
    }
    "pruning drops rows past the retention window" {
        val l = log("prune")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.prune(T0.minusSeconds(1)) shouldBe 0
        l.prune(T0.plusSeconds(1)) shouldBe 1
        l.messagesForToken("tokA", 10) shouldHaveSize 0
    }
    "a chat migration keeps the tracked chat id readable" {
        val l = log("migrate")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.rewriteChatRef(-100L, -1001L) shouldBe 1
        l.messagesForToken("tokA", 10).first().chatId shouldBe -1001L
    }
    "a message's own text and keyboard are remembered, so a later edit can rebuild them" {
        val l = log("remembers")
        val buttons = listOf(Button("✅ Done with ann", "done?a=tokA&b=tokB"), Button("✖️ Cancel my request", "cancel?t=tokA"))
        l.record(-100L, 10L, listOf("tokA", "tokB"), listOf(1L, 2L), "2 people match: ...", buttons)
        val m = l.logged(-100L, 10L)!!
        m.chatId shouldBe -100L
        m.text shouldBe "2 people match: ..."
        m.buttons shouldBe buttons
        m.refTokens.toSet() shouldBe setOf("tokA", "tokB")
    }
    "a message recorded without its text reads back as having none, not as empty" {
        val l = log("notext")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        val m = l.logged(-100L, 10L)!!
        m.text shouldBe null
        m.buttons shouldBe emptyList()
    }
    "an unknown message is not logged" {
        log("unknownmsg").logged(-100L, 99L) shouldBe null
    }
    "re-recording the same message replaces its text and keyboard" {
        val l = log("rerecord")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L), "first", listOf(Button("a", "cancel?t=tokA")))
        l.record(-100L, 10L, listOf("tokA"), listOf(1L), "second", emptyList())
        val m = l.logged(-100L, 10L)!!
        m.text shouldBe "second"
        m.buttons shouldBe emptyList()
    }
    "a row written before V3 reads back with no text and no buttons, not a payload that fails to decode" {
        val crypto = testCrypto()
        val ds = memDataSource("prev3row")
        migrate(ds)
        val db = connectExposed(ds)
        val json = Json { ignoreUnknownKeys = true }
        transaction(db) {
            val chatRef = crypto.ref((-100L).toString())
            SentMessages.upsert {
                it[SentMessages.chatRef] = chatRef
                it[SentMessages.messageId] = 10L
                it[sentAt] = T0
                it[payload] = crypto.seal(json.encodeToString(PreV3Payload(-100L)), "$chatRef:10")
            }
            SentMessageRefs.batchInsert(listOf(0), shouldReturnGeneratedValues = false) {
                this[SentMessageRefs.chatRef] = chatRef
                this[SentMessageRefs.messageId] = 10L
                this[SentMessageRefs.refToken] = "tokA"
                this[SentMessageRefs.userRef] = crypto.ref("1")
            }
        }
        val l = MessageLogRepository(ds, crypto, db = db)
        val m = l.logged(-100L, 10L)!!
        m.text shouldBe null
        m.buttons shouldBe emptyList()
        m.refTokens shouldBe listOf("tokA")
    }
    "a chat migration reseals the message's text and keyboard, not just its chat id" {
        val l = log("migratefull")
        val buttons = listOf(Button("✅ Done", "done?a=tokA&b=tokB"))
        l.record(-100L, 10L, listOf("tokA"), listOf(1L), "some text", buttons)
        l.rewriteChatRef(-100L, -1001L) shouldBe 1
        val m = l.logged(-1001L, 10L)!!
        m.text shouldBe "some text"
        m.buttons shouldBe buttons
    }
})
