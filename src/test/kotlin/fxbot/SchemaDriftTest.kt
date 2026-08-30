package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

class SchemaDriftTest : StringSpec({
    "the Exposed tables match the schema Flyway creates" {
        val ds = memDataSource("drift")
        migrate(ds)
        val db = connectExposed(ds)
        transaction(db) {
            val tables = arrayOf(Requests, ChatSettingsTable, FxRates, SentMessages, SentMessageRefs)
            // One-directional: this lists the statements Exposed would run to bring the
            // database up to what Tables.kt declares, so it catches a column renamed or
            // invented in Tables.kt that V1__initial.sql doesn't have (this is exactly
            // how it caught a stray `sent_message.payload` column during the port). It
            // does NOT catch the opposite — a migration column with no matching Tables.kt
            // property — and it is not a reliable type-equivalence check.
            @Suppress("DEPRECATION") // replacement lives in exposed-migration-jdbc, a
            // module the Global Constraints forbid (Exposed is a query layer only;
            // Flyway owns the schema) — see task-5b-report.md.
            org.jetbrains.exposed.v1.jdbc.SchemaUtils
                .statementsRequiredToActualizeScheme(*tables)
                .shouldBeEmpty()
        }
    }
})
