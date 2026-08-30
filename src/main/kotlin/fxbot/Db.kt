package fxbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

/**
 * H2 with its file cipher on. The password is the H2 two-part form:
 * file password, a space, then the user password.
 */
fun createDataSource(cfg: Config): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:file:${cfg.dbPath};CIPHER=AES;MODE=PostgreSQL"
        username = "sa"
        password = "${cfg.dbFileKey} ${cfg.dbUserPw}"
        maximumPoolSize = 4
    })

/** H2 support ships inside flyway-core; no database module is needed. */
fun migrate(ds: DataSource) {
    Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
}
