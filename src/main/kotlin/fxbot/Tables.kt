package fxbot

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.datetime.InstantColumnType
import java.time.Instant

/**
 * `Table.timestamp()` ships in the `exposed-kotlin-datetime` and `exposed-java-time`
 * modules, neither of which this project depends on (Global Constraints: exposed-core
 * + exposed-jdbc only). exposed-core does carry the abstract [InstantColumnType], which
 * works in terms of `kotlin.time.Instant` — a Kotlin-stdlib type, distinct from
 * `kotlinx.datetime.Instant` — so a two-line concrete subclass bridging it to
 * `java.time.Instant` gets a working timestamp column without adding a module.
 *
 * The stdlib also ships `kotlin.time.jdk8.toJavaInstant()`/`toKotlinInstant()` for
 * exactly this conversion, but that specific interop file does not resolve against
 * this project's Kotlin 2.4.10 toolchain (`error: unresolved reference` even in a
 * standalone `kotlinc` compile, independent of Gradle — verified by hand). The
 * seconds/nanos bridge below uses only stable, directly-confirmed-resolvable members
 * of both `Instant` types, and neither direction touches a time zone.
 *
 * The JVM-default-zone dependency in this column lives inside Exposed's
 * `InstantColumnType` itself, not here: writing a value formats it as SQL text via a
 * `LocalDateTime`, which `InstantColumnType` derives from the `Instant` using the
 * system default zone (`toLocalDateTime()`/equivalent internally), and reading
 * reverses that through the same zone. That is exactly what the hand-rolled JDBC this
 * replaced also did — `java.sql.Timestamp.from(instant)` / `Timestamp.toInstant()`
 * round-trip through the JVM's default time zone the same way — so this is not a
 * regression the port introduced.
 */
private class JavaInstantColumnType : InstantColumnType<Instant>() {
    override fun toInstant(value: Instant): kotlin.time.Instant =
        kotlin.time.Instant.fromEpochSeconds(value.epochSecond, value.nano)

    override fun fromInstant(instant: kotlin.time.Instant): Instant =
        Instant.ofEpochSecond(instant.epochSeconds, instant.nanosecondsOfSecond.toLong())
}

private fun Table.timestamp(name: String): Column<Instant> = registerColumn(name, JavaInstantColumnType())

/** Mirrors `V1__initial.sql`; `SchemaDriftTest` keeps the two from disagreeing silently. */
object Requests : Table("request") {
    val rowId = long("row_id").autoIncrement()
    val refToken = text("ref_token")
    val chatRef = text("chat_ref")
    val userRef = text("user_ref")
    val shortId = text("short_id")
    val state = text("state")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val closedAt = timestamp("closed_at").nullable()
    val payload = binary("payload")
    override val primaryKey = PrimaryKey(rowId)
}

object ChatSettingsTable : Table("chat_settings") {
    val chatRef = text("chat_ref")
    val payload = binary("payload")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(chatRef)
}

object FxRates : Table("fx_rate") {
    val base = text("base")
    val quote = text("quote")
    val rate = decimal("rate", 30, 10)
    val fetchedAt = timestamp("fetched_at")
    override val primaryKey = PrimaryKey(base, quote)
}

object SentMessages : Table("sent_message") {
    val chatRef = text("chat_ref")
    val messageId = long("message_id")
    val sentAt = timestamp("sent_at")
    val payload = binary("payload")
    override val primaryKey = PrimaryKey(chatRef, messageId)
}

object SentMessageRefs : Table("sent_message_ref") {
    val chatRef = text("chat_ref")
    val messageId = long("message_id")
    val refToken = text("ref_token")
    val userRef = text("user_ref")
}
