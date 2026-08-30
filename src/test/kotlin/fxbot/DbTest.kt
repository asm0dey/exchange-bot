package fxbot

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain

private val COMPLETE = mapOf(
    "BOT_TOKEN" to "tok",
    "DB_FILE_KEY" to "filepw",
    "DB_USER_PW" to "userpw",
    "DATA_KEYSET" to """{"key":[]}""",
    "INDEX_KEYSET" to """{"key":[]}""",
)

class DbTest : StringSpec({
    "rejects a DB_FILE_KEY containing a space, because H2 would silently truncate it" {
        val cfg = loadConfig((COMPLETE + ("DB_FILE_KEY" to "has space"))::get)
        val e = shouldThrow<IllegalArgumentException> { createDataSource(cfg) }
        e.message!! shouldContain "DB_FILE_KEY"
    }
})
