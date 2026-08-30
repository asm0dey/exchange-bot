package fxbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
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
