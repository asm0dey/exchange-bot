package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun log(name: String, clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)): MessageLogRepository {
    val ds = memDataSource(name)
    migrate(ds)
    return MessageLogRepository(ds, testCrypto(), clock)
}

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
})
