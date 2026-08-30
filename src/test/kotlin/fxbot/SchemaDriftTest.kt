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
            // Any statement Exposed would need to run to reach its own definition is a
            // disagreement between Tables.kt and V1__initial.sql.
            org.jetbrains.exposed.v1.jdbc.SchemaUtils
                .statementsRequiredToActualizeScheme(*tables)
                .shouldBeEmpty()
        }
    }
})
