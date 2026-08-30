package fxbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.ExperimentalKeywordApi
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * H2 with its file cipher on. The password is the H2 two-part form:
 * file password, a space, then the user password.
 */
fun createDataSource(cfg: Config): HikariDataSource {
    // H2 splits the two-part password on the FIRST space, so a space inside the file
    // key would silently truncate it and shift the remainder into the user password.
    require(' ' !in cfg.dbFileKey) { "DB_FILE_KEY must not contain a space" }
    return HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:file:${cfg.dbPath};CIPHER=AES;MODE=PostgreSQL"
        username = "sa"
        password = "${cfg.dbFileKey} ${cfg.dbUserPw}"
        maximumPoolSize = 4
    })
}

/** H2 support ships inside flyway-core; no database module is needed. */
fun migrate(ds: DataSource) {
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
}

/**
 * Registers the existing pool with Exposed's transaction manager. Exposed never opens
 * its own connections — every query still goes through the Hikari pool Flyway used.
 *
 * `preserveKeywordCasing` defaults to `true`, which makes Exposed double-quote any
 * column name that collides with a SQL:2003 reserved word (our `state` column is
 * one) so its exact case survives. Flyway's `CREATE TABLE` left that column
 * unquoted, so H2 folded it to `STATE`; a later quoted `"state"` reference then
 * misses it entirely ("Column REQUEST.state not found"). Disabling the flag keeps
 * Exposed's identifier handling on the same unquoted, case-folded footing Flyway's
 * raw SQL already established.
 */
@OptIn(ExperimentalKeywordApi::class)
fun connectExposed(ds: DataSource): Database =
    Database.connect(ds, databaseConfig = DatabaseConfig { preserveKeywordCasing = false })
