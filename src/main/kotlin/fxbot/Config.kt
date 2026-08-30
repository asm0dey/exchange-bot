package fxbot

/** Exposed so callers can report config SHAPE (default vs overridden) without logging the value itself. */
const val DEFAULT_DB_PATH = "./data/exchange"

data class Config(
    val botToken: String,
    val dbPath: String,
    val dbFileKey: String,
    val dbUserPw: String,
    val dataKeyset: String,
    val indexKeyset: String,
) {
    /** Overrides the generated one, which would print every secret field verbatim. */
    override fun toString(): String = "Config(***)"
}

/**
 * Reads configuration from [env]. Every value is required except DB_PATH.
 * Fails naming the offending variable — and never quoting any value, because
 * five of the six are secrets.
 */
fun loadConfig(env: (String) -> String?): Config {
    fun required(name: String): String {
        val v = env(name)
        check(!v.isNullOrBlank()) { "$name environment variable is required" }
        return v
    }
    return Config(
        botToken = required("BOT_TOKEN"),
        dbPath = env("DB_PATH")?.takeIf { it.isNotBlank() } ?: DEFAULT_DB_PATH,
        dbFileKey = required("DB_FILE_KEY"),
        dbUserPw = required("DB_USER_PW"),
        dataKeyset = required("DATA_KEYSET"),
        indexKeyset = required("INDEX_KEYSET"),
    )
}
