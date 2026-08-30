package fxbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private val COMPLETE = mapOf(
    "BOT_TOKEN" to "tok",
    "DB_FILE_KEY" to "filepw",
    "DB_USER_PW" to "userpw",
    "DATA_KEYSET" to """{"key":[]}""",
    "INDEX_KEYSET" to """{"key":[]}""",
)

class ConfigTest : StringSpec({
    "loads a complete environment" {
        val c = loadConfig(COMPLETE::get)
        c.botToken shouldBe "tok"
        c.dbPath shouldBe "./data/exchange"
    }
    "DB_PATH overrides the default" {
        loadConfig((COMPLETE + ("DB_PATH" to "/srv/x"))::get).dbPath shouldBe "/srv/x"
    }
    "names the missing variable" {
        for (missing in COMPLETE.keys) {
            val env = COMPLETE - missing
            val e = shouldThrow<IllegalStateException> { loadConfig(env::get) }
            e.message!! shouldContain missing
        }
    }
    "rejects a blank variable the same as a missing one" {
        val e = shouldThrow<IllegalStateException> { loadConfig((COMPLETE + ("BOT_TOKEN" to "  "))::get) }
        e.message!! shouldContain "BOT_TOKEN"
    }
    "never puts a secret value in the message" {
        val e = shouldThrow<IllegalStateException> { loadConfig((COMPLETE - "DATA_KEYSET")::get) }
        e.message!!.contains("userpw") shouldBe false
    }
    "toString never reveals any secret value" {
        val text = loadConfig(COMPLETE::get).toString()
        for (secret in COMPLETE.values) {
            text.contains(secret) shouldBe false
        }
    }
})
