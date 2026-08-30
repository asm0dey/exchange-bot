package fxbot

/**
 * Prints a fresh pair of keysets. Equivalent to two `tinkey create-keyset`
 * calls, without needing tinkey installed. Generated keys go straight into the
 * environment and never into the repository.
 */
fun main() {
    println("DATA_KEYSET=${KeysetGen.aead()}")
    println("INDEX_KEYSET=${KeysetGen.mac()}")
    println()
    println("# Also set, to values of your own choosing:")
    println("# DB_FILE_KEY=  DB_USER_PW=  BOT_TOKEN=")
}
