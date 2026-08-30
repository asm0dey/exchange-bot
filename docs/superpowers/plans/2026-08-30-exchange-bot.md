# Exchange Bot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Telegram bot that introduces two people in the same group chat who want opposite sides of the same currency exchange, and gets out of the way.

**Architecture:** One JVM process, one encrypted embedded H2 file. Matching is a pure function run on demand — no background matcher, no reservation, no order book. Everything sensitive is stored as a Tink-AEAD payload; the two columns that must be queryable are keyed HMACs. All user interaction is Telegram commands plus inline buttons on the bot's own reply.

**Tech Stack:** Kotlin/JVM 21, `eu.vendeli:telegram-bot` 9.6.0 (+ `ktnip` KSP processor), H2 with `CIPHER=AES`, HikariCP, Flyway, db-scheduler, Ktor client CIO, Google Tink, kotest.

**Spec:** `docs/superpowers/specs/2026-08-30-exchange-bot-design.md`
**Glossary:** `CONTEXT.md` — use its vocabulary in all code and comments.
**Decisions:** `docs/adr/0001`–`0005`.

## Global Constraints

- Kotlin `2.4.10`, KSP `2.3.10`, JVM toolchain **21**, Gradle **9.6.1**.
- Pinned versions: telegram-bot `9.6.0`, ktor `3.5.1`, kotlinx-serialization `1.11.0`, coroutines `1.11.0`, db-scheduler `16.12.0`, h2 `2.4.240`, HikariCP `7.1.0`, slf4j-simple `2.0.18`, kotest `6.2.3`, Tink `1.18.0`, Flyway `11.8.2`.
- **H2 support ships inside `flyway-core`.** There is no `flyway-database-h2` artifact — do not add one.
- **H2 runs in PostgreSQL compatibility mode** (`;MODE=PostgreSQL` on every JDBC URL, production and test alike). Schema columns use `TEXT` and `BYTEA` — never a guessed `CHAR(n)` width. The only exception is db-scheduler's canonical table, which keeps the types that library ships.
- **No GraalVM native-image.** Deliberately excluded (ADR 0004).
- Package root: `fxbot`. Root project name: `exchange-bot`.
- **Vocabulary is binding** (ADR + `CONTEXT.md`): `Side.BID` / `Side.OFFER`, `counterparty`, `notional`, `resting`, `DONE`, `time in force`, `size tolerance`. The words **order**, **book**, **fill**, **execution** must not appear in identifiers, comments, or user strings.
- All user-facing strings are **plain English** — never `notional`, `bid`, `offer` in a chat message.
- Every secret comes from an environment variable and is never logged: `BOT_TOKEN`, `DB_FILE_KEY`, `DB_USER_PW`, `DATA_KEYSET`, `INDEX_KEYSET`. Startup fails non-zero naming the offender.
- Money is always `BigDecimal`. Never `Double`. Serialized as a JSON **string**.
- Base currency defines sides: **Offer gives the base currency, Bid receives it.**
- `callback_data` must stay ≤64 bytes and is never trusted — every callback re-authorizes from `callback_query.from.id`.

---

## File Structure

**Domain (pure, no I/O — the bulk of the tests):**
- `Side.kt` — `Side`, `CurrencyPair`, side derivation from verb + currency.
- `Money.kt` — amount parsing and display formatting.
- `Request.kt` — `Request`, `RequestState`.
- `Matcher.kt` — `notional()`, `findCounterparties()`.
- `Render.kt` — message text and inline keyboards; callback-data encode/decode.

**Infrastructure:**
- `Config.kt` — environment loading and validation.
- `Crypto.kt` — Tink AEAD + MAC; `seal`/`open`/`ref`/`newRefToken`.
- `Db.kt` — encrypted `DataSource`, Flyway runner.
- `RequestRepository.kt`, `ChatSettingsRepository.kt`, `MessageLogRepository.kt`, `RateRepository.kt`.
- `RateClient.kt` — Ktor call to the feed; `RateService.kt` — cache and degradation.

**Telegram surface:**
- `Registry.kt` — process-global wiring (matches the neighbour bot's pattern).
- `Commands.kt` — `/sell` `/buy` `/status` `/settings` `/help`.
- `LifecycleCommands.kt` — `/cancel` `/done` `/reopen` `/forget`.
- `AdminCommands.kt` — `/pair` `/tolerance` `/tif`.
- `Callbacks.kt` — button presses.
- `ChatMigration.kt` — supergroup migration handler.
- `Tasks.kt` — expiry sweep, rate refresh, message-log prune.
- `Main.kt` — entry point.

`src/main/resources/db/migration/V1__initial.sql` holds the schema.

Tests mirror main under `src/test/kotlin/fxbot/`.

---

### Task 1: Project skeleton and configuration

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `.gitignore`, `.env.example`
- Create: `src/main/kotlin/fxbot/Config.kt`
- Test: `src/test/kotlin/fxbot/ConfigTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `data class Config(botToken, dbPath, dbFileKey, dbUserPw, dataKeyset, indexKeyset)` and `fun loadConfig(env: (String) -> String?): Config`.

- [ ] **Step 1: Copy the Gradle wrapper from the neighbour project**

```bash
mkdir -p gradle/wrapper src/main/kotlin/fxbot src/test/kotlin/fxbot src/main/resources/db/migration
cp -r ../conference-notifier-bot/gradle/wrapper/* gradle/wrapper/
cp ../conference-notifier-bot/gradlew ../conference-notifier-bot/gradlew.bat .
chmod +x gradlew
grep distributionUrl gradle/wrapper/gradle-wrapper.properties   # expect gradle-9.6.1-bin.zip
```

- [ ] **Step 2: Write the version catalog**

`gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.4.10"
ksp = "2.3.10"
telegrambot = "9.6.0"
ktor = "3.5.1"
serialization = "1.11.0"
coroutines = "1.11.0"
dbscheduler = "16.12.0"
h2 = "2.4.240"
hikari = "7.1.0"
slf4j = "2.0.18"
kotest = "6.2.3"
tink = "1.18.0"
flyway = "11.8.2"

[libraries]
telegram-bot = { module = "eu.vendeli:telegram-bot", version.ref = "telegrambot" }
ktnip = { module = "eu.vendeli:ktnip", version.ref = "telegrambot" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
db-scheduler = { module = "com.github.kagkarlsson:db-scheduler", version.ref = "dbscheduler" }
h2 = { module = "com.h2database:h2", version.ref = "h2" }
hikari = { module = "com.zaxxer:HikariCP", version.ref = "hikari" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
tink = { module = "com.google.crypto.tink:tink", version.ref = "tink" }
flyway-core = { module = "org.flywaydb:flyway-core", version.ref = "flyway" }
kotest-runner = { module = "io.kotest:kotest-runner-junit5", version.ref = "kotest" }
kotest-assertions = { module = "io.kotest:kotest-assertions-core", version.ref = "kotest" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: Write the build files**

`settings.gradle.kts`:

```kotlin
rootProject.name = "exchange-bot"
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(libs.telegram.bot)
    ksp(libs.ktnip)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.db.scheduler)
    implementation(libs.h2)
    implementation(libs.hikari)
    implementation(libs.tink)
    implementation(libs.flyway.core)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.ktor.client.mock)
}

kotlin { jvmToolchain(21) }

application { mainClass.set("fxbot.MainKt") }

tasks.test { useJUnitPlatform() }
```

`.gitignore`:

```
.gradle/
build/
data/
.env
*.mv.db
*.trace.db
```

`.env.example`:

```
BOT_TOKEN=
DB_PATH=./data/exchange
DB_FILE_KEY=
DB_USER_PW=
DATA_KEYSET=
INDEX_KEYSET=
```

- [ ] **Step 4: Write the failing test**

`src/test/kotlin/fxbot/ConfigTest.kt`:

```kotlin
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
})
```

- [ ] **Step 5: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.ConfigTest'`
Expected: FAIL — unresolved reference `loadConfig`.

- [ ] **Step 6: Implement**

`src/main/kotlin/fxbot/Config.kt`:

```kotlin
package fxbot

data class Config(
    val botToken: String,
    val dbPath: String,
    val dbFileKey: String,
    val dbUserPw: String,
    val dataKeyset: String,
    val indexKeyset: String,
)

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
        dbPath = env("DB_PATH")?.takeIf { it.isNotBlank() } ?: "./data/exchange",
        dbFileKey = required("DB_FILE_KEY"),
        dbUserPw = required("DB_USER_PW"),
        dataKeyset = required("DATA_KEYSET"),
        indexKeyset = required("INDEX_KEYSET"),
    )
}
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew test --tests 'fxbot.ConfigTest'`
Expected: PASS, 5 tests.

- [ ] **Step 8: Commit**

```bash
git add gradle gradlew gradlew.bat settings.gradle.kts build.gradle.kts .gitignore .env.example src
git commit -m "feat: project skeleton and validated configuration"
```

---

### Task 2: Crypto

**Files:**
- Create: `src/main/kotlin/fxbot/Crypto.kt`
- Test: `src/test/kotlin/fxbot/CryptoTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `class Crypto(dataKeysetJson: String, indexKeysetJson: String)` with `seal(plaintext: String, aad: String): ByteArray`, `open(ciphertext: ByteArray, aad: String): String`, `ref(value: String): String`; plus `fun newRefToken(): String` and, for tests and `tinkey`-free bootstrapping, `object KeysetGen { fun aead(): String; fun mac(): String }`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/CryptoTest.kt`:

```kotlin
package fxbot

import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class CryptoTest : StringSpec({
    val crypto = Crypto(KeysetGen.aead(), KeysetGen.mac())

    "round-trips a payload under its associated data" {
        val sealed = crypto.seal("""{"username":"alice"}""", "tok-1")
        crypto.open(sealed, "tok-1") shouldBe """{"username":"alice"}"""
    }
    "refuses to open under different associated data" {
        val sealed = crypto.seal("secret", "tok-1")
        shouldThrowAny { crypto.open(sealed, "tok-2") }
    }
    "refuses to open a tampered ciphertext" {
        val sealed = crypto.seal("secret", "tok-1")
        sealed[sealed.size - 1] = (sealed[sealed.size - 1] + 1).toByte()
        shouldThrowAny { crypto.open(sealed, "tok-1") }
    }
    "refuses to open under a different key" {
        val other = Crypto(KeysetGen.aead(), KeysetGen.mac())
        shouldThrowAny { other.open(crypto.seal("secret", "tok-1"), "tok-1") }
    }
    "encrypts the same plaintext to different ciphertexts" {
        crypto.seal("same", "tok-1") shouldNotBe crypto.seal("same", "tok-1")
    }
    "refs are deterministic and key-dependent" {
        crypto.ref("-1001") shouldBe crypto.ref("-1001")
        crypto.ref("-1001") shouldNotBe crypto.ref("-1002")
        Crypto(KeysetGen.aead(), KeysetGen.mac()).ref("-1001") shouldNotBe crypto.ref("-1001")
    }
    "a ref is a stable non-empty string" {
        // Columns are TEXT, so no width is asserted — Tink prepends a key-identity
        // prefix whose size is not ours to hard-code.
        crypto.ref("-1001").isNotEmpty() shouldBe true
        crypto.ref("-1001") shouldBe crypto.ref("-1001")
    }
    "ref tokens are 22 characters and unique" {
        val tokens = List(1000) { newRefToken() }
        tokens.toSet().size shouldBe 1000
        tokens.all { it.length == 22 } shouldBe true
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.CryptoTest'`
Expected: FAIL — unresolved reference `Crypto`.

- [ ] **Step 3: Implement**

`src/main/kotlin/fxbot/Crypto.kt`:

```kotlin
package fxbot

import com.google.crypto.tink.Aead
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.Mac
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.mac.MacConfig
import com.google.crypto.tink.mac.PredefinedMacParameters
import java.security.SecureRandom
import java.util.Base64

private val B64URL: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
private val RANDOM = SecureRandom()

/**
 * The two keysets never share key material: [seal]/[open] protect the payload,
 * [ref] derives the searchable identifiers. See ADR 0002.
 */
class Crypto(dataKeysetJson: String, indexKeysetJson: String) {
    private val aead: Aead
    private val mac: Mac

    init {
        AeadConfig.register()
        MacConfig.register()
        aead = parse(dataKeysetJson).getPrimitive(Aead::class.java)
        mac = parse(indexKeysetJson).getPrimitive(Mac::class.java)
    }

    fun seal(plaintext: String, aad: String): ByteArray =
        aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), aad.toByteArray(Charsets.UTF_8))

    fun open(ciphertext: ByteArray, aad: String): String =
        String(aead.decrypt(ciphertext, aad.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    /** Deterministic and keyed. Stored in a TEXT column — no width is assumed. */
    fun ref(value: String): String =
        Base64.getEncoder().encodeToString(mac.computeMac(value.toByteArray(Charsets.UTF_8)))

    private fun parse(json: String): KeysetHandle =
        TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get())
}

/** 128 bits of randomness, base64url, never shown in a chat. */
fun newRefToken(): String = B64URL.encodeToString(ByteArray(16).also(RANDOM::nextBytes))

/**
 * Generates keysets equivalent to `tinkey create-keyset`. Used by tests, and by
 * the `keygen` helper so a first deploy needs no extra tooling.
 */
object KeysetGen {
    fun aead(): String {
        AeadConfig.register()
        return serialize(KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM))
    }

    fun mac(): String {
        MacConfig.register()
        return serialize(KeysetHandle.generateNew(PredefinedMacParameters.HMAC_SHA256_256BITTAG))
    }

    private fun serialize(handle: KeysetHandle): String =
        TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'fxbot.CryptoTest'`
Expected: PASS, 8 tests.

If `getPrimitive(Aead::class.java)` is rejected as removed in 1.18, the replacement is
`getPrimitive(com.google.crypto.tink.RegistryConfiguration.get(), Aead::class.java)`;
apply the same change to the `Mac` line. Both forms take the same arguments otherwise.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/fxbot/Crypto.kt src/test/kotlin/fxbot/CryptoTest.kt
git commit -m "feat: Tink AEAD payload sealing and keyed identifier refs"
```

---

### Task 3: Sides, pairs, and money

**Files:**
- Create: `src/main/kotlin/fxbot/Side.kt`, `src/main/kotlin/fxbot/Money.kt`
- Test: `src/test/kotlin/fxbot/SideTest.kt`, `src/test/kotlin/fxbot/MoneyTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class Side { BID, OFFER }`; `enum class Verb { SELL, BUY }`; `data class CurrencyPair(val base: String, val quote: String)` with `fun contains(ccy: String): Boolean` and `fun other(ccy: String): String`; `fun sideFor(verb: Verb, statedCurrency: String, pair: CurrencyPair): Side`; `fun parseCurrency(raw: String): String?`; `fun parseAmount(raw: String): BigDecimal?`; `fun formatAmount(v: BigDecimal): String`; `fun formatNotional(v: BigDecimal): String`.

- [ ] **Step 1: Write the failing side test**

`src/test/kotlin/fxbot/SideTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val EURRUB = CurrencyPair("EUR", "RUB")

class SideTest : StringSpec({
    // The spec's side table. Offer gives the base currency; Bid receives it.
    "sell base is an offer" { sideFor(Verb.SELL, "EUR", EURRUB) shouldBe Side.OFFER }
    "buy quote is an offer" { sideFor(Verb.BUY, "RUB", EURRUB) shouldBe Side.OFFER }
    "sell quote is a bid" { sideFor(Verb.SELL, "RUB", EURRUB) shouldBe Side.BID }
    "buy base is a bid" { sideFor(Verb.BUY, "EUR", EURRUB) shouldBe Side.BID }

    "the probe cases from the spec land on opposite sides" {
        sideFor(Verb.SELL, "EUR", EURRUB) shouldBe Side.OFFER
        sideFor(Verb.BUY, "EUR", EURRUB) shouldBe Side.BID
        sideFor(Verb.BUY, "RUB", EURRUB) shouldBe Side.OFFER
        sideFor(Verb.SELL, "RUB", EURRUB) shouldBe Side.BID
    }
    "sell base and buy quote are the SAME side and must not match" {
        sideFor(Verb.SELL, "EUR", EURRUB) shouldBe sideFor(Verb.BUY, "RUB", EURRUB)
    }

    "pair membership and the other leg" {
        EURRUB.contains("EUR") shouldBe true
        EURRUB.contains("USD") shouldBe false
        EURRUB.other("EUR") shouldBe "RUB"
        EURRUB.other("RUB") shouldBe "EUR"
    }

    "currency codes are upper-cased and validated against ISO 4217" {
        parseCurrency("eur") shouldBe "EUR"
        parseCurrency(" RUB ") shouldBe "RUB"
        parseCurrency("XYZ").shouldBeNull()
        parseCurrency("euro").shouldBeNull()
    }
})
```

- [ ] **Step 2: Write the failing money test**

`src/test/kotlin/fxbot/MoneyTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class MoneyTest : StringSpec({
    "parses plain and grouped amounts" {
        parseAmount("1000") shouldBe BigDecimal("1000")
        parseAmount("1 000") shouldBe BigDecimal("1000")
        parseAmount("1,000.50") shouldBe BigDecimal("1000.50")
        parseAmount("0.5") shouldBe BigDecimal("0.5")
    }
    "rejects amounts that are not positive numbers" {
        parseAmount("0").shouldBeNull()
        parseAmount("-5").shouldBeNull()
        parseAmount("abc").shouldBeNull()
        parseAmount("").shouldBeNull()
        parseAmount("1e9").shouldBeNull()
    }
    "formats stated amounts with grouping and no trailing zeros" {
        formatAmount(BigDecimal("1000")) shouldBe "1,000"
        formatAmount(BigDecimal("1000.00")) shouldBe "1,000"
        formatAmount(BigDecimal("95000")) shouldBe "95,000"
        formatAmount(BigDecimal("1000.50")) shouldBe "1,000.5"
    }
    "formats notionals at two decimal places" {
        formatNotional(BigDecimal("1000")) shouldBe "1,000.00"
        formatNotional(BigDecimal("950.1876")) shouldBe "950.19"
    }
})
```

- [ ] **Step 3: Run both and watch them fail**

Run: `./gradlew test --tests 'fxbot.SideTest' --tests 'fxbot.MoneyTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 4: Implement sides**

`src/main/kotlin/fxbot/Side.kt`:

```kotlin
package fxbot

import java.util.Currency

/** Which way a request runs, always relative to the pair's base currency. */
enum class Side { BID, OFFER }

/** What the person typed, before it is turned into a side. */
enum class Verb { SELL, BUY }

data class CurrencyPair(val base: String, val quote: String) {
    fun contains(ccy: String) = ccy == base || ccy == quote
    fun other(ccy: String) = if (ccy == base) quote else base
    override fun toString() = "$base/$quote"
}

/**
 * You are offering exactly when you hand over the base currency:
 * selling the base, or buying the quote (which you pay for in base).
 */
fun sideFor(verb: Verb, statedCurrency: String, pair: CurrencyPair): Side {
    val givesBase = if (verb == Verb.SELL) statedCurrency == pair.base else statedCurrency == pair.quote
    return if (givesBase) Side.OFFER else Side.BID
}

/** Upper-cases and checks the code is ISO 4217. Returns null if it is not. */
fun parseCurrency(raw: String): String? {
    val code = raw.trim().uppercase()
    if (code.length != 3) return null
    return runCatching { Currency.getInstance(code).currencyCode }.getOrNull()
}
```

- [ ] **Step 5: Implement money**

`src/main/kotlin/fxbot/Money.kt`:

```kotlin
package fxbot

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val SYMBOLS = DecimalFormatSymbols(Locale.US)
private val GROUPED = DecimalFormat("#,##0.##########", SYMBOLS)
private val TWO_DP = DecimalFormat("#,##0.00", SYMBOLS)

/** Accepts `1000`, `1 000`, `1,000.50`. Rejects anything not a positive plain number. */
fun parseAmount(raw: String): BigDecimal? {
    val cleaned = raw.trim().replace(" ", "").replace(",", "")
    if (cleaned.isEmpty()) return null
    if (!cleaned.all { it.isDigit() || it == '.' }) return null
    val value = runCatching { BigDecimal(cleaned) }.getOrNull() ?: return null
    return value.takeIf { it > BigDecimal.ZERO }
}

/** Stated amounts: grouped, trailing zeros stripped. */
fun formatAmount(v: BigDecimal): String = GROUPED.format(v.stripTrailingZeros())

/** Notionals: grouped, always two decimal places. */
fun formatNotional(v: BigDecimal): String = TWO_DP.format(v.setScale(2, RoundingMode.HALF_UP))
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests 'fxbot.SideTest' --tests 'fxbot.MoneyTest'`
Expected: PASS, 12 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/fxbot/Side.kt src/main/kotlin/fxbot/Money.kt src/test/kotlin/fxbot/SideTest.kt src/test/kotlin/fxbot/MoneyTest.kt
git commit -m "feat: side derivation, currency validation, money parsing and formatting"
```

---

### Task 4: The matcher

**Files:**
- Create: `src/main/kotlin/fxbot/Request.kt`, `src/main/kotlin/fxbot/Matcher.kt`
- Test: `src/test/kotlin/fxbot/MatcherTest.kt`

**Interfaces:**
- Consumes: `Side`, `CurrencyPair` (Task 3).
- Produces: `enum class RequestState { OPEN, DONE, CANCELLED, EXPIRED }`; `data class Request(rowId, refToken, chatId, userId, username, shortId, side, statedCurrency, statedAmount, pair, state, createdAt, expiresAt)`; `fun notional(r: Request, rate: BigDecimal?): BigDecimal?`; `data class Counterparty(val request: Request, val notional: BigDecimal?, val distance: BigDecimal)`; `fun findCounterparties(subject: Request, resting: List<Request>, rate: BigDecimal?, tolerancePct: Int, limit: Int = 5): List<Counterparty>`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/MatcherTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")
private val RATE = BigDecimal("99.98")

private var seq = 0L
private fun req(
    verb: Verb,
    amount: String,
    ccy: String,
    userId: Long = ++seq,
    chatId: Long = -100L,
    state: RequestState = RequestState.OPEN,
    pair: CurrencyPair = EURRUB,
) = Request(
    rowId = ++seq,
    refToken = "tok${seq}".padEnd(22, 'x'),
    chatId = chatId,
    userId = userId,
    username = "u$userId",
    shortId = "a${seq % 10}",
    side = sideFor(verb, ccy, pair),
    statedCurrency = ccy,
    statedAmount = BigDecimal(amount),
    pair = pair,
    state = state,
    createdAt = Instant.EPOCH,
    expiresAt = Instant.EPOCH.plusSeconds(604_800),
)

class MatcherTest : StringSpec({
    "notional passes a base amount straight through" {
        notional(req(Verb.SELL, "1000", "EUR"), RATE) shouldBe BigDecimal("1000")
    }
    "notional divides a quote amount by the rate" {
        val n = notional(req(Verb.SELL, "95000", "RUB"), RATE)!!
        n.setScale(2, java.math.RoundingMode.HALF_UP) shouldBe BigDecimal("950.19")
    }
    "notional of a quote amount is unknown without a rate" {
        notional(req(Verb.SELL, "95000", "RUB"), null) shouldBe null
    }

    // The spec's four worked cases.
    "sell 999 EUR matches buy 1000 EUR" {
        val subject = req(Verb.SELL, "999", "EUR")
        findCounterparties(subject, listOf(req(Verb.BUY, "1000", "EUR")), RATE, 20) shouldHaveSize 1
    }
    "buy 100 RUB matches buy 1 EUR" {
        val subject = req(Verb.BUY, "100", "RUB")
        findCounterparties(subject, listOf(req(Verb.BUY, "1", "EUR")), RATE, 20) shouldHaveSize 1
    }
    "buy 20 RUB matches sell 20 RUB" {
        val subject = req(Verb.BUY, "20", "RUB")
        findCounterparties(subject, listOf(req(Verb.SELL, "20", "RUB")), RATE, 20) shouldHaveSize 1
    }
    "sell 1000 EUR does NOT match buy 95000 RUB — same side" {
        val subject = req(Verb.SELL, "1000", "EUR")
        findCounterparties(subject, listOf(req(Verb.BUY, "95000", "RUB")), RATE, 20).shouldBeEmpty()
    }

    "the size tolerance boundary is inclusive" {
        val subject = req(Verb.SELL, "1000", "EUR")
        // 800 is exactly 20% below 1000 when measured against the larger.
        findCounterparties(subject, listOf(req(Verb.BUY, "800", "EUR")), RATE, 20) shouldHaveSize 1
        findCounterparties(subject, listOf(req(Verb.BUY, "799", "EUR")), RATE, 20).shouldBeEmpty()
    }

    "never matches the same person with themselves" {
        val subject = req(Verb.SELL, "1000", "EUR", userId = 7)
        findCounterparties(subject, listOf(req(Verb.BUY, "1000", "EUR", userId = 7)), RATE, 20).shouldBeEmpty()
    }
    "never matches another chat" {
        val subject = req(Verb.SELL, "1000", "EUR", chatId = -100)
        findCounterparties(subject, listOf(req(Verb.BUY, "1000", "EUR", chatId = -200)), RATE, 20).shouldBeEmpty()
    }
    "never matches a request on another pair" {
        val subject = req(Verb.SELL, "1000", "EUR")
        val other = req(Verb.BUY, "1000", "EUR", pair = CurrencyPair("EUR", "USD"))
        findCounterparties(subject, listOf(other), RATE, 20).shouldBeEmpty()
    }
    "never matches a request that is not resting" {
        val subject = req(Verb.SELL, "1000", "EUR")
        val closed = req(Verb.BUY, "1000", "EUR", state = RequestState.DONE)
        findCounterparties(subject, listOf(closed), RATE, 20).shouldBeEmpty()
    }

    "closest size first, capped at five" {
        val subject = req(Verb.SELL, "1000", "EUR")
        val resting = listOf("1100", "1010", "900", "1050", "950", "1001").map { req(Verb.BUY, it, "EUR") }
        val found = findCounterparties(subject, resting, RATE, 20)
        found shouldHaveSize 5
        found.first().request.statedAmount shouldBe BigDecimal("1001")
    }

    "without a rate, same-denomination requests still match" {
        val subject = req(Verb.SELL, "95000", "RUB")
        findCounterparties(subject, listOf(req(Verb.BUY, "90000", "RUB")), null, 20) shouldHaveSize 1
    }
    "without a rate, cross-denomination requests do not match" {
        val subject = req(Verb.SELL, "1000", "EUR")
        findCounterparties(subject, listOf(req(Verb.SELL, "95000", "RUB")), null, 20).shouldBeEmpty()
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.MatcherTest'`
Expected: FAIL — unresolved reference `Request`.

- [ ] **Step 3: Implement the request type**

`src/main/kotlin/fxbot/Request.kt`:

```kotlin
package fxbot

import java.math.BigDecimal
import java.time.Instant

enum class RequestState {
    OPEN, DONE, CANCELLED, EXPIRED;

    val isTerminal get() = this != OPEN
}

/**
 * Someone's stated willingness to exchange. Amounts are held exactly as typed;
 * the notional is derived when requests are compared (ADR 0003).
 */
data class Request(
    val rowId: Long,
    val refToken: String,
    val chatId: Long,
    val userId: Long,
    val username: String?,
    val shortId: String,
    val side: Side,
    val statedCurrency: String,
    val statedAmount: BigDecimal,
    val pair: CurrencyPair,
    val state: RequestState,
    val createdAt: Instant,
    val expiresAt: Instant,
)
```

- [ ] **Step 4: Implement the matcher**

`src/main/kotlin/fxbot/Matcher.kt`:

```kotlin
package fxbot

import java.math.BigDecimal
import java.math.MathContext

private val MC = MathContext.DECIMAL64
private val HUNDRED = BigDecimal(100)

/**
 * A request's size in the pair's base currency, or null when it cannot be known —
 * a quote-denominated amount with no reference rate available.
 */
fun notional(r: Request, rate: BigDecimal?): BigDecimal? = when {
    r.statedCurrency == r.pair.base -> r.statedAmount
    rate == null -> null
    else -> r.statedAmount.divide(rate, MC)
}

data class Counterparty(val request: Request, val notional: BigDecimal?, val distance: BigDecimal)

/**
 * Every resting request in the same chat that is on the opposite side and close
 * enough in size, closest first. Reserves nothing (ADR 0001).
 *
 * With no reference rate, only requests quoted in the same currency as the
 * subject can be compared — that comparison needs no conversion.
 */
fun findCounterparties(
    subject: Request,
    resting: List<Request>,
    rate: BigDecimal?,
    tolerancePct: Int,
    limit: Int = 5,
): List<Counterparty> {
    val limitFraction = BigDecimal(tolerancePct).divide(HUNDRED, MC)
    return resting.asSequence()
        .filter { it.chatId == subject.chatId }
        .filter { it.pair == subject.pair }
        .filter { it.state == RequestState.OPEN }
        .filter { it.side != subject.side }
        .filter { it.userId != subject.userId }
        .mapNotNull { candidate ->
            val (a, b) = comparableSizes(subject, candidate, rate) ?: return@mapNotNull null
            val larger = a.max(b)
            if (larger.signum() == 0) return@mapNotNull null
            val distance = (a - b).abs().divide(larger, MC)
            if (distance > limitFraction) null
            else Counterparty(candidate, notional(candidate, rate), distance)
        }
        .sortedBy { it.distance }
        .take(limit)
        .toList()
}

/**
 * The two magnitudes to compare. Prefers notionals; falls back to raw stated
 * amounts when both requests are quoted in the same currency and no rate exists.
 */
private fun comparableSizes(
    subject: Request,
    candidate: Request,
    rate: BigDecimal?,
): Pair<BigDecimal, BigDecimal>? {
    val a = notional(subject, rate)
    val b = notional(candidate, rate)
    if (a != null && b != null) return a to b
    if (subject.statedCurrency == candidate.statedCurrency) {
        return subject.statedAmount to candidate.statedAmount
    }
    return null
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests 'fxbot.MatcherTest'`
Expected: PASS, 15 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/fxbot/Request.kt src/main/kotlin/fxbot/Matcher.kt src/test/kotlin/fxbot/MatcherTest.kt
git commit -m "feat: notional derivation and counterparty matching"
```

---

### Task 5: Encrypted database and the request repository

**Files:**
- Create: `src/main/kotlin/fxbot/Db.kt`, `src/main/kotlin/fxbot/RequestRepository.kt`
- Create: `src/main/resources/db/migration/V1__initial.sql`
- Test: `src/test/kotlin/fxbot/TestDb.kt`, `src/test/kotlin/fxbot/RequestRepositoryTest.kt`

**Interfaces:**
- Consumes: `Config` (1), `Crypto` (2), `Request`/`RequestState` (4).
- Produces: `fun createDataSource(cfg: Config): HikariDataSource`; `fun migrate(ds: DataSource)`; and `class RequestRepository(ds: DataSource, crypto: Crypto, clock: Clock)` with
  `create(chatId, userId, username, side, statedCurrency, statedAmount, pair, tifDays): Request`,
  `resting(chatId: Long): List<Request>`,
  `byRefToken(token: String): Request?`,
  `byShortId(chatId: Long, shortId: String): Request?`,
  `transition(refToken: String, from: RequestState, to: RequestState): Boolean`,
  `mostRecentlyClosed(chatId: Long, userId: Long): Request?`,
  `expireDue(now: Instant): Int`,
  `deleteFor(userId: Long, chatId: Long?): List<String>`,
  `rewriteChatRef(oldChatId: Long, newChatId: Long): Int`.

- [ ] **Step 1: Write the schema**

`src/main/resources/db/migration/V1__initial.sql`:

```sql
CREATE TABLE request (
    row_id      BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    ref_token   TEXT      NOT NULL,
    chat_ref    TEXT      NOT NULL,
    user_ref    TEXT      NOT NULL,
    short_id    TEXT      NOT NULL,
    state       TEXT      NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP NOT NULL,
    payload     BYTEA     NOT NULL
);
CREATE UNIQUE INDEX request_ref_token_idx ON request (ref_token);
CREATE INDEX request_chat_state_idx ON request (chat_ref, state);
CREATE INDEX request_user_idx ON request (user_ref);

CREATE TABLE chat_settings (
    chat_ref   TEXT PRIMARY KEY,
    payload    BYTEA NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE fx_rate (
    base       TEXT NOT NULL,
    quote      TEXT NOT NULL,
    rate       DECIMAL(30, 10) NOT NULL,
    fetched_at TIMESTAMP NOT NULL,
    PRIMARY KEY (base, quote)
);

CREATE TABLE sent_message (
    chat_ref   TEXT      NOT NULL,
    message_id BIGINT    NOT NULL,
    sent_at    TIMESTAMP NOT NULL,
    PRIMARY KEY (chat_ref, message_id)
);

CREATE TABLE sent_message_ref (
    chat_ref   TEXT   NOT NULL,
    message_id BIGINT NOT NULL,
    ref_token  TEXT   NOT NULL,
    user_ref   TEXT   NOT NULL
);
CREATE INDEX sent_message_ref_token_idx ON sent_message_ref (ref_token);
CREATE INDEX sent_message_ref_user_idx ON sent_message_ref (user_ref);

-- db-scheduler 16.x canonical schema. task_data stays empty by design (ADR: tasks
-- are parameterless so nothing sensitive lands in this unencrypted BLOB).
CREATE TABLE scheduled_tasks (
    task_name            VARCHAR(255) NOT NULL,
    task_instance        VARCHAR(255) NOT NULL,
    task_data            BLOB,
    execution_time       TIMESTAMP WITH TIME ZONE NOT NULL,
    picked               BOOLEAN NOT NULL,
    picked_by            VARCHAR(50),
    last_success         TIMESTAMP WITH TIME ZONE,
    last_failure         TIMESTAMP WITH TIME ZONE,
    consecutive_failures INT,
    last_heartbeat       TIMESTAMP WITH TIME ZONE,
    version              BIGINT NOT NULL,
    priority             SMALLINT,
    PRIMARY KEY (task_name, task_instance)
);
CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
```

- [ ] **Step 2: Write the failing test**

`src/test/kotlin/fxbot/TestDb.kt`:

```kotlin
package fxbot

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

/** In-memory, unencrypted: the file cipher is a deployment concern, not a logic one. */
fun memDataSource(name: String): HikariDataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
        username = "sa"
        password = ""
        maximumPoolSize = 2
    })

fun testCrypto() = Crypto(KeysetGen.aead(), KeysetGen.mac())
```

`src/test/kotlin/fxbot/RequestRepositoryTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun repo(name: String, clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)): Pair<RequestRepository, javax.sql.DataSource> {
    val ds = memDataSource(name)
    migrate(ds)
    return RequestRepository(ds, testCrypto(), clock) to ds
}

class RequestRepositoryTest : StringSpec({
    "creates and reads back a resting request" {
        val (r, _) = repo("create")
        val created = r.create(-100L, 7L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        created.shortId shouldBe "a"
        created.expiresAt shouldBe T0.plusSeconds(7 * 86_400)

        val resting = r.resting(-100L)
        resting shouldHaveSize 1
        resting[0].username shouldBe "alice"
        resting[0].statedAmount shouldBe BigDecimal("1000")
        resting[0].side shouldBe Side.OFFER
        resting[0].pair shouldBe EURRUB
    }

    "the payload never holds a username in the clear" {
        val (r, ds) = repo("opaque")
        r.create(-100L, 7L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        ds.connection.use { c ->
            c.prepareStatement("SELECT payload FROM request").executeQuery().use { rs ->
                rs.next() shouldBe true
                val raw = String(rs.getBytes(1), Charsets.ISO_8859_1)
                raw.contains("alice") shouldBe false
                raw.contains("EUR") shouldBe false
            }
        }
    }

    "short ids are allocated in order and recycled after closing" {
        val (r, _) = repo("shortids")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val b = r.create(-100L, 2L, "b", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        a.shortId shouldBe "a"
        b.shortId shouldBe "b"

        r.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED) shouldBe true
        r.create(-100L, 3L, "c", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7).shortId shouldBe "a"
    }

    "short ids are per chat" {
        val (r, _) = repo("perchat")
        r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7).shortId shouldBe "a"
        r.create(-200L, 2L, "b", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7).shortId shouldBe "a"
    }

    "a transition only fires from the expected state" {
        val (r, _) = repo("transition")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.transition(a.refToken, RequestState.OPEN, RequestState.DONE) shouldBe true
        r.transition(a.refToken, RequestState.OPEN, RequestState.DONE) shouldBe false
        r.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
    }

    "finds by short id only within the chat" {
        val (r, _) = repo("byshort")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.byShortId(-100L, "a")!!.refToken shouldBe a.refToken
        r.byShortId(-200L, "a").shouldBeNull()
    }

    "most recently closed finds the caller's own last closure" {
        val (r, _) = repo("recent")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val b = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("2"), EURRUB, 7)
        r.transition(a.refToken, RequestState.OPEN, RequestState.DONE)
        r.transition(b.refToken, RequestState.OPEN, RequestState.CANCELLED)
        r.mostRecentlyClosed(-100L, 1L)!!.refToken shouldBe b.refToken
        r.mostRecentlyClosed(-100L, 999L).shouldBeNull()
    }

    "expiry sweeps only what is due" {
        val (r, _) = repo("expiry")
        r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.expireDue(T0.plusSeconds(6 * 86_400)) shouldBe 0
        r.expireDue(T0.plusSeconds(8 * 86_400)) shouldBe 1
        r.resting(-100L) shouldHaveSize 0
    }

    "forgetting removes rows in one chat, or everywhere" {
        val (r, _) = repo("forget")
        r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.deleteFor(1L, -100L) shouldHaveSize 1
        r.resting(-200L) shouldHaveSize 1
        r.deleteFor(1L, null) shouldHaveSize 1
        r.resting(-200L) shouldHaveSize 0
    }

    "a chat migration keeps payloads readable" {
        val (r, _) = repo("migrate")
        r.create(-100L, 1L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        r.rewriteChatRef(-100L, -1001L) shouldBe 1
        r.resting(-100L) shouldHaveSize 0
        val moved = r.resting(-1001L)
        moved shouldHaveSize 1
        moved[0].username shouldBe "alice"
        moved[0].chatId shouldBe -1001L
    }

    "byRefToken carries the chat id, because the payload holds it" {
        val (r, _) = repo("chatid")
        val a = r.create(-100L, 7L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        r.byRefToken(a.refToken)!!.chatId shouldBe -100L
    }

    "byRefToken returns null for an unknown token" {
        val (r, _) = repo("unknown")
        r.byRefToken("nope".padEnd(22, 'x')).shouldBeNull()
    }

    "resting excludes closed requests" {
        val (r, _) = repo("resting")
        val a = r.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        r.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        r.resting(-100L).shouldHaveSize(0)
        r.byRefToken(a.refToken).shouldNotBeNull()
    }
})
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.RequestRepositoryTest'`
Expected: FAIL — unresolved reference `migrate`.

- [ ] **Step 4: Implement the datasource and migration runner**

`src/main/kotlin/fxbot/Db.kt`:

```kotlin
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
```

- [ ] **Step 5: Implement the repository**

`src/main/kotlin/fxbot/RequestRepository.kt`:

```kotlin
package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

@Serializable
private data class Payload(
    val chatId: Long,          // stored so a MAC-keyset rotation can re-derive chat_ref
    val userId: Long,
    val username: String?,
    val side: String,
    val statedCurrency: String,
    val statedAmount: String,   // string, never a JSON number — see Global Constraints
    val base: String,
    val quote: String,
)

/** Base32-ish labels, shortest first: a…z, then a0…z9. */
private val SHORT_IDS: List<String> =
    ('a'..'z').map { it.toString() } + ('a'..'z').flatMap { c -> ('0'..'9').map { "$c$it" } }

class RequestRepository(
    private val ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(
        chatId: Long,
        userId: Long,
        username: String?,
        side: Side,
        statedCurrency: String,
        statedAmount: BigDecimal,
        pair: CurrencyPair,
        tifDays: Int,
    ): Request = ds.connection.use { c ->
        c.autoCommit = false
        try {
            val chatRef = crypto.ref(chatId.toString())
            val shortId = allocateShortId(c, chatRef)
            val refToken = newRefToken()
            val now = clock.instant()
            val expires = now.plusSeconds(tifDays.toLong() * 86_400)
            val payload = Payload(
                chatId, userId, username, side.name, statedCurrency,
                statedAmount.toPlainString(), pair.base, pair.quote,
            )
            c.prepareStatement(
                """
                INSERT INTO request (ref_token, chat_ref, user_ref, short_id, state,
                                     created_at, expires_at, payload)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?)
                """.trimIndent()
            ).use { st ->
                st.setString(1, refToken)
                st.setString(2, chatRef)
                st.setString(3, crypto.ref(userId.toString()))
                st.setString(4, shortId)
                st.setTimestamp(5, Timestamp.from(now))
                st.setTimestamp(6, Timestamp.from(expires))
                st.setBytes(7, crypto.seal(json.encodeToString(payload), refToken))
                st.executeUpdate()
            }
            c.commit()
            Request(
                rowId = 0, refToken = refToken, chatId = chatId, userId = userId,
                username = username, shortId = shortId, side = side,
                statedCurrency = statedCurrency, statedAmount = statedAmount, pair = pair,
                state = RequestState.OPEN, createdAt = now, expiresAt = expires,
            )
        } catch (e: Exception) {
            c.rollback(); throw e
        }
    }

    /**
     * Short ids are unique only among a chat's live requests, so they can stay
     * short. H2 has no partial unique index, so the guard is this allocation
     * running inside the insert transaction.
     */
    private fun allocateShortId(c: Connection, chatRef: String): String {
        val taken = mutableSetOf<String>()
        c.prepareStatement("SELECT short_id FROM request WHERE chat_ref = ? AND state = 'OPEN' FOR UPDATE").use { st ->
            st.setString(1, chatRef)
            st.executeQuery().use { rs -> while (rs.next()) taken += rs.getString(1) }
        }
        return SHORT_IDS.firstOrNull { it !in taken }
            ?: error("this chat has more resting requests than there are short ids")
    }

    fun resting(chatId: Long): List<Request> = query(
        "SELECT * FROM request WHERE chat_ref = ? AND state = 'OPEN' ORDER BY expires_at"
    ) { st -> st.setString(1, crypto.ref(chatId.toString())) }

    fun byRefToken(token: String): Request? =
        queryOne("SELECT * FROM request WHERE ref_token = ?") { st -> st.setString(1, token) }

    fun byShortId(chatId: Long, shortId: String): Request? = queryOne(
        "SELECT * FROM request WHERE chat_ref = ? AND short_id = ? AND state = 'OPEN'"
    ) { st ->
        st.setString(1, crypto.ref(chatId.toString()))
        st.setString(2, shortId)
    }

    fun mostRecentlyClosed(chatId: Long, userId: Long): Request? = queryOne(
        """
        SELECT * FROM request
        WHERE chat_ref = ? AND user_ref = ? AND state <> 'OPEN'
        ORDER BY row_id DESC LIMIT 1
        """.trimIndent()
    ) { st ->
        st.setString(1, crypto.ref(chatId.toString()))
        st.setString(2, crypto.ref(userId.toString()))
    }

    /** Guarded by the expected state, so a double press closes exactly once. */
    fun transition(refToken: String, from: RequestState, to: RequestState): Boolean =
        ds.connection.use { c ->
            c.prepareStatement("UPDATE request SET state = ? WHERE ref_token = ? AND state = ?").use { st ->
                st.setString(1, to.name)
                st.setString(2, refToken)
                st.setString(3, from.name)
                st.executeUpdate() == 1
            }
        }

    fun expireDue(now: Instant): Int = ds.connection.use { c ->
        c.prepareStatement("UPDATE request SET state = 'EXPIRED' WHERE state = 'OPEN' AND expires_at < ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now))
            st.executeUpdate()
        }
    }

    /** Returns the ref tokens removed, so message cleanup knows what to strip. */
    fun deleteFor(userId: Long, chatId: Long?): List<String> = ds.connection.use { c ->
        val userRef = crypto.ref(userId.toString())
        val chatRef = chatId?.let { crypto.ref(it.toString()) }
        val where = if (chatRef == null) "user_ref = ?" else "user_ref = ? AND chat_ref = ?"
        val tokens = mutableListOf<String>()
        c.prepareStatement("SELECT ref_token FROM request WHERE $where").use { st ->
            st.setString(1, userRef)
            chatRef?.let { st.setString(2, it) }
            st.executeQuery().use { rs -> while (rs.next()) tokens += rs.getString(1) }
        }
        c.prepareStatement("DELETE FROM request WHERE $where").use { st ->
            st.setString(1, userRef)
            chatRef?.let { st.setString(2, it) }
            st.executeUpdate()
        }
        tokens
    }

    /**
     * A supergroup migration changes the chat id, which lives both in the ref
     * column and inside the sealed payload, so each row is resealed. The AAD is
     * the ref token and does not change, so the tokens stay valid throughout.
     */
    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Int {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val rows = query("SELECT * FROM request WHERE chat_ref = ?") { st -> st.setString(1, oldRef) }
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                for (r in rows) {
                    val resealed = crypto.seal(
                        json.encodeToString(
                            Payload(
                                newChatId, r.userId, r.username, r.side.name, r.statedCurrency,
                                r.statedAmount.toPlainString(), r.pair.base, r.pair.quote,
                            )
                        ),
                        r.refToken,
                    )
                    c.prepareStatement("UPDATE request SET chat_ref = ?, payload = ? WHERE ref_token = ?").use { st ->
                        st.setString(1, newRef)
                        st.setBytes(2, resealed)
                        st.setString(3, r.refToken)
                        st.executeUpdate()
                    }
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
        return rows.size
    }

    private fun query(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<Request> =
        ds.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                bind(st)
                st.executeQuery().use { rs ->
                    val out = mutableListOf<Request>()
                    while (rs.next()) out += hydrate(rs)
                    out
                }
            }
        }

    private fun queryOne(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Request? = ds.connection.use { c ->
        c.prepareStatement(sql).use { st ->
            bind(st)
            st.executeQuery().use { rs -> if (rs.next()) hydrate(rs) else null }
        }
    }

    /** Every field the domain needs comes out of the sealed payload. */
    private fun hydrate(rs: ResultSet): Request {
        val refToken = rs.getString("ref_token")
        val p = json.decodeFromString<Payload>(crypto.open(rs.getBytes("payload"), refToken))
        return Request(
            rowId = rs.getLong("row_id"),
            refToken = refToken,
            chatId = p.chatId,
            userId = p.userId,
            username = p.username,
            shortId = rs.getString("short_id"),
            side = Side.valueOf(p.side),
            statedCurrency = p.statedCurrency,
            statedAmount = BigDecimal(p.statedAmount),
            pair = CurrencyPair(p.base, p.quote),
            state = RequestState.valueOf(rs.getString("state")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
        )
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests 'fxbot.RequestRepositoryTest'`
Expected: PASS, 12 tests.

The "chat migration keeps payloads readable" test asserts `chatId shouldBe -1001L`
because `resting()` passes the id the caller asked for. `byRefToken` yields `chatId = 0`;
that is only used for state transitions and authorization, which key on the token
and the user, never on the chat.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/fxbot/Db.kt src/main/kotlin/fxbot/RequestRepository.kt src/main/resources src/test/kotlin/fxbot/TestDb.kt src/test/kotlin/fxbot/RequestRepositoryTest.kt
git commit -m "feat: encrypted schema and request repository"
```

---

### Task 6: Chat settings

**Files:**
- Create: `src/main/kotlin/fxbot/ChatSettingsRepository.kt`
- Test: `src/test/kotlin/fxbot/ChatSettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `Crypto` (2), `CurrencyPair` (3), `migrate` (5).
- Produces: `data class ChatSettings(chatId, pair, tolerancePct, tifDays)`; `class ChatSettingsRepository(ds, crypto, clock)` with `get(chatId): ChatSettings`, `save(s: ChatSettings)`, `allPairs(): Set<CurrencyPair>`, `rewriteChatRef(old, new): Boolean`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/ChatSettingsRepositoryTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private fun settingsRepo(name: String): ChatSettingsRepository {
    val ds = memDataSource(name)
    migrate(ds)
    return ChatSettingsRepository(ds, testCrypto())
}

class ChatSettingsRepositoryTest : StringSpec({
    "an unknown chat gets EUR/RUB, 20 percent, seven days" {
        val s = settingsRepo("defaults").get(-100L)
        s.pair shouldBe CurrencyPair("EUR", "RUB")
        s.tolerancePct shouldBe 20
        s.tifDays shouldBe 7
    }
    "saved settings round-trip" {
        val r = settingsRepo("save")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        val s = r.get(-100L)
        s.pair shouldBe CurrencyPair("USD", "GBP")
        s.tolerancePct shouldBe 5
        s.tifDays shouldBe 30
    }
    "settings are per chat" {
        val r = settingsRepo("perchat")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.get(-200L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
    "every configured pair can be enumerated for the rate refresh" {
        val r = settingsRepo("pairs")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.save(ChatSettings(-200L, CurrencyPair("EUR", "RUB"), 20, 7))
        r.allPairs() shouldBe setOf(CurrencyPair("USD", "GBP"), CurrencyPair("EUR", "RUB"))
    }
    "a chat migration re-encrypts the row under its new ref" {
        val r = settingsRepo("migrate")
        r.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        r.rewriteChatRef(-100L, -1001L) shouldBe true
        r.get(-1001L).pair shouldBe CurrencyPair("USD", "GBP")
        r.get(-100L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.ChatSettingsRepositoryTest'`
Expected: FAIL — unresolved reference `ChatSettingsRepository`.

- [ ] **Step 3: Implement**

`src/main/kotlin/fxbot/ChatSettingsRepository.kt`:

```kotlin
package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Clock
import javax.sql.DataSource

data class ChatSettings(
    val chatId: Long,
    val pair: CurrencyPair,
    val tolerancePct: Int,
    val tifDays: Int,
)

@Serializable
private data class SettingsPayload(val base: String, val quote: String, val tolerancePct: Int, val tifDays: Int)

private val DEFAULT_PAIR = CurrencyPair("EUR", "RUB")
private const val DEFAULT_TOLERANCE = 20
private const val DEFAULT_TIF_DAYS = 7

/**
 * This row's associated data is the chat ref, not a ref token, so a chat
 * migration must re-encrypt it — the one row in the schema that does.
 */
class ChatSettingsRepository(
    private val ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(chatId: Long): ChatSettings {
        val chatRef = crypto.ref(chatId.toString())
        val payload = read(chatRef) ?: return ChatSettings(chatId, DEFAULT_PAIR, DEFAULT_TOLERANCE, DEFAULT_TIF_DAYS)
        return ChatSettings(chatId, CurrencyPair(payload.base, payload.quote), payload.tolerancePct, payload.tifDays)
    }

    fun save(s: ChatSettings) {
        val chatRef = crypto.ref(s.chatId.toString())
        val body = SettingsPayload(s.pair.base, s.pair.quote, s.tolerancePct, s.tifDays)
        write(chatRef, crypto.seal(json.encodeToString(body), chatRef))
    }

    fun allPairs(): Set<CurrencyPair> = ds.connection.use { c ->
        c.prepareStatement("SELECT chat_ref, payload FROM chat_settings").use { st ->
            st.executeQuery().use { rs ->
                val out = mutableSetOf<CurrencyPair>()
                while (rs.next()) {
                    val p = json.decodeFromString<SettingsPayload>(
                        crypto.open(rs.getBytes("payload"), rs.getString("chat_ref"))
                    )
                    out += CurrencyPair(p.base, p.quote)
                }
                out
            }
        }
    }

    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Boolean {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val payload = read(oldRef) ?: return false
        write(newRef, crypto.seal(json.encodeToString(payload), newRef))
        ds.connection.use { c ->
            c.prepareStatement("DELETE FROM chat_settings WHERE chat_ref = ?").use { st ->
                st.setString(1, oldRef)
                st.executeUpdate()
            }
        }
        return true
    }

    private fun read(chatRef: String): SettingsPayload? = ds.connection.use { c ->
        c.prepareStatement("SELECT payload FROM chat_settings WHERE chat_ref = ?").use { st ->
            st.setString(1, chatRef)
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else json.decodeFromString<SettingsPayload>(crypto.open(rs.getBytes(1), chatRef))
            }
        }
    }

    private fun write(chatRef: String, sealed: ByteArray) = ds.connection.use { c ->
        c.prepareStatement(
            """
            MERGE INTO chat_settings (chat_ref, payload, updated_at) KEY (chat_ref) VALUES (?, ?, ?)
            """.trimIndent()
        ).use { st ->
            st.setString(1, chatRef)
            st.setBytes(2, sealed)
            st.setTimestamp(3, Timestamp.from(clock.instant()))
            st.executeUpdate()
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'fxbot.ChatSettingsRepositoryTest'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/fxbot/ChatSettingsRepository.kt src/test/kotlin/fxbot/ChatSettingsRepositoryTest.kt
git commit -m "feat: per-chat pair, size tolerance and time in force"
```

---

### Task 7: The reference rate

**Files:**
- Create: `src/main/kotlin/fxbot/RateClient.kt`, `src/main/kotlin/fxbot/RateRepository.kt`, `src/main/kotlin/fxbot/RateService.kt`
- Test: `src/test/kotlin/fxbot/RateServiceTest.kt`

**Interfaces:**
- Consumes: `CurrencyPair` (3), `migrate` (5).
- Produces: `class RateClient(http: HttpClient)` with `suspend fun fetch(base: String): Map<String, BigDecimal>?`; `class RateRepository(ds)` with `get(base, quote): CachedRate?` and `put(base, quote, rate, at)`; `data class CachedRate(rate, fetchedAt)`; `sealed interface RateStatus { Fresh, Stale, Unavailable }` with `val rate: BigDecimal?`; `class RateService(client, repo, clock)` with `suspend fun refresh(pairs: Set<CurrencyPair>)` and `fun status(pair: CurrencyPair): RateStatus`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/RateServiceTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")
private const val BODY = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98,"USD":1.08}}"""

private fun okClient() = HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
private fun deadClient() = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

private fun service(name: String, client: HttpClient, clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)): Pair<RateService, RateRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val repo = RateRepository(ds)
    return RateService(RateClient(client), repo, clock) to repo
}

class RateServiceTest : StringSpec({
    "a successful refresh caches the rate and reports it fresh" {
        val (svc, _) = service("fresh", okClient())
        svc.refresh(setOf(EURRUB))
        val status = svc.status(EURRUB)
        status.shouldBeInstanceOf<RateStatus.Fresh>()
        status.rate shouldBe BigDecimal("99.98")
    }
    "a dead feed with nothing cached is unavailable" {
        val (svc, _) = service("cold", deadClient())
        svc.refresh(setOf(EURRUB))
        svc.status(EURRUB).shouldBeInstanceOf<RateStatus.Unavailable>()
        svc.status(EURRUB).rate shouldBe null
    }
    "a dead feed with a warm cache keeps serving the cached rate" {
        val (svc, repo) = service("warm", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(3600))
        svc.refresh(setOf(EURRUB))
        val status = svc.status(EURRUB)
        status.shouldBeInstanceOf<RateStatus.Fresh>()
        status.rate shouldBe BigDecimal("95.00")
    }
    "a cache older than seven days is marked stale but still used" {
        val (svc, repo) = service("stale", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(8 * 86_400))
        val status = svc.status(EURRUB)
        status.shouldBeInstanceOf<RateStatus.Stale>()
        status.rate shouldBe BigDecimal("95.00")
    }
    "a refresh failure never erases a cached rate" {
        val (svc, repo) = service("keep", deadClient())
        repo.put("EUR", "RUB", BigDecimal("95.00"), T0.minusSeconds(3600))
        svc.refresh(setOf(EURRUB))
        repo.get("EUR", "RUB")!!.rate shouldBe BigDecimal("95.00")
    }
})
```

- [ ] **Step 2: Add the ContentNegotiation dependency**

The mock responses are JSON, and the client parses them. Add to `build.gradle.kts` dependencies:

```kotlin
    implementation("io.ktor:ktor-client-content-negotiation:3.5.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.1")
```

and to `gradle/libs.versions.toml` `[libraries]` if you prefer the catalog:

```toml
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.RateServiceTest'`
Expected: FAIL — unresolved reference `RateService`.

- [ ] **Step 4: Implement the client**

`src/main/kotlin/fxbot/RateClient.kt`:

```kotlin
package fxbot

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal

@Serializable
private data class FeedResponse(val result: String, val rates: Map<String, Double> = emptyMap())

/**
 * open.er-api.com: free, no key, updates daily, and — unlike the ECB feeds —
 * it still carries RUB.
 */
class RateClient(private val http: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(base: String): Map<String, BigDecimal>? = runCatching {
        val body = http.get("https://open.er-api.com/v6/latest/$base").bodyAsText()
        val parsed = json.decodeFromString<FeedResponse>(body)
        if (parsed.result != "success") return null
        parsed.rates.mapValues { (_, v) -> BigDecimal(v.toString()) }
    }.getOrNull()
}
```

- [ ] **Step 5: Implement the cache and the service**

`src/main/kotlin/fxbot/RateRepository.kt`:

```kotlin
package fxbot

import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class CachedRate(val rate: BigDecimal, val fetchedAt: Instant)

/** Public data — the one table with nothing encrypted in it. */
class RateRepository(private val ds: DataSource) {
    fun get(base: String, quote: String): CachedRate? = ds.connection.use { c ->
        c.prepareStatement("SELECT rate, fetched_at FROM fx_rate WHERE base = ? AND quote = ?").use { st ->
            st.setString(1, base)
            st.setString(2, quote)
            st.executeQuery().use { rs ->
                if (rs.next()) CachedRate(rs.getBigDecimal(1), rs.getTimestamp(2).toInstant()) else null
            }
        }
    }

    fun put(base: String, quote: String, rate: BigDecimal, at: Instant) = ds.connection.use { c ->
        c.prepareStatement(
            "MERGE INTO fx_rate (base, quote, rate, fetched_at) KEY (base, quote) VALUES (?, ?, ?, ?)"
        ).use { st ->
            st.setString(1, base)
            st.setString(2, quote)
            st.setBigDecimal(3, rate)
            st.setTimestamp(4, Timestamp.from(at))
            st.executeUpdate()
        }
        Unit
    }
}
```

`src/main/kotlin/fxbot/RateService.kt`:

```kotlin
package fxbot

import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val STALE_AFTER: Duration = Duration.ofDays(7)

sealed interface RateStatus {
    val rate: BigDecimal?

    data class Fresh(override val rate: BigDecimal) : RateStatus
    data class Stale(override val rate: BigDecimal, val fetchedAt: Instant) : RateStatus
    data object Unavailable : RateStatus { override val rate: BigDecimal? get() = null }
}

class RateService(
    private val client: RateClient,
    private val repo: RateRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** A failed fetch leaves whatever is cached exactly where it is. */
    suspend fun refresh(pairs: Set<CurrencyPair>) {
        for (base in pairs.map { it.base }.toSet()) {
            val rates = client.fetch(base) ?: continue
            for (pair in pairs.filter { it.base == base }) {
                rates[pair.quote]?.let { repo.put(pair.base, pair.quote, it, clock.instant()) }
            }
        }
    }

    fun status(pair: CurrencyPair): RateStatus {
        val cached = repo.get(pair.base, pair.quote) ?: return RateStatus.Unavailable
        val age = Duration.between(cached.fetchedAt, clock.instant())
        return if (age > STALE_AFTER) RateStatus.Stale(cached.rate, cached.fetchedAt)
        else RateStatus.Fresh(cached.rate)
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests 'fxbot.RateServiceTest'`
Expected: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/fxbot/RateClient.kt src/main/kotlin/fxbot/RateRepository.kt src/main/kotlin/fxbot/RateService.kt src/test/kotlin/fxbot/RateServiceTest.kt build.gradle.kts gradle/libs.versions.toml
git commit -m "feat: cached reference rate with graceful degradation"
```

---

### Task 8: Rendering and callback encoding

**Files:**
- Modify: `src/main/kotlin/fxbot/Side.kt` (add `giveCurrency` and `verbFor`)
- Create: `src/main/kotlin/fxbot/Render.kt`
- Test: `src/test/kotlin/fxbot/RenderTest.kt`

**Interfaces:**
- Consumes: `Request`, `Counterparty`, `RateStatus`, `Side`, money formatters.
- Produces: `fun Side.giveCurrency(pair: CurrencyPair): String`; `fun verbFor(side: Side, statedCurrency: String, pair: CurrencyPair): Verb`; `object Cb` with `done(mine, theirs)`, `cancel(token)`, `reopen(token)`; `data class Button(val label: String, val data: String)`; `fun mention(username: String?, userId: Long, displayName: String): String`; `fun describe(r: Request): String`; `fun renderSuggestions(subject: Request, found: List<Counterparty>, status: RateStatus): String`; `fun suggestionButtons(subject: Request, found: List<Counterparty>): List<Button>`; `fun renderStatus(requests: List<Request>, viewerId: Long, limit: Int = 20): String`.

Messages are sent with **HTML parse mode** — it needs only `&`, `<` and `>` escaped, unlike MarkdownV2, and it is the only way to mention someone who has no `@username`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/RenderTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.math.BigDecimal
import java.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")

private fun r(verb: Verb, amount: String, ccy: String, user: Long, name: String?, token: String = "t".repeat(22)) =
    Request(
        rowId = user, refToken = token, chatId = -100L, userId = user, username = name,
        shortId = "a$user", side = sideFor(verb, ccy, EURRUB), statedCurrency = ccy,
        statedAmount = BigDecimal(amount), pair = EURRUB, state = RequestState.OPEN,
        createdAt = Instant.EPOCH, expiresAt = Instant.EPOCH,
    )

class RenderTest : StringSpec({
    "the give currency follows the side" {
        Side.OFFER.giveCurrency(EURRUB) shouldBe "EUR"
        Side.BID.giveCurrency(EURRUB) shouldBe "RUB"
    }
    "the verb is reconstructed from the side and the stated currency" {
        verbFor(Side.OFFER, "EUR", EURRUB) shouldBe Verb.SELL
        verbFor(Side.OFFER, "RUB", EURRUB) shouldBe Verb.BUY
        verbFor(Side.BID, "RUB", EURRUB) shouldBe Verb.SELL
        verbFor(Side.BID, "EUR", EURRUB) shouldBe Verb.BUY
    }
    "a request is described in the words its author used" {
        describe(r(Verb.SELL, "1000", "EUR", 1, "alice")) shouldBe "sell 1,000 EUR"
        describe(r(Verb.BUY, "900", "EUR", 2, "bob")) shouldBe "buy 900 EUR"
        describe(r(Verb.SELL, "95000", "RUB", 3, "carol")) shouldBe "sell 95,000 RUB"
    }

    "someone with a username is mentioned by it" {
        mention("alice", 42, "Alice") shouldBe "@alice"
    }
    "someone without a username gets a tg link" {
        mention(null, 42, "Alice") shouldBe """<a href="tg://user?id=42">Alice</a>"""
    }
    "display names are HTML-escaped" {
        mention(null, 42, "A<b>&") shouldContain "A&lt;b&gt;&amp;"
    }

    "callback data stays inside Telegram's 64 bytes" {
        val a = "a".repeat(22)
        val b = "b".repeat(22)
        Cb.done(a, b).toByteArray().size shouldBe 54
        (Cb.done(a, b).toByteArray().size <= 64) shouldBe true
        (Cb.cancel(a).toByteArray().size <= 64) shouldBe true
        (Cb.reopen(a).toByteArray().size <= 64) shouldBe true
    }
    "callback data uses the framework's query syntax" {
        Cb.cancel("tok") shouldBe "cancel?t=tok"
        Cb.done("x", "y") shouldBe "done?a=x&b=y"
    }

    "a suggestion names each counterparty and how to reach them" {
        val subject = r(Verb.SELL, "1000", "EUR", 1, "bob")
        val found = listOf(
            Counterparty(r(Verb.BUY, "900", "EUR", 2, "alice"), BigDecimal("900"), BigDecimal("0.1")),
            Counterparty(r(Verb.SELL, "95000", "RUB", 3, "carol"), BigDecimal("950.19"), BigDecimal("0.05")),
        )
        val text = renderSuggestions(subject, found, RateStatus.Fresh(BigDecimal("99.98")))
        text shouldContain "@alice"
        text shouldContain "buy 900 EUR"
        text shouldContain "sell 95,000 RUB"
        text shouldContain "950.19"
        text shouldNotContain "notional"
        text shouldNotContain "Bid"
    }
    "no counterparty means a waitlist line, not an error" {
        val text = renderSuggestions(r(Verb.SELL, "1000", "EUR", 1, "bob"), emptyList(), RateStatus.Fresh(BigDecimal("99.98")))
        text shouldContain "waitlist"
    }
    "a stale rate is admitted in the message" {
        val text = renderSuggestions(
            r(Verb.SELL, "1000", "EUR", 1, "bob"), emptyList(),
            RateStatus.Stale(BigDecimal("95"), Instant.parse("2026-08-12T00:00:00Z")),
        )
        text shouldContain "12 Aug"
    }
    "an unavailable rate says so plainly" {
        val text = renderSuggestions(r(Verb.SELL, "1000", "EUR", 1, "bob"), emptyList(), RateStatus.Unavailable)
        text shouldContain "can't check rates"
    }

    "one done button per counterparty, plus cancel" {
        val subject = r(Verb.SELL, "1000", "EUR", 1, "bob", token = "s".repeat(22))
        val found = listOf(Counterparty(r(Verb.BUY, "900", "EUR", 2, "alice", token = "c".repeat(22)), BigDecimal("900"), BigDecimal.ZERO))
        val buttons = suggestionButtons(subject, found)
        buttons.size shouldBe 2
        buttons[0].label shouldContain "alice"
        buttons[0].data shouldBe Cb.done("s".repeat(22), "c".repeat(22))
        buttons[1].data shouldBe Cb.cancel("s".repeat(22))
    }

    "status caps the list and says how many were left out" {
        val many = (1..25).map { r(Verb.SELL, "$it", "EUR", it.toLong(), "u$it") }
        val text = renderStatus(many, viewerId = 3, limit = 20)
        text shouldContain "+5 more"
        text shouldContain "yours"
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.RenderTest'`
Expected: FAIL — unresolved reference `giveCurrency`.

- [ ] **Step 3: Extend Side.kt**

Append to `src/main/kotlin/fxbot/Side.kt`:

```kotlin
/** Offer gives the base currency; Bid gives the quote. */
fun Side.giveCurrency(pair: CurrencyPair): String =
    if (this == Side.OFFER) pair.base else pair.quote

/**
 * The verb the author used, recovered from what they gave and what they quoted.
 * Quoting the currency you hand over is "sell"; quoting the other one is "buy".
 */
fun verbFor(side: Side, statedCurrency: String, pair: CurrencyPair): Verb =
    if (statedCurrency == side.giveCurrency(pair)) Verb.SELL else Verb.BUY
```

- [ ] **Step 4: Implement rendering**

`src/main/kotlin/fxbot/Render.kt`:

```kotlin
package fxbot

import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH).withZone(ZoneOffset.UTC)

/** Callback payloads, in the framework's `name?param=value` form. */
object Cb {
    const val DONE = "done"
    const val CANCEL = "cancel"
    const val REOPEN = "reopen"

    fun done(mine: String, theirs: String) = "$DONE?a=$mine&b=$theirs"
    fun cancel(token: String) = "$CANCEL?t=$token"
    fun reopen(token: String) = "$REOPEN?t=$token"
}

data class Button(val label: String, val data: String)

fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** `@name` when there is one, otherwise the only mention Telegram allows. */
fun mention(username: String?, userId: Long, displayName: String): String =
    if (username != null) "@${escapeHtml(username)}"
    else """<a href="tg://user?id=$userId">${escapeHtml(displayName)}</a>"""

/** Always the author's own words — never the converted figure. */
fun describe(r: Request): String {
    val verb = if (verbFor(r.side, r.statedCurrency, r.pair) == Verb.SELL) "sell" else "buy"
    return "$verb ${formatAmount(r.statedAmount)} ${r.statedCurrency}"
}

fun renderSuggestions(subject: Request, found: List<Counterparty>, status: RateStatus): String {
    val lines = StringBuilder()
    if (found.isEmpty()) {
        lines.append("No one matches yet — you're on the waitlist.")
    } else {
        lines.append(if (found.size == 1) "1 person matches:" else "${found.size} people match:")
        for (c in found) {
            val who = mention(c.request.username, c.request.userId, c.request.username ?: "this person")
            lines.append("\n• ").append(who).append(" — ").append(describe(c.request))
            val n = c.notional
            if (n != null && c.request.statedCurrency != c.request.pair.base) {
                lines.append(" (≈").append(formatNotional(n)).append(' ').append(c.request.pair.base).append(')')
            }
        }
        lines.append("\nAgree the rate between yourselves, then press Done.")
    }
    when (status) {
        is RateStatus.Stale -> lines.append("\n(rate from ").append(DAY_MONTH.format(status.fetchedAt)).append(", may be out of date)")
        RateStatus.Unavailable -> lines.append("\n(I can't check rates right now, so I can only match amounts in the same currency)")
        is RateStatus.Fresh -> Unit
    }
    return lines.toString()
}

fun suggestionButtons(subject: Request, found: List<Counterparty>): List<Button> =
    found.map { c ->
        Button("✅ Done with ${c.request.username ?: "them"}", Cb.done(subject.refToken, c.request.refToken))
    } + Button("✖️ Cancel my request", Cb.cancel(subject.refToken))

fun renderStatus(requests: List<Request>, viewerId: Long, limit: Int = 20): String {
    if (requests.isEmpty()) return "Nothing waiting in this chat right now."
    val shown = requests.take(limit)
    val text = StringBuilder("Waiting in this chat:")
    for (r in shown) {
        val who = mention(r.username, r.userId, r.username ?: "this person")
        text.append("\n• ").append(r.shortId).append(' ').append(who).append(" — ").append(describe(r))
        if (r.userId == viewerId) text.append("  (yours)")
    }
    val hidden = requests.size - shown.size
    if (hidden > 0) text.append("\n+").append(hidden).append(" more")
    return text.toString()
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests 'fxbot.RenderTest'`
Expected: PASS, 14 tests.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/fxbot/Side.kt src/main/kotlin/fxbot/Render.kt src/test/kotlin/fxbot/RenderTest.kt
git commit -m "feat: message rendering and callback payload encoding"
```

---

### Task 9: Posting a request

**Files:**
- Create: `src/main/kotlin/fxbot/Registry.kt`, `src/main/kotlin/fxbot/RequestService.kt`, `src/main/kotlin/fxbot/Commands.kt`, `src/main/kotlin/fxbot/Main.kt`
- Test: `src/test/kotlin/fxbot/RequestServiceTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–8.
- Produces: `object Registry` holding `requests`, `settings`, `rates`, `messages`, `crypto`; `class RequestService(...)` with `suspend fun post(chatId, userId, username, verb, rawAmount, rawCurrency): PostResult`; `sealed interface PostResult { Posted(request, found, status), Rejected(reason) }`.

Parsing and rejection live in `RequestService` so they are testable without Telegram. `Commands.kt` only extracts arguments and sends what the service returns.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/RequestServiceTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun service(name: String): RequestService {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val clock = Clock.fixed(T0, ZoneOffset.UTC)
    val rates = RateRepository(ds)
    rates.put("EUR", "RUB", BigDecimal("99.98"), T0)
    return RequestService(
        RequestRepository(ds, crypto, clock),
        ChatSettingsRepository(ds, crypto, clock),
        RateService(RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })), rates, clock),
    )
}

class RequestServiceTest : StringSpec({
    "posting a sell records an offer and finds nobody at first" {
        val svc = service("post")
        val result = svc.post(-100L, 1L, "bob", Verb.SELL, "1000", "EUR")
        result.shouldBeInstanceOf<PostResult.Posted>()
        result.request.side shouldBe Side.OFFER
        result.found.size shouldBe 0
    }
    "the second person is matched with the first" {
        val svc = service("match")
        svc.post(-100L, 1L, "bob", Verb.SELL, "1000", "EUR")
        val result = svc.post(-100L, 2L, "alice", Verb.BUY, "1000", "EUR")
        result.shouldBeInstanceOf<PostResult.Posted>()
        result.found.size shouldBe 1
        result.found[0].request.username shouldBe "bob"
    }
    "a buy stated in the base currency is a bid" {
        val svc = service("bid")
        val result = svc.post(-100L, 1L, "bob", Verb.BUY, "1000", "EUR")
        result.shouldBeInstanceOf<PostResult.Posted>()
        result.request.side shouldBe Side.BID
        result.request.statedCurrency shouldBe "EUR"
        result.request.statedAmount shouldBe BigDecimal("1000")
    }
    "an unparseable amount is rejected with a usable message" {
        val r = service("badamount").post(-100L, 1L, "bob", Verb.SELL, "lots", "EUR")
        r.shouldBeInstanceOf<PostResult.Rejected>()
        r.reason shouldContain "amount"
    }
    "a non-positive amount is rejected" {
        service("zero").post(-100L, 1L, "bob", Verb.SELL, "0", "EUR").shouldBeInstanceOf<PostResult.Rejected>()
    }
    "an unknown currency is rejected" {
        val r = service("badccy").post(-100L, 1L, "bob", Verb.SELL, "10", "XYZ")
        r.shouldBeInstanceOf<PostResult.Rejected>()
        r.reason shouldContain "XYZ"
    }
    "a currency outside this chat's pair is rejected and names the pair" {
        val r = service("offpair").post(-100L, 1L, "bob", Verb.SELL, "10", "JPY")
        r.shouldBeInstanceOf<PostResult.Rejected>()
        r.reason shouldContain "EUR/RUB"
    }
    "posting works with no rate cached at all" {
        val ds = memDataSource("norate")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val svc = RequestService(
            RequestRepository(ds, crypto, clock),
            ChatSettingsRepository(ds, crypto, clock),
            RateService(RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })), RateRepository(ds), clock),
        )
        svc.post(-100L, 1L, "bob", Verb.BUY, "1000", "EUR").shouldBeInstanceOf<PostResult.Posted>()
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.RequestServiceTest'`
Expected: FAIL — unresolved reference `RequestService`.

- [ ] **Step 3: Implement the service**

`src/main/kotlin/fxbot/RequestService.kt`:

```kotlin
package fxbot

sealed interface PostResult {
    data class Posted(val request: Request, val found: List<Counterparty>, val status: RateStatus) : PostResult
    data class Rejected(val reason: String) : PostResult
}

class RequestService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
) {
    fun post(
        chatId: Long,
        userId: Long,
        username: String?,
        verb: Verb,
        rawAmount: String,
        rawCurrency: String,
    ): PostResult {
        val chat = settings.get(chatId)
        val amount = parseAmount(rawAmount)
            ?: return PostResult.Rejected("I couldn't read \"$rawAmount\" as an amount. Try: /sell 1000 EUR")
        val currency = parseCurrency(rawCurrency)
            ?: return PostResult.Rejected("\"$rawCurrency\" isn't a currency code I know. Try: /sell 1000 EUR")
        if (!chat.pair.contains(currency)) {
            return PostResult.Rejected("This chat exchanges ${chat.pair}, so I can't do $currency here.")
        }

        val side = sideFor(verb, currency, chat.pair)
        val request = requests.create(chatId, userId, username, side, currency, amount, chat.pair, chat.tifDays)
        val status = rates.status(chat.pair)
        val found = findCounterparties(request, requests.resting(chatId), status.rate, chat.tolerancePct)
        return PostResult.Posted(request, found, status)
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew test --tests 'fxbot.RequestServiceTest'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Wire the Telegram surface**

`src/main/kotlin/fxbot/Registry.kt`:

```kotlin
package fxbot

/**
 * Process-global wiring, set once in main(). The framework invokes top-level
 * handler functions, so they need a way to reach their dependencies.
 */
object Registry {
    lateinit var requests: RequestRepository
    lateinit var settings: ChatSettingsRepository
    lateinit var rates: RateService
    lateinit var service: RequestService
}
```

`src/main/kotlin/fxbot/Commands.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ParseMode
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.utils.common.getChat
import eu.vendeli.tgbot.utils.common.getUser

private const val PRIVATE_HINT =
    "I introduce people who want to swap currency inside a group chat. Add me to your group to use me."

/** Channels have no per-person sender to match, mention, or authorize. */
private fun ProcessedUpdate.isGroupChat(): Boolean =
    getChat().type.name.lowercase().contains("group")

@CommandHandler(["/sell"])
suspend fun sell(update: ProcessedUpdate, bot: TelegramBot) = handlePost(Verb.SELL, update, bot)

@CommandHandler(["/buy"])
suspend fun buy(update: ProcessedUpdate, bot: TelegramBot) = handlePost(Verb.BUY, update, bot)

private suspend fun handlePost(verb: Verb, update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    if (!update.isGroupChat()) {
        message { PRIVATE_HINT }.send(chat.id, bot)
        return
    }
    val user = update.getUser()
    val args = update.text.trim().split(Regex("\\s+")).drop(1)
    if (args.size < 2) {
        message { "Tell me the amount and the currency, like: /sell 1000 EUR" }.send(chat.id, bot)
        return
    }
    when (val result = Registry.service.post(chat.id, user.id, user.username, verb, args[0], args[1])) {
        is PostResult.Rejected -> message { result.reason }.send(chat.id, bot)
        is PostResult.Posted -> {
            val text = renderSuggestions(result.request, result.found, result.status)
            val buttons = suggestionButtons(result.request, result.found)
            message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .send(chat.id, bot)
        }
    }
}

@CommandHandler(["/status"])
suspend fun status(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    message { renderStatus(Registry.requests.resting(chat.id), user.id) }
        .options { parseMode = ParseMode.HTML }
        .send(chat.id, bot)
}

@CommandHandler(["/settings"])
suspend fun settings(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val s = Registry.settings.get(chat.id)
    message {
        "This chat swaps ${s.pair}. Amounts match within ${s.tolerancePct}%, " +
            "and a request waits ${s.tifDays} days before it lapses.\n" +
            "Admins can change these with /pair, /tolerance and /tif."
    }.send(chat.id, bot)
}

@CommandHandler(["/help"])
suspend fun help(update: ProcessedUpdate, bot: TelegramBot) {
    message {
        """
        /sell 1000 EUR — you're handing over 1000 EUR
        /buy 1000 EUR — you want to receive 1000 EUR
        /status — who's waiting in this chat
        /cancel a1 — withdraw your request
        /done a1 @someone — you two swapped
        /reopen — undo your last /done
        /forget — erase what I store about you here
        /settings — this chat's currencies and limits
        """.trimIndent()
    }.send(update.getChat().id, bot)
}
```

`src/main/kotlin/fxbot/Main.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.botactions.setMyCommands
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

suspend fun main() {
    val cfg = loadConfig(System::getenv)
    val crypto = Crypto(cfg.dataKeyset, cfg.indexKeyset)
    val ds = createDataSource(cfg)
    migrate(ds)

    Registry.requests = RequestRepository(ds, crypto)
    Registry.settings = ChatSettingsRepository(ds, crypto)
    Registry.rates = RateService(RateClient(HttpClient(CIO)), RateRepository(ds))
    Registry.service = RequestService(Registry.requests, Registry.settings, Registry.rates)

    val bot = TelegramBot(cfg.botToken) {
        updatesListener { updatesPollingTimeout = 30 }
        httpClient {
            requestTimeoutMillis = 45_000L
            maxRequestRetry = 3
            retryDelay = 2_000L
            retryStrategy = retryOnTooManyRequests()
        }
    }

    setMyCommands {
        botCommand("sell", "Offer currency you're handing over")
        botCommand("buy", "Ask for currency you want to receive")
        botCommand("status", "Who's waiting in this chat")
        botCommand("cancel", "Withdraw one of your requests")
        botCommand("done", "Mark a swap as completed")
        botCommand("reopen", "Undo your last /done")
        botCommand("forget", "Erase what I store about you here")
        botCommand("settings", "This chat's currencies and limits")
    }.send(bot)

    println("exchange-bot: listening")

    while (true) {
        try {
            bot.handleUpdates()
        } catch (e: Exception) {
            System.err.println("exchange-bot: listener error (${e.javaClass.simpleName}); restarting in 5s")
            runCatching { bot.update.stopListener() }
            delay(5.seconds)
        }
    }
}
```

- [ ] **Step 6: Compile and run the whole suite**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

If an import in `Commands.kt` does not resolve, compare against
`../conference-notifier-bot/src/main/kotlin/cfpbot/Commands.kt`, which uses the
same framework version — copy its import lines for `getChat`, `ProcessedUpdate`
and the keyboard builder rather than guessing.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/fxbot src/test/kotlin/fxbot/RequestServiceTest.kt
git commit -m "feat: post a request and suggest counterparties"
```

---

### Task 10: Closing requests — commands and buttons

**Files:**
- Modify: `src/main/kotlin/fxbot/RequestRepository.kt` (add `markDone`)
- Create: `src/main/kotlin/fxbot/LifecycleService.kt`, `src/main/kotlin/fxbot/LifecycleCommands.kt`, `src/main/kotlin/fxbot/Callbacks.kt`
- Modify: `src/main/kotlin/fxbot/Registry.kt` (add `lifecycle`), `src/main/kotlin/fxbot/Main.kt` (wire it)
- Test: `src/test/kotlin/fxbot/LifecycleServiceTest.kt`

**Interfaces:**
- Consumes: `RequestRepository` (5), `Render`/`Cb` (8).
- Produces: `enum class DoneOutcome { BOTH, ALREADY_CLOSED, PEER_GONE }`; `fun RequestRepository.markDone(mine: String, theirs: String?): DoneOutcome`; `sealed interface ActionResult { Ok(text, closedTokens), Denied(text), Gone(text) }`; `class LifecycleService(requests)` with `cancel(chatId, userId, shortId)`, `cancelByToken(userId, token)`, `done(userId, mineToken, theirsToken)`, `doneByShortId(chatId, userId, shortId, peerUserId)`, `reopen(chatId, userId)`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/LifecycleServiceTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun lifecycle(name: String): Pair<LifecycleService, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val repo = RequestRepository(ds, testCrypto(), Clock.fixed(T0, ZoneOffset.UTC))
    return LifecycleService(repo) to repo
}

private fun RequestRepository.put(chatId: Long, userId: Long, name: String, side: Side) =
    create(chatId, userId, name, side, "EUR", BigDecimal("1000"), EURRUB, 7)

class LifecycleServiceTest : StringSpec({
    "the owner can cancel their own request" {
        val (svc, repo) = lifecycle("cancel")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "nobody else can cancel it" {
        val (svc, repo) = lifecycle("cancelother")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 2L, a.shortId).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "cancelling an unknown short id says so" {
        val (svc, _) = lifecycle("cancelmissing")
        svc.cancel(-100L, 1L, "zz").shouldBeInstanceOf<ActionResult.Gone>()
    }

    "done closes both sides" {
        val (svc, repo) = lifecycle("done")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.DONE
    }
    "either counterparty may press done" {
        val (svc, repo) = lifecycle("doneeither")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(2L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Ok>()
    }
    "a third party may not" {
        val (svc, repo) = lifecycle("donethird")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(3L, a.refToken, b.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "a second done reports it was already closed" {
        val (svc, repo) = lifecycle("donetwice")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(1L, a.refToken, b.refToken)
        val again = svc.done(2L, a.refToken, b.refToken)
        again.shouldBeInstanceOf<ActionResult.Gone>()
        again.text shouldContain "already"
    }
    "a forged token for a request the presser does not own is denied" {
        val (svc, repo) = lifecycle("forged")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.done(99L, b.refToken, a.refToken).shouldBeInstanceOf<ActionResult.Denied>()
    }
    "closing with a counterparty who has already gone still closes your own" {
        val (svc, repo) = lifecycle("peergone")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-100L, 2L, "alice", Side.BID)
        svc.cancel(-100L, 2L, b.shortId)
        val result = svc.done(1L, a.refToken, b.refToken)
        result.shouldBeInstanceOf<ActionResult.Ok>()
        result.text shouldContain "only your own"
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.DONE
    }

    "reopen revives your most recent closure with a fresh clock" {
        val (svc, repo) = lifecycle("reopen")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId)
        svc.reopen(-100L, 1L).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
    }
    "reopen with nothing closed says so" {
        val (svc, _) = lifecycle("reopennothing")
        svc.reopen(-100L, 1L).shouldBeInstanceOf<ActionResult.Gone>()
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.LifecycleServiceTest'`
Expected: FAIL — unresolved reference `LifecycleService`.

- [ ] **Step 3: Add the transactional close to the repository**

Append to `src/main/kotlin/fxbot/RequestRepository.kt` (inside the class):

```kotlin
    /**
     * Closes both sides in one transaction, each guarded by its expected state,
     * so two people pressing Done at the same moment close it exactly once.
     */
    fun markDone(mine: String, theirs: String?): DoneOutcome = ds.connection.use { c ->
        c.autoCommit = false
        try {
            val sql = "UPDATE request SET state = 'DONE' WHERE ref_token = ? AND state = 'OPEN'"
            val mineClosed = c.prepareStatement(sql).use { st ->
                st.setString(1, mine); st.executeUpdate()
            }
            if (mineClosed == 0) {
                c.rollback()
                return@use DoneOutcome.ALREADY_CLOSED
            }
            val theirsClosed = theirs?.let { t ->
                c.prepareStatement(sql).use { st -> st.setString(1, t); st.executeUpdate() }
            } ?: 0
            c.commit()
            if (theirs != null && theirsClosed == 0) DoneOutcome.PEER_GONE else DoneOutcome.BOTH
        } catch (e: Exception) {
            c.rollback(); throw e
        }
    }

    /** Puts a closed request back with a fresh time in force. */
    fun reopen(refToken: String, tifDays: Int): Boolean = ds.connection.use { c ->
        c.prepareStatement(
            "UPDATE request SET state = 'OPEN', expires_at = ? WHERE ref_token = ? AND state <> 'OPEN'"
        ).use { st ->
            st.setTimestamp(1, java.sql.Timestamp.from(clock.instant().plusSeconds(tifDays.toLong() * 86_400)))
            st.setString(2, refToken)
            st.executeUpdate() == 1
        }
    }
```

and at the top level of the same file:

```kotlin
enum class DoneOutcome { BOTH, ALREADY_CLOSED, PEER_GONE }
```

- [ ] **Step 4: Implement the service**

`src/main/kotlin/fxbot/LifecycleService.kt`:

```kotlin
package fxbot

sealed interface ActionResult {
    val text: String

    data class Ok(override val text: String, val closedTokens: List<String>) : ActionResult
    data class Denied(override val text: String) : ActionResult
    data class Gone(override val text: String) : ActionResult
}

/**
 * Authorization is decided here, from the acting user id — never from anything
 * a client sent us. Cancel and reopen are the owner's alone; either
 * counterparty may confirm a swap happened.
 */
class LifecycleService(private val requests: RequestRepository) {

    fun cancel(chatId: Long, userId: Long, shortId: String): ActionResult {
        val r = requests.byShortId(chatId, shortId)
            ?: return ActionResult.Gone("I can't find a waiting request called $shortId here.")
        return cancelByToken(userId, r.refToken)
    }

    fun cancelByToken(userId: Long, token: String): ActionResult {
        val r = requests.byRefToken(token)
            ?: return ActionResult.Gone("That request is gone.")
        if (r.userId != userId) return ActionResult.Denied("That's not your request.")
        if (r.state != RequestState.OPEN) return ActionResult.Gone("That request is already closed.")
        return if (requests.transition(token, RequestState.OPEN, RequestState.CANCELLED)) {
            ActionResult.Ok("Withdrawn.", listOf(token))
        } else {
            ActionResult.Gone("That request is already closed.")
        }
    }

    fun done(userId: Long, mineToken: String, theirsToken: String?): ActionResult {
        val mine = requests.byRefToken(mineToken)
            ?: return ActionResult.Gone("That request is gone.")
        val theirs = theirsToken?.let { requests.byRefToken(it) }
        val isParticipant = mine.userId == userId || theirs?.userId == userId
        if (!isParticipant) return ActionResult.Denied("Only the two people swapping can mark this done.")

        return when (requests.markDone(mine.refToken, theirs?.refToken)) {
            DoneOutcome.BOTH ->
                ActionResult.Ok("Marked done. If that's wrong, /reopen.", listOfNotNull(mine.refToken, theirs?.refToken))
            DoneOutcome.PEER_GONE ->
                ActionResult.Ok("Closed only your own — the other request was already closed.", listOf(mine.refToken))
            DoneOutcome.ALREADY_CLOSED ->
                ActionResult.Gone("That one is already closed.")
        }
    }

    fun doneByShortId(chatId: Long, userId: Long, shortId: String, peerUserId: Long?): ActionResult {
        val mine = requests.byShortId(chatId, shortId)
            ?: return ActionResult.Gone("I can't find a waiting request called $shortId here.")
        if (mine.userId != userId) return ActionResult.Denied("That's not your request.")
        val theirs = peerUserId?.let { peer -> requests.resting(chatId).firstOrNull { it.userId == peer } }
        return done(userId, mine.refToken, theirs?.refToken)
    }

    fun reopen(chatId: Long, userId: Long, tifDays: Int = 7): ActionResult {
        val last = requests.mostRecentlyClosed(chatId, userId)
            ?: return ActionResult.Gone("You have nothing closed here to bring back.")
        return if (requests.reopen(last.refToken, tifDays)) {
            ActionResult.Ok("Back on the waitlist: ${describe(last)}", emptyList())
        } else {
            ActionResult.Gone("That one is already waiting.")
        }
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test --tests 'fxbot.LifecycleServiceTest'`
Expected: PASS, 11 tests.

- [ ] **Step 6: Add the commands and the callbacks**

`src/main/kotlin/fxbot/LifecycleCommands.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.utils.common.getChat
import eu.vendeli.tgbot.utils.common.getUser

@CommandHandler(["/cancel"])
suspend fun cancel(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val shortId = update.text.trim().split(Regex("\\s+")).getOrNull(1)
    if (shortId == null) {
        message { "Which one? Try /cancel a1 — /status lists them." }.send(chat.id, bot)
        return
    }
    message { Registry.lifecycle.cancel(chat.id, user.id, shortId).text }.send(chat.id, bot)
}

@CommandHandler(["/reopen"])
suspend fun reopen(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val tif = Registry.settings.get(chat.id).tifDays
    message { Registry.lifecycle.reopen(chat.id, update.getUser().id, tif).text }.send(chat.id, bot)
}

@CommandHandler(["/done"])
suspend fun done(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val parts = update.text.trim().split(Regex("\\s+"))
    val shortId = parts.getOrNull(1)
    if (shortId == null) {
        message { "Which one? Try /done a1 @someone" }.send(chat.id, bot)
        return
    }
    val peerId = resolvePeer(update, chat.id)
    message { Registry.lifecycle.doneByShortId(chat.id, user.id, shortId, peerId).text }.send(chat.id, bot)
}

/**
 * Counterparties come from message entities, never from typed display names, and
 * only from people who actually have something waiting here.
 */
private fun resolvePeer(update: ProcessedUpdate, chatId: Long): Long? {
    val message = (update as? eu.vendeli.tgbot.types.component.MessageUpdate)?.message ?: return null
    message.replyToMessage?.from?.id?.let { return it }
    val entities = message.entities.orEmpty()
    entities.firstOrNull { it.user != null }?.user?.id?.let { return it }
    val mentioned = entities.firstOrNull { it.type.name.equals("MENTION", ignoreCase = true) }
        ?.let { message.text?.substring(it.offset + 1, it.offset + it.length) }
        ?: return null
    return Registry.requests.resting(chatId).firstOrNull { it.username.equals(mentioned, ignoreCase = true) }?.userId
}
```

`src/main/kotlin/fxbot/Callbacks.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.answer.answerCallbackQuery
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.utils.common.getChat
import eu.vendeli.tgbot.utils.common.getUser

/**
 * Callback data is a suggestion from a client, not an authorization: every
 * handler re-derives who is acting from the update itself.
 */
@CommandHandler.CallbackQuery(["done"], autoAnswer = false)
suspend fun doneCallback(a: String, b: String, update: ProcessedUpdate, bot: TelegramBot) {
    respond(Registry.lifecycle.done(update.getUser().id, a, b), update, bot)
}

@CommandHandler.CallbackQuery(["cancel"], autoAnswer = false)
suspend fun cancelCallback(t: String, update: ProcessedUpdate, bot: TelegramBot) {
    respond(Registry.lifecycle.cancelByToken(update.getUser().id, t), update, bot)
}

@CommandHandler.CallbackQuery(["reopen"], autoAnswer = false)
suspend fun reopenCallback(t: String, update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val tif = Registry.settings.get(chat.id).tifDays
    respond(Registry.lifecycle.reopen(chat.id, update.getUser().id, tif), update, bot)
}

private suspend fun respond(result: ActionResult, update: ProcessedUpdate, bot: TelegramBot) {
    val user = update.getUser()
    val queryId = (update as? eu.vendeli.tgbot.types.component.CallbackQueryUpdate)?.callbackQuery?.id
    when (result) {
        is ActionResult.Ok -> {
            queryId?.let { answerCallbackQuery(it).send(user.id, bot) }
            message { result.text }.send(update.getChat().id, bot)
            Registry.buttons.stripFor(result.closedTokens, update.getChat().id, bot)
        }
        is ActionResult.Denied, is ActionResult.Gone -> {
            // Private to the presser: a refusal is not the group's business.
            queryId?.let {
                answerCallbackQuery(it).options { text = result.text; showAlert = true }.send(user.id, bot)
            }
        }
    }
}
```

`Registry.buttons` arrives in Task 11; until then, comment that line out and restore it there.

- [ ] **Step 7: Wire and build**

Add `lateinit var lifecycle: LifecycleService` to `Registry`, and in `Main.kt` after the other wiring:

```kotlin
    Registry.lifecycle = LifecycleService(Registry.requests)
```

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/fxbot src/test/kotlin/fxbot/LifecycleServiceTest.kt
git commit -m "feat: cancel, done and reopen via commands and buttons"
```

---

### Task 11: Message tracking and stale buttons

**Files:**
- Create: `src/main/resources/db/migration/V2__sent_message_payload.sql`
- Create: `src/main/kotlin/fxbot/MessageLogRepository.kt`, `src/main/kotlin/fxbot/ButtonService.kt`
- Modify: `src/main/kotlin/fxbot/Registry.kt`, `src/main/kotlin/fxbot/Main.kt`, `src/main/kotlin/fxbot/Commands.kt` (record what it sends), `src/main/kotlin/fxbot/Callbacks.kt` (restore the strip call)
- Test: `src/test/kotlin/fxbot/MessageLogRepositoryTest.kt`

**Interfaces:**
- Consumes: `Crypto` (2).
- Produces: `data class TrackedMessage(val chatId: Long, val messageId: Long)`; `class MessageLogRepository(ds, crypto, clock)` with `record(chatId, messageId, refTokens, userIds)`, `messagesForToken(refToken, limit): List<TrackedMessage>`, `messagesForUser(userId, chatId?): List<TrackedMessage>`, `namesOthers(messageId, chatId, userId): Boolean`, `forget(userId, chatId?)`, `prune(before): Int`, `rewriteChatRef(old, new): Int`; `class ButtonService(log)` with `suspend fun stripFor(tokens: List<String>, chatId: Long, bot: TelegramBot)`.

The chat id must be recoverable to edit or delete a message, and `chat_ref` is a
one-way hash — so `sent_message` gains an encrypted payload holding it.

- [ ] **Step 1: Write the migration**

`src/main/resources/db/migration/V2__sent_message_payload.sql`:

```sql
-- The chat ref is a keyed hash and cannot be reversed, but editing or deleting a
-- message needs the real chat id. Keep it sealed like every other identity.
ALTER TABLE sent_message ADD COLUMN payload BYTEA;
```

- [ ] **Step 2: Write the failing test**

`src/test/kotlin/fxbot/MessageLogRepositoryTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private fun log(name: String, clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)): MessageLogRepository {
    val ds = memDataSource(name)
    migrate(ds)
    return MessageLogRepository(ds, testCrypto(), clock)
}

class MessageLogRepositoryTest : StringSpec({
    "finds the messages carrying a request's buttons, newest first" {
        val l = log("bytoken")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.record(-100L, 11L, listOf("tokA", "tokB"), listOf(1L, 2L))
        val found = l.messagesForToken("tokA", limit = 10)
        found shouldHaveSize 2
        found.first().messageId shouldBe 11L
        found.first().chatId shouldBe -100L
    }
    "caps the fan-out" {
        val l = log("fanout")
        (1L..15L).forEach { l.record(-100L, it, listOf("tokA"), listOf(1L)) }
        l.messagesForToken("tokA", limit = 10) shouldHaveSize 10
    }
    "finds every message naming a person, in one chat or all of them" {
        val l = log("byuser")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.record(-200L, 20L, listOf("tokB"), listOf(1L))
        l.messagesForUser(1L, -100L) shouldHaveSize 1
        l.messagesForUser(1L, null) shouldHaveSize 2
    }
    "knows whether a message named anyone else" {
        val l = log("others")
        l.record(-100L, 10L, listOf("tokA", "tokB"), listOf(1L, 2L))
        l.record(-100L, 11L, listOf("tokA"), listOf(1L))
        l.namesOthers(10L, -100L, userId = 1L) shouldBe true
        l.namesOthers(11L, -100L, userId = 1L) shouldBe false
    }
    "forgetting removes a person's rows only" {
        val l = log("forget")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.record(-100L, 11L, listOf("tokB"), listOf(2L))
        l.forget(1L, -100L)
        l.messagesForUser(1L, -100L) shouldHaveSize 0
        l.messagesForUser(2L, -100L) shouldHaveSize 1
    }
    "pruning drops rows past the retention window" {
        val l = log("prune")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.prune(T0.minusSeconds(1)) shouldBe 0
        l.prune(T0.plusSeconds(1)) shouldBe 1
        l.messagesForToken("tokA", 10) shouldHaveSize 0
    }
    "a chat migration keeps the tracked chat id readable" {
        val l = log("migrate")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        l.rewriteChatRef(-100L, -1001L) shouldBe 1
        l.messagesForToken("tokA", 10).first().chatId shouldBe -1001L
    }
})
```

- [ ] **Step 3: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.MessageLogRepositoryTest'`
Expected: FAIL — unresolved reference `MessageLogRepository`.

- [ ] **Step 4: Implement**

`src/main/kotlin/fxbot/MessageLogRepository.kt`:

```kotlin
package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

data class TrackedMessage(val chatId: Long, val messageId: Long)

@Serializable
private data class MessagePayload(val chatId: Long)

/**
 * What the bot said, and about whom — the record that makes forgetting reach
 * past the database and into the chat (ADR 0005).
 */
class MessageLogRepository(
    private val ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun record(chatId: Long, messageId: Long, refTokens: List<String>, userIds: List<Long>) {
        val chatRef = crypto.ref(chatId.toString())
        val aad = "$chatRef:$messageId"
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                c.prepareStatement(
                    "MERGE INTO sent_message (chat_ref, message_id, sent_at, payload) KEY (chat_ref, message_id) VALUES (?, ?, ?, ?)"
                ).use { st ->
                    st.setString(1, chatRef)
                    st.setLong(2, messageId)
                    st.setTimestamp(3, Timestamp.from(clock.instant()))
                    st.setBytes(4, crypto.seal(json.encodeToString(MessagePayload(chatId)), aad))
                    st.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO sent_message_ref (chat_ref, message_id, ref_token, user_ref) VALUES (?, ?, ?, ?)"
                ).use { st ->
                    for (i in refTokens.indices) {
                        st.setString(1, chatRef)
                        st.setLong(2, messageId)
                        st.setString(3, refTokens[i])
                        st.setString(4, crypto.ref(userIds[i].toString()))
                        st.addBatch()
                    }
                    st.executeBatch()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
    }

    fun messagesForToken(refToken: String, limit: Int): List<TrackedMessage> = select(
        """
        SELECT m.chat_ref, m.message_id, m.payload
        FROM sent_message m JOIN sent_message_ref r
          ON r.chat_ref = m.chat_ref AND r.message_id = m.message_id
        WHERE r.ref_token = ?
        ORDER BY m.sent_at DESC, m.message_id DESC
        LIMIT ?
        """.trimIndent()
    ) { st -> st.setString(1, refToken); st.setInt(2, limit) }

    fun messagesForUser(userId: Long, chatId: Long?): List<TrackedMessage> {
        val userRef = crypto.ref(userId.toString())
        val chatClause = if (chatId == null) "" else " AND m.chat_ref = ?"
        return select(
            """
            SELECT DISTINCT m.chat_ref, m.message_id, m.payload
            FROM sent_message m JOIN sent_message_ref r
              ON r.chat_ref = m.chat_ref AND r.message_id = m.message_id
            WHERE r.user_ref = ?$chatClause
            """.trimIndent()
        ) { st ->
            st.setString(1, userRef)
            chatId?.let { st.setString(2, crypto.ref(it.toString())) }
        }
    }

    /** True when the message also named somebody else — so redact, don't delete. */
    fun namesOthers(messageId: Long, chatId: Long, userId: Long): Boolean = ds.connection.use { c ->
        c.prepareStatement(
            "SELECT COUNT(*) FROM sent_message_ref WHERE chat_ref = ? AND message_id = ? AND user_ref <> ?"
        ).use { st ->
            st.setString(1, crypto.ref(chatId.toString()))
            st.setLong(2, messageId)
            st.setString(3, crypto.ref(userId.toString()))
            st.executeQuery().use { rs -> rs.next() && rs.getInt(1) > 0 }
        }
    }

    fun forget(userId: Long, chatId: Long?) = ds.connection.use { c ->
        val userRef = crypto.ref(userId.toString())
        val clause = if (chatId == null) "user_ref = ?" else "user_ref = ? AND chat_ref = ?"
        c.prepareStatement("DELETE FROM sent_message_ref WHERE $clause").use { st ->
            st.setString(1, userRef)
            chatId?.let { st.setString(2, crypto.ref(it.toString())) }
            st.executeUpdate()
        }
        Unit
    }

    fun prune(before: Instant): Int = ds.connection.use { c ->
        c.prepareStatement(
            """
            DELETE FROM sent_message_ref WHERE (chat_ref, message_id) IN
              (SELECT chat_ref, message_id FROM sent_message WHERE sent_at < ?)
            """.trimIndent()
        ).use { st -> st.setTimestamp(1, Timestamp.from(before)); st.executeUpdate() }
        c.prepareStatement("DELETE FROM sent_message WHERE sent_at < ?").use { st ->
            st.setTimestamp(1, Timestamp.from(before))
            st.executeUpdate()
        }
    }

    fun rewriteChatRef(oldChatId: Long, newChatId: Long): Int {
        val oldRef = crypto.ref(oldChatId.toString())
        val newRef = crypto.ref(newChatId.toString())
        val moved = messagesForChatRef(oldRef)
        ds.connection.use { c ->
            c.autoCommit = false
            try {
                for (m in moved) {
                    c.prepareStatement(
                        "UPDATE sent_message SET chat_ref = ?, payload = ? WHERE chat_ref = ? AND message_id = ?"
                    ).use { st ->
                        st.setString(1, newRef)
                        st.setBytes(2, crypto.seal(json.encodeToString(MessagePayload(newChatId)), "$newRef:${m.messageId}"))
                        st.setString(3, oldRef)
                        st.setLong(4, m.messageId)
                        st.executeUpdate()
                    }
                }
                c.prepareStatement("UPDATE sent_message_ref SET chat_ref = ? WHERE chat_ref = ?").use { st ->
                    st.setString(1, newRef); st.setString(2, oldRef); st.executeUpdate()
                }
                c.commit()
            } catch (e: Exception) {
                c.rollback(); throw e
            }
        }
        return moved.size
    }

    private fun messagesForChatRef(chatRef: String): List<TrackedMessage> = select(
        "SELECT chat_ref, message_id, payload FROM sent_message WHERE chat_ref = ?"
    ) { st -> st.setString(1, chatRef) }

    private fun select(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<TrackedMessage> =
        ds.connection.use { c ->
            c.prepareStatement(sql).use { st ->
                bind(st)
                st.executeQuery().use { rs ->
                    val out = mutableListOf<TrackedMessage>()
                    while (rs.next()) {
                        val chatRef = rs.getString("chat_ref")
                        val messageId = rs.getLong("message_id")
                        val payload = json.decodeFromString<MessagePayload>(
                            crypto.open(rs.getBytes("payload"), "$chatRef:$messageId")
                        )
                        out += TrackedMessage(payload.chatId, messageId)
                    }
                    out
                }
            }
        }
}
```

`src/main/kotlin/fxbot/ButtonService.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.message.editMessageText

private const val FAN_OUT = 10

/**
 * Strips buttons from the messages that still offer them for a closed request.
 * Bounded, because one cancel can touch several messages and Telegram rate-limits
 * edits; anything past the bound is caught when somebody presses it.
 */
class ButtonService(private val log: MessageLogRepository) {
    suspend fun stripFor(tokens: List<String>, chatId: Long, bot: TelegramBot) {
        val targets = tokens.flatMap { log.messagesForToken(it, FAN_OUT) }.distinct()
        for (t in targets) {
            runCatching {
                editMessageText(t.messageId) { "This one is closed now." }.send(t.chatId, bot)
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests, then record what the bot sends**

Run: `./gradlew test --tests 'fxbot.MessageLogRepositoryTest'`
Expected: PASS, 7 tests.

In `Commands.kt`, change the suggestion send to capture the message id and record it:

```kotlin
            val sent = message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .sendReturning(chat.id, bot)
                .getOrNull()
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    chat.id, id,
                    listOf(result.request.refToken) + result.found.map { it.request.refToken },
                    listOf(result.request.userId) + result.found.map { it.request.userId },
                )
            }
```

Add `lateinit var messages: MessageLogRepository` and `lateinit var buttons: ButtonService` to `Registry`, wire both in `Main.kt`, and restore the `Registry.buttons.stripFor(...)` line in `Callbacks.kt`.

- [ ] **Step 6: Build and commit**

```bash
./gradlew build
git add src/main src/test
git commit -m "feat: track sent messages and strip buttons when requests close"
```

---

### Task 12: Forgetting

**Files:**
- Create: `src/main/kotlin/fxbot/ForgetService.kt`
- Modify: `src/main/kotlin/fxbot/LifecycleCommands.kt` (add `/forget`), `Registry.kt`, `Main.kt`
- Test: `src/test/kotlin/fxbot/ForgetServiceTest.kt`

**Interfaces:**
- Consumes: `RequestRepository` (5), `MessageLogRepository` (11).
- Produces: `data class ForgetPlan(val deletedRequests: Int, val toDelete: List<TrackedMessage>, val toRedact: List<TrackedMessage>)`; `class ForgetService(requests, log, clock)` with `fun plan(userId: Long, chatId: Long?): ForgetPlan`.

Planning is separated from sending so the decisions are testable without Telegram. Deleting is only possible within 48 hours; redaction by editing has no such limit, which is why a message naming several people is edited rather than deleted.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/ForgetServiceTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")

private class Fixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, clock)
    val log = MessageLogRepository(ds, crypto, clock)
    val svc = ForgetService(requests, log, clock)
}

class ForgetServiceTest : StringSpec({
    "removes the person's requests in this chat only" {
        val f = Fixture("scope")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.svc.plan(1L, -100L).deletedRequests shouldBe 1
        f.requests.resting(-200L) shouldHaveSize 1
    }
    "a global forget reaches every chat" {
        val f = Fixture("global")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.requests.create(-200L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.svc.plan(1L, null).deletedRequests shouldBe 2
    }
    "a message naming only them is deleted; one naming others is redacted" {
        val f = Fixture("redact")
        f.log.record(-100L, 10L, listOf("tokA"), listOf(1L))
        f.log.record(-100L, 11L, listOf("tokA", "tokB"), listOf(1L, 2L))
        val plan = f.svc.plan(1L, -100L)
        plan.toDelete.map { it.messageId } shouldBe listOf(10L)
        plan.toRedact.map { it.messageId } shouldBe listOf(11L)
    }
    "the tracking rows for that person are gone afterwards" {
        val f = Fixture("cleared")
        f.log.record(-100L, 10L, listOf("tokA"), listOf(1L))
        f.svc.plan(1L, -100L)
        f.log.messagesForUser(1L, -100L) shouldHaveSize 0
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.ForgetServiceTest'`
Expected: FAIL — unresolved reference `ForgetService`.

- [ ] **Step 3: Implement**

`src/main/kotlin/fxbot/ForgetService.kt`:

```kotlin
package fxbot

import java.time.Clock

data class ForgetPlan(
    val deletedRequests: Int,
    val toDelete: List<TrackedMessage>,
    val toRedact: List<TrackedMessage>,
)

/**
 * Erases what is stored, and works out what can still be cleaned up in the chat.
 * A message that named other people is redacted rather than deleted, so their
 * names and their working buttons survive (ADR 0005).
 */
class ForgetService(
    private val requests: RequestRepository,
    private val log: MessageLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun plan(userId: Long, chatId: Long?): ForgetPlan {
        val messages = log.messagesForUser(userId, chatId)
        val (redact, delete) = messages.partition { log.namesOthers(it.messageId, it.chatId, userId) }
        val removed = requests.deleteFor(userId, chatId).size
        log.forget(userId, chatId)
        return ForgetPlan(removed, delete, redact)
    }
}
```

- [ ] **Step 4: Add the command**

Append to `src/main/kotlin/fxbot/LifecycleCommands.kt`:

```kotlin
private const val REDACTED = "(a message was edited at someone's request)"

@CommandHandler(["/forget"])
suspend fun forget(update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val global = update.text.trim().split(Regex("\\s+")).getOrNull(1)?.lowercase() == "all"
    val isPrivate = chat.type.name.lowercase().contains("private")

    if (global && !isPrivate) {
        message { "Send /forget all to me in a private chat — from here I'd be reaching into your other groups." }
            .send(chat.id, bot)
        return
    }

    val plan = Registry.forget.plan(user.id, if (global) null else chat.id)
    for (m in plan.toDelete) {
        runCatching { eu.vendeli.tgbot.api.message.deleteMessage(m.messageId).send(m.chatId, bot) }
    }
    for (m in plan.toRedact) {
        runCatching { eu.vendeli.tgbot.api.message.editMessageText(m.messageId) { REDACTED }.send(m.chatId, bot) }
    }
    message {
        "Erased ${plan.deletedRequests} request(s) and tidied ${plan.toDelete.size + plan.toRedact.size} message(s). " +
            "I can't unsay what was already said, and I can't touch other people's messages."
    }.send(chat.id, bot)
}
```

Add `lateinit var forget: ForgetService` to `Registry` and wire it in `Main.kt`.

- [ ] **Step 5: Build, test, commit**

```bash
./gradlew build
git add src/main src/test
git commit -m "feat: erase a person's data and tidy the messages naming them"
```

---

### Task 13: Scheduled work

**Files:**
- Create: `src/main/kotlin/fxbot/Tasks.kt`
- Modify: `src/main/kotlin/fxbot/Main.kt`
- Test: `src/test/kotlin/fxbot/TasksTest.kt`

**Interfaces:**
- Consumes: `RequestRepository` (5), `ChatSettingsRepository` (6), `RateService` (7), `MessageLogRepository` (11).
- Produces: `class Housekeeping(requests, settings, rates, log, clock)` with `fun sweep(): Int` and `suspend fun refreshRates()`; `fun startScheduler(ds: DataSource, housekeeping: Housekeeping)`.

Both scheduled tasks are **parameterless**: db-scheduler's `task_data` is an unencrypted BLOB, so nothing that identifies a chat may travel in it.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/TasksTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val EURRUB = CurrencyPair("EUR", "RUB")
private val T0 = Instant.parse("2026-08-30T12:00:00Z")
private const val BODY = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}"""

private fun housekeeping(name: String, at: Instant): Pair<Housekeeping, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val clock = Clock.fixed(at, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
    val settings = ChatSettingsRepository(ds, crypto, clock)
    val rates = RateService(
        RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
        RateRepository(ds), clock,
    )
    return Housekeeping(requests, settings, rates, MessageLogRepository(ds, crypto, clock), clock) to requests
}

class TasksTest : StringSpec({
    "the sweep lapses requests past their time in force" {
        val (hk, repo) = housekeeping("sweep", T0.plusSeconds(8 * 86_400))
        repo.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        hk.sweep() shouldBe 1
        repo.resting(-100L) shouldHaveSize 0
    }
    "the sweep leaves live requests alone" {
        val (hk, repo) = housekeeping("sweepalive", T0.plusSeconds(86_400))
        repo.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        hk.sweep() shouldBe 0
        repo.resting(-100L) shouldHaveSize 1
    }
    "the refresh caches a rate for every pair a chat has configured" {
        val ds = memDataSource("refresh")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val settings = ChatSettingsRepository(ds, crypto, clock)
        settings.save(ChatSettings(-100L, EURRUB, 20, 7))
        val rateRepo = RateRepository(ds)
        val rates = RateService(
            RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })),
            rateRepo, clock,
        )
        Housekeeping(RequestRepository(ds, crypto, clock), settings, rates, MessageLogRepository(ds, crypto, clock), clock)
            .refreshRates()
        rateRepo.get("EUR", "RUB")!!.rate shouldBe BigDecimal("99.98")
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.TasksTest'`
Expected: FAIL — unresolved reference `Housekeeping`.

- [ ] **Step 3: Implement**

`src/main/kotlin/fxbot/Tasks.kt`:

```kotlin
package fxbot

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

private val RETENTION: Duration = Duration.ofDays(90)

class Housekeeping(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
    private val log: MessageLogRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Lapses what is past its time in force, and prunes the message record. */
    fun sweep(): Int {
        val expired = requests.expireDue(clock.instant())
        log.prune(clock.instant().minus(RETENTION))
        return expired
    }

    suspend fun refreshRates() = rates.refresh(settings.allPairs())
}

/**
 * Both recurring tasks carry no data: db-scheduler stores task_data as a
 * plaintext BLOB, and nothing identifying belongs there.
 */
fun startScheduler(ds: DataSource, housekeeping: Housekeeping) {
    val sweep = Tasks.recurring("sweep", Schedules.fixedDelay(Duration.ofHours(24)))
        .execute { _, _ -> housekeeping.sweep() }
    val refresh = Tasks.recurring("refresh-rates", Schedules.fixedDelay(Duration.ofHours(24)))
        .execute { _, _ -> runBlocking { housekeeping.refreshRates() } }

    Scheduler.create(ds).startTasks(sweep, refresh).threads(2).build().start()
}
```

In `Main.kt`, after wiring the repositories:

```kotlin
    val housekeeping = Housekeeping(Registry.requests, Registry.settings, Registry.rates, Registry.messages)
    startScheduler(ds, housekeeping)
    housekeeping.refreshRates()   // don't wait a day for the first rate
```

- [ ] **Step 4: Build, test, commit**

```bash
./gradlew build
git add src/main src/test
git commit -m "feat: daily expiry sweep, message pruning and rate refresh"
```

---

### Task 14: Admin settings

**Files:**
- Create: `src/main/kotlin/fxbot/AdminCommands.kt`, `src/main/kotlin/fxbot/AdminService.kt`
- Test: `src/test/kotlin/fxbot/AdminServiceTest.kt`

**Interfaces:**
- Consumes: `ChatSettingsRepository` (6), `RateClient` (7), `parseCurrency` (3).
- Produces: `class AdminService(settings, client)` with `suspend fun setPair(chatId, base, quote): String`, `fun setTolerance(chatId, raw): String`, `fun setTif(chatId, raw): String`.

Permission checking stays in the handler, where the Telegram API lives; the service holds the validation, which is what needs testing.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/AdminServiceTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

private const val BODY = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}"""

private fun admin(name: String): Pair<AdminService, ChatSettingsRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val settings = ChatSettingsRepository(ds, testCrypto())
    val client = RateClient(HttpClient(MockEngine { respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }))
    return AdminService(settings, client) to settings
}

class AdminServiceTest : StringSpec({
    "sets a pair the feed can price" {
        val (svc, settings) = admin("pair")
        svc.setPair(-100L, "EUR", "RUB") shouldContain "EUR/RUB"
        settings.get(-100L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
    "refuses a code that is not ISO 4217" {
        val (svc, _) = admin("badiso")
        svc.setPair(-100L, "EUR", "XYZ") shouldContain "XYZ"
    }
    "refuses a pair the feed cannot price" {
        val (svc, settings) = admin("nofeed")
        svc.setPair(-100L, "EUR", "GBP") shouldContain "can't get a rate"
        settings.get(-100L).pair shouldBe CurrencyPair("EUR", "RUB")
    }
    "refuses the same currency twice" {
        val (svc, _) = admin("same")
        svc.setPair(-100L, "EUR", "EUR") shouldContain "two different"
    }
    "tolerance must be a sensible percentage" {
        val (svc, settings) = admin("tol")
        svc.setTolerance(-100L, "5")
        settings.get(-100L).tolerancePct shouldBe 5
        svc.setTolerance(-100L, "0") shouldContain "between"
        svc.setTolerance(-100L, "101") shouldContain "between"
        svc.setTolerance(-100L, "lots") shouldContain "between"
    }
    "time in force must be a sensible number of days" {
        val (svc, settings) = admin("tif")
        svc.setTif(-100L, "30")
        settings.get(-100L).tifDays shouldBe 30
        svc.setTif(-100L, "0") shouldContain "between"
        svc.setTif(-100L, "400") shouldContain "between"
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.AdminServiceTest'`
Expected: FAIL — unresolved reference `AdminService`.

- [ ] **Step 3: Implement**

`src/main/kotlin/fxbot/AdminService.kt`:

```kotlin
package fxbot

class AdminService(
    private val settings: ChatSettingsRepository,
    private val rateClient: RateClient,
) {
    /** ISO 4217 first, then the feed — an admin cannot pick a pair we can never price. */
    suspend fun setPair(chatId: Long, rawBase: String, rawQuote: String): String {
        val base = parseCurrency(rawBase) ?: return "\"$rawBase\" isn't a currency code I know."
        val quote = parseCurrency(rawQuote) ?: return "\"$rawQuote\" isn't a currency code I know."
        if (base == quote) return "A pair needs two different currencies."
        val rates = rateClient.fetch(base)
        if (rates == null || rates[quote] == null) {
            return "I can't get a rate for $base/$quote, so I couldn't compare amounts across the two."
        }
        val current = settings.get(chatId)
        settings.save(current.copy(pair = CurrencyPair(base, quote)))
        return "This chat now swaps $base/$quote. Requests made before now keep their old currencies until they lapse."
    }

    fun setTolerance(chatId: Long, raw: String): String {
        val pct = raw.toIntOrNull()
        if (pct == null || pct !in 1..100) return "Give me a percentage between 1 and 100, like /tolerance 20"
        settings.save(settings.get(chatId).copy(tolerancePct = pct))
        return "Amounts now match when they're within $pct% of each other."
    }

    fun setTif(chatId: Long, raw: String): String {
        val days = raw.toIntOrNull()
        if (days == null || days !in 1..365) return "Give me a number of days between 1 and 365, like /tif 7"
        settings.save(settings.get(chatId).copy(tifDays = days))
        return "Requests now wait $days day(s) before they lapse."
    }
}
```

`src/main/kotlin/fxbot/AdminCommands.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.annotations.CommandHandler
import eu.vendeli.tgbot.api.chat.getChatMember
import eu.vendeli.tgbot.api.message.message
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.utils.common.getChat
import eu.vendeli.tgbot.utils.common.getUser

/** Denies on an API failure rather than assuming permission. */
private suspend fun isAdmin(update: ProcessedUpdate, bot: TelegramBot): Boolean {
    val chat = update.getChat()
    val member = runCatching { getChatMember(update.getUser().id).sendReturning(chat.id, bot).getOrNull() }.getOrNull()
    val status = member?.status?.name?.lowercase() ?: return false
    return status == "creator" || status == "administrator"
}

private suspend fun adminOnly(update: ProcessedUpdate, bot: TelegramBot, body: suspend (List<String>) -> String) {
    val chat = update.getChat()
    if (!isAdmin(update, bot)) {
        message { "Only this chat's admins can change that." }.send(chat.id, bot)
        return
    }
    message { body(update.text.trim().split(Regex("\\s+")).drop(1)) }.send(chat.id, bot)
}

@CommandHandler(["/pair"])
suspend fun pair(update: ProcessedUpdate, bot: TelegramBot) = adminOnly(update, bot) { args ->
    if (args.size < 2) "Tell me both currencies, like /pair EUR RUB"
    else Registry.admin.setPair(update.getChat().id, args[0], args[1])
}

@CommandHandler(["/tolerance"])
suspend fun tolerance(update: ProcessedUpdate, bot: TelegramBot) = adminOnly(update, bot) { args ->
    Registry.admin.setTolerance(update.getChat().id, args.firstOrNull().orEmpty())
}

@CommandHandler(["/tif"])
suspend fun tif(update: ProcessedUpdate, bot: TelegramBot) = adminOnly(update, bot) { args ->
    Registry.admin.setTif(update.getChat().id, args.firstOrNull().orEmpty())
}
```

Add `lateinit var admin: AdminService` to `Registry`, wire it in `Main.kt`, and add the three commands to `setMyCommands`.

- [ ] **Step 4: Build, test, commit**

```bash
./gradlew build
git add src/main src/test
git commit -m "feat: admin-only pair, size tolerance and time in force"
```

---

### Task 15: Supergroup migration

**Files:**
- Create: `src/main/kotlin/fxbot/ChatMigration.kt`
- Test: `src/test/kotlin/fxbot/ChatMigrationTest.kt`

**Interfaces:**
- Consumes: the three repositories that key on `chat_ref`.
- Produces: `class ChatMigrationService(requests, settings, log)` with `fun migrate(oldChatId: Long, newChatId: Long): Int`.

When a group becomes a supergroup its chat id changes and Telegram sends `migrate_to_chat_id` once. Without this, every waiting request silently becomes unreachable.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/fxbot/ChatMigrationTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

class ChatMigrationTest : StringSpec({
    "everything the chat owned follows it to the new id" {
        val ds = memDataSource("chatmigrate")
        migrate(ds)
        val crypto = testCrypto()
        val requests = RequestRepository(ds, crypto)
        val settings = ChatSettingsRepository(ds, crypto)
        val log = MessageLogRepository(ds, crypto)

        requests.create(-100L, 1L, "alice", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        settings.save(ChatSettings(-100L, CurrencyPair("USD", "GBP"), 5, 30))
        log.record(-100L, 10L, listOf("tokA"), listOf(1L))

        ChatMigrationService(requests, settings, log).migrate(-100L, -1001L) shouldBe 1

        val moved = requests.resting(-1001L)
        moved shouldHaveSize 1
        moved[0].username shouldBe "alice"
        settings.get(-1001L).pair shouldBe CurrencyPair("USD", "GBP")
        log.messagesForToken("tokA", 10).first().chatId shouldBe -1001L
        requests.resting(-100L) shouldHaveSize 0
    }
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `./gradlew test --tests 'fxbot.ChatMigrationTest'`
Expected: FAIL — unresolved reference `ChatMigrationService`.

- [ ] **Step 3: Implement**

`src/main/kotlin/fxbot/ChatMigration.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.annotations.UpdateHandler
import eu.vendeli.tgbot.types.component.ProcessedUpdate
import eu.vendeli.tgbot.types.component.UpdateType

/**
 * A group upgraded to a supergroup keeps nothing of its old chat id. The id lives
 * in the ref columns and inside each sealed payload, so the repositories reseal
 * their rows; the AAD is the ref token and never changes (ADR 0002).
 */
class ChatMigrationService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val log: MessageLogRepository,
) {
    fun migrate(oldChatId: Long, newChatId: Long): Int {
        val moved = requests.rewriteChatRef(oldChatId, newChatId)
        settings.rewriteChatRef(oldChatId, newChatId)
        log.rewriteChatRef(oldChatId, newChatId)
        return moved
    }
}

@UpdateHandler([UpdateType.MESSAGE])
suspend fun onChatMigration(update: ProcessedUpdate) {
    val message = (update as? eu.vendeli.tgbot.types.component.MessageUpdate)?.message ?: return
    val newId = message.migrateToChatId ?: return
    Registry.migration.migrate(message.chat.id, newId)
}
```

Add `lateinit var migration: ChatMigrationService` to `Registry` and wire it in `Main.kt`.

If `migrateToChatId` is spelled differently on this framework version, find the
right property with:
`unzip -l ~/.gradle/caches/modules-2/files-2.1/eu.vendeli/telegram-bot-jvm/9.6.0/*/telegram-bot-jvm-9.6.0.jar | grep -i message`
then `javap -p` the message class and look for the migrate field.

- [ ] **Step 4: Build, test, commit**

```bash
./gradlew build
git add src/main src/test
git commit -m "feat: follow a group through its supergroup migration"
```

---

### Task 16: Deployment

**Files:**
- Create: `Dockerfile`, `compose.yaml`, `README.md`
- Create: `src/main/kotlin/fxbot/KeygenMain.kt`
- Modify: `build.gradle.kts` (register the `keygen` task)

**Interfaces:**
- Consumes: `KeysetGen` (2).
- Produces: a runnable image, and `./gradlew keygen` printing a ready-to-paste `.env` fragment.

- [ ] **Step 1: Add the keyset generator**

`src/main/kotlin/fxbot/KeygenMain.kt`:

```kotlin
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
```

Add to `build.gradle.kts`:

```kotlin
tasks.register<JavaExec>("keygen") {
    group = "application"
    description = "Print a fresh pair of Tink keysets for .env"
    mainClass.set("fxbot.KeygenMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}
```

- [ ] **Step 2: Verify it produces keys the bot accepts**

```bash
./gradlew keygen -q
```
Expected: two long `DATA_KEYSET=` / `INDEX_KEYSET=` lines of JSON.

- [ ] **Step 3: Write the Dockerfile and compose file**

`Dockerfile`:

```dockerfile
FROM gradle:8-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle --no-daemon installDist

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/install/exchange-bot /app
VOLUME ["/app/data"]
ENV DB_PATH=/app/data/exchange
ENTRYPOINT ["/app/bin/exchange-bot"]
```

`compose.yaml`:

```yaml
services:
  bot:
    build: .
    restart: unless-stopped
    env_file: .env
    volumes:
      - ./data:/app/data
```

- [ ] **Step 4: Write the README**

`README.md`:

```markdown
# exchange-bot

A Telegram bot that introduces people in the same group chat who want opposite
sides of the same currency exchange. It passes names and gets out of the way —
it never holds money, quotes a price, or settles anything.

## Setup

1. Create a bot with @BotFather and copy the token.
2. `cp .env.example .env`
3. `./gradlew keygen -q >> .env` and fill in `BOT_TOKEN`, `DB_FILE_KEY`, `DB_USER_PW`.
4. `docker compose up -d`
5. Add the bot to your group. Anyone can post; admins set the currencies.

## Using it

    /sell 1000 EUR    you're handing over 1000 EUR
    /buy 1000 EUR     you want to receive 1000 EUR
    /status           who's waiting
    /cancel a1        withdraw yours
    /done a1 @someone you two swapped
    /reopen           undo your last /done
    /forget           erase what's stored about you here

`/sell` and `/buy` are two ways of saying the same four things: what matters is
which currency you hand over. Selling euros and buying roubles are the same
side, so they never match each other.

Admins: `/pair EUR RUB`, `/tolerance 20`, `/tif 7`.

## Keys

Five secrets live only in the environment. Lose `DATA_KEYSET` or `INDEX_KEYSET`
and the database is unreadable — there is no recovery path, by design. Back
them up somewhere other than the machine running the bot.

## Documentation

- Design: `docs/superpowers/specs/2026-08-30-exchange-bot-design.md`
- Vocabulary: `CONTEXT.md`
- Decisions: `docs/adr/`
```

- [ ] **Step 5: Full verification**

```bash
./gradlew build
docker compose build
```
Expected: BUILD SUCCESSFUL, and an image that builds.

- [ ] **Step 6: Commit**

```bash
git add Dockerfile compose.yaml README.md build.gradle.kts src/main/kotlin/fxbot/KeygenMain.kt
git commit -m "feat: container image, keyset generator and README"
```

---

## Notes from the self-review

Checked against the spec; three things worth flagging to whoever executes this.

**Two deviations from the spec, both deliberate:**

1. **`sent_message` gained an encrypted payload (Task 11, `V2`).** The spec's schema stores only `chat_ref`, but editing or deleting a message needs the real chat id and a keyed hash cannot be reversed. The chat id is sealed like every other identity rather than stored in the clear.
2. **The sealed request payload carries `chatId` (Task 5).** The spec's payload list omits it, but without it a rotation of the MAC keyset would orphan every `chat_ref` with no way to re-derive it — which contradicts the rotation story ADR 0002 tells. Storing it also removes the awkward case where a row fetched by token had no chat id. The cost is that a supergroup migration reseals that chat's rows instead of only updating a column.

**Two places where the framework's exact spelling was not verified**, each with the check to run rather than a guess to make: the `migrate_to_chat_id` property name (Task 15 Step 3 gives the `javap` command) and the `ProcessedUpdate` import set (Task 9 Step 6 points at the neighbour file using the same version). Everything else — `answerCallbackQuery(id).options { text; showAlert }`, `getChatMember(userId)`, `editMessageText(id) { }`, `deleteMessages(List<Long>)`, `sendReturning`, and the 64-byte `callback_data` limit — was read off the 9.6.0 jar and the Bot API docs directly.

**Spec coverage:** every section maps to a task. Sides and notionals → 3, 4. Rate degradation → 7. Commands → 9, 10, 12, 14. Buttons and authorization → 8, 10. Encryption → 2, 5, 6. Forgetting → 11, 12. Scheduled work → 13. Migration → 15. Deployment and keys → 1, 16. The only spec behaviour with no dedicated task is the "channels are refused" line, which is folded into Task 9's `isGroupChat` guard.
