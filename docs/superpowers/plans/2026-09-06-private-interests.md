# Private Interests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a person state an interest to the bot privately, show it in every group they share with the bot whose pair fits, and work it bot-wide on a no-names basis against people they share no chat with.

**Architecture:** No new request table. An interest is a set of `request` rows sharing a plaintext `interest_token`: one row per chat (a *showing*), plus one row at the sentinel `chatId = 0` (the *no-names* request). Size tolerance becomes a per-side residual, so each side judges its own leftover against its own number. Announcements of privately-stated interests are batched on a 60-second tumbling window per person and persisted so a restart re-renders them from live state. Identities on the no-names side pass only after both sides press a button, with consent read from a table, never from callback data.

**Tech Stack:** Kotlin/JVM (toolchain 25), `eu.vendeli:telegram-bot` 9.6.0 (+ `ktnip` KSP), Exposed 1.5.0 (core + jdbc only), H2 2.4.240 with `CIPHER=AES` in PostgreSQL mode, Flyway 11.8.2, HikariCP, db-scheduler 16.12.0, Ktor client CIO, Google Tink 1.18.0, Kotest 6.2.3.

**Spec:** `docs/superpowers/specs/2026-09-06-private-interests-design.md`
**Glossary:** `CONTEXT.md` — its vocabulary is binding in code, comments and user strings.
**Decisions:** `docs/adr/0001`–`0007`. ADR 0006 (per-side residual, no combinations) and ADR 0007 (no-names working, mutual give-up) are the two this plan implements.

## Global Constraints

- Kotlin `2.4.10`, KSP `2.3.10`, JVM toolchain **25** (BellSoft), Gradle/shadow `9.6.1`. Do not change any of these.
- Pinned versions stay exactly as `gradle/libs.versions.toml` has them. **No new dependency may be added by any task in this plan.** Exposed is `exposed-core` + `exposed-jdbc` only — never `exposed-migration-jdbc`, `exposed-java-time`, or `exposed-kotlin-datetime`.
- **Flyway owns the schema; Exposed is a query layer.** New tables go in a new `V3__*.sql` and are mirrored by hand in `Tables.kt`. Never call `SchemaUtils.create`.
- **H2 runs in PostgreSQL compatibility mode.** Schema columns are `TEXT`, `BYTEA`, `BIGINT`, `TIMESTAMP` — never a guessed `CHAR(n)` width, never `BLOB`.
- **Vocabulary is binding** (`CONTEXT.md`): *interest*, *showing*, *no-names basis*, *name give-up*, *residual*, *size tolerance*, *counterparty*, *resting*, *notional*, *time in force*, `Side.BID` / `Side.OFFER`. The words **order**, **book**, **fill**, **execution**, **pool**, **market**, **dark pool**, **reveal**, **match** (as a noun for a pairing) must not appear in identifiers, comments, or user strings.
- All user-facing strings are **plain English** — never `notional`, `bid`, `offer`, `residual` in a chat message.
- Money is always `BigDecimal`, never `Double`, serialized as a JSON **string**.
- Everything identifying is either a sealed Tink AEAD payload or a keyed HMAC ref (`crypto.ref`). Plaintext columns are allowed only for random tokens (`ref_token`, `interest_token`) and public data (`fx_rate`).
- `callback_data` must stay ≤ 64 bytes and is never trusted: every callback re-derives the presser from `callback_query.from.id` and re-authorizes against the database.
- Logging: counts and fixed outcome labels only. Never a chat id, user id, username, amount, or message text. Use `logCommand(command, outcome)` on the command surface.
- The sentinel `chatId = 0` means "no chat" — Telegram never issues 0. Declare it once as `const val NO_NAMES_CHAT_ID = 0L` in `Request.kt` and use it everywhere.
- Run `./gradlew test` before every commit. Commit messages are Conventional Commits.

## Interpretation notes (ambiguities resolved once, here)

These two readings are locked for the whole plan so tasks do not disagree:

1. **"`buy 10 RUB for EUR` must meet `sell 10 EUR for RUB`"** (spec, Posting an interest, step 1). Those two statements are the same intent expressed two ways — both hand over EUR and receive RUB — so under `sideFor` they are the *same* side, not counterparties. What the canonical pair buys is that they land in the same pair space with the same side, so a third person on the opposite side meets either one identically. Task 8's test asserts exactly that, not that the two are counterparties.
2. **Where a restated residual goes** (spec, Residuals after a done). A residual of a request that came from a privately stated interest (its row has an `interest_token`, or it *is* the no-names row) is restated as a new private interest and fans out. A residual of a plain request typed in a group (`interest_token IS NULL`) is restated as a new request in that same chat only. Fanning a group-typed request out bot-wide would put someone on the no-names side who never asked, which ADR 0007 forbids ("consent is what puts someone on the no-names side, and it is given by stating the interest privately"). The button is still offered in both places, which is what the spec's "including for a request typed in a group" requires.

---

## File Structure

**New files**

- `src/main/resources/db/migration/V3__private_interests.sql` — the `interest_token` column and three new tables.
- `src/main/kotlin/fxbot/PersonSettingsRepository.kt` — one person's own size tolerance, sealed under their `user_ref`.
- `src/main/kotlin/fxbot/NameGiveUpRepository.kt` — consent and decline rows for the give-up path.
- `src/main/kotlin/fxbot/PendingAnnouncementRepository.kt` — what is owed an announcement, never how it reads.
- `src/main/kotlin/fxbot/InterestService.kt` — stating an interest privately: parse, price the pair, pick the chats, create the rows, find no-names counterparties.
- `src/main/kotlin/fxbot/AnnouncementBatcher.kt` — the 60-second window, the flush, the restart re-render.
- `src/main/kotlin/fxbot/GiveUpService.kt` — offer, decline, disclose.
- `src/main/kotlin/fxbot/Residual.kt` — the residual arithmetic and the restate decision.
- `src/main/kotlin/fxbot/PrivateCommands.kt` — the private-chat command surface and the Telegram-side probes (`MembershipProbe`, `NameLookup`, `AnnouncementSink`).

**Modified files**

- `Tables.kt`, `Request.kt`, `Matcher.kt`, `RequestRepository.kt`, `ChatSettingsRepository.kt`, `MessageLogRepository.kt`, `LifecycleService.kt`, `ButtonService.kt`, `AdminService.kt`, `AdminCommands.kt`, `Commands.kt`, `LifecycleCommands.kt`, `Callbacks.kt`, `Render.kt`, `ForgetService.kt`, `Tasks.kt`, `Registry.kt`, `Main.kt`.

**Tests** mirror main under `src/test/kotlin/fxbot/`: new `PersonSettingsRepositoryTest`, `NameGiveUpRepositoryTest`, `PendingAnnouncementRepositoryTest`, `InterestServiceTest`, `AnnouncementBatcherTest`, `GiveUpServiceTest`, `ResidualTest`, `ButtonServiceTest`, `PrivateCommandTest`; extended `MatcherTest`, `RequestRepositoryTest`, `ChatSettingsRepositoryTest`, `MessageLogRepositoryTest`, `LifecycleServiceTest`, `AdminServiceTest`, `ForgetCommandTest`, `TasksTest`, `SchemaDriftTest`.

---

### Task 1: Migration V3 and the Exposed table objects

**Files:**
- Create: `src/main/resources/db/migration/V3__private_interests.sql`
- Modify: `src/main/kotlin/fxbot/Tables.kt` (add `Requests.interestToken`; add three table objects)
- Test: `src/test/kotlin/fxbot/SchemaDriftTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `Requests.interestToken: Column<String?>`; `object PersonSettingsTable`, `object NameGiveUps`, `object PendingAnnouncements`. Later tasks query these.

Note: two payload changes the spec asks for need **no** SQL, because both columns are already sealed JSON blobs and `Json { ignoreUnknownKeys = true }` plus a Kotlin default makes the new field backward-compatible. They are done in Tasks 5 and 7, not here:
- `sent_message.payload` gains the rendered message text (Task 7).
- `chat_settings.payload` gains the fan-out flag (Task 5).

- [ ] **Step 1: Write the failing test**

Extend `SchemaDriftTest.kt` — replace the `tables` array line with:

```kotlin
            val tables = arrayOf(
                Requests, ChatSettingsTable, FxRates, SentMessages, SentMessageRefs,
                PersonSettingsTable, NameGiveUps, PendingAnnouncements,
            )
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew test --tests 'fxbot.SchemaDriftTest'`
Expected: FAIL — `Unresolved reference: PersonSettingsTable` (and the other two).

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V3__private_interests.sql`:

```sql
-- Plaintext like ref_token: random, carries no personal data, and a sealed payload
-- cannot be queried, so the sibling link has to be a column. Rows predating V3 keep
-- NULL and are interests of one showing.
ALTER TABLE request ADD COLUMN interest_token TEXT;
CREATE INDEX request_interest_idx ON request (interest_token);

-- One person's own size tolerance, for no-names working. Sealed like chat_settings;
-- the AAD is the user_ref, so forgetting deletes it with the predicate the schema
-- already uses everywhere else.
CREATE TABLE person_settings (
    user_ref   TEXT PRIMARY KEY,
    payload    BYTEA     NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- One row per direction of a pairing. Two 'offered' rows mean both consented; one
-- 'declined' row suppresses the pairing for both. Rows die with their requests.
CREATE TABLE name_give_up (
    ref_token      TEXT      NOT NULL,
    peer_ref_token TEXT      NOT NULL,
    user_ref       TEXT      NOT NULL,
    stance         TEXT      NOT NULL,
    decided_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (ref_token, peer_ref_token)
);
CREATE INDEX name_give_up_user_idx ON name_give_up (user_ref);

-- What to announce, never how: the text is re-rendered from live state at flush time.
CREATE TABLE pending_announcement (
    chat_ref       TEXT      NOT NULL,
    interest_token TEXT      NOT NULL,
    user_ref       TEXT      NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (chat_ref, interest_token)
);
CREATE INDEX pending_announcement_user_idx ON pending_announcement (user_ref);
```

- [ ] **Step 4: Mirror the schema in `Tables.kt`**

Add `interestToken` to `Requests`, after `payload`:

```kotlin
    val payload = binary("payload")
    val interestToken = text("interest_token").nullable()
```

Append the three new objects at the end of the file:

```kotlin
object PersonSettingsTable : Table("person_settings") {
    val userRef = text("user_ref")
    val payload = binary("payload")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userRef)
}

object NameGiveUps : Table("name_give_up") {
    val refToken = text("ref_token")
    val peerRefToken = text("peer_ref_token")
    val userRef = text("user_ref")
    val stance = text("stance")
    val decidedAt = timestamp("decided_at")
    override val primaryKey = PrimaryKey(refToken, peerRefToken)
}

object PendingAnnouncements : Table("pending_announcement") {
    val chatRef = text("chat_ref")
    val interestToken = text("interest_token")
    val userRef = text("user_ref")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(chatRef, interestToken)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'fxbot.SchemaDriftTest'`
Expected: PASS — Exposed needs no statements to actualize the scheme Flyway built.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew test`
Expected: PASS. `RequestRepository.hydrate` does not read the new column yet, and a nullable added column breaks nothing.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V3__private_interests.sql src/main/kotlin/fxbot/Tables.kt src/test/kotlin/fxbot/SchemaDriftTest.kt
git commit -m "feat: add V3 schema for private interests"
```

---

### Task 2: Size tolerance becomes a per-side residual

**Files:**
- Modify: `src/main/kotlin/fxbot/Matcher.kt`
- Test: `src/test/kotlin/fxbot/MatcherTest.kt`

**Interfaces:**
- Consumes: `Request`, `CurrencyPair`, `notional` (unchanged).
- Produces:
  ```kotlin
  fun findCounterparties(
      subject: Request,
      resting: List<Request>,
      rate: BigDecimal?,
      tolerancePct: Int,
      limit: Int = 5,
      peerTolerancePct: (Request) -> Int = { tolerancePct },
      suppressed: (Request, Request) -> Boolean = { _, _ -> false },
  ): List<Counterparty>
  ```
  The two new parameters have defaults, so every existing call site and every existing test compiles untouched. `Counterparty(request, notional, distance)` is unchanged — `distance` stays `|a − b| / larger`, used only for ordering, never for acceptance.

- [ ] **Step 1: Write the failing tests**

Append to `MatcherTest.kt`, inside the `StringSpec({ ... })` block:

```kotlin
    // --- ADR 0006: each side judges its own residual against its own tolerance.

    "a smaller counterparty is a counterparty when the larger side accepts the leftover" {
        // A sells 4 EUR with a 50% tolerance; B buys 2. A's residual is 2 of 4 = 50%.
        val a = req(Verb.SELL, "4", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "2", "EUR")), RATE, 50) shouldHaveSize 1
    }
    "the larger side's own tolerance can exclude a pairing the smaller side accepts" {
        // Same shapes, A's tolerance is 20: 50% > 20, so A refuses even though B has no residual.
        val a = req(Verb.SELL, "4", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "2", "EUR")), RATE, 20).shouldBeEmpty()
    }
    "the smaller side has no residual of its own, so it accepts however tight its tolerance is" {
        // B is the subject now, with a tolerance of 1: B's residual is 0, A's is not B's business.
        val b = req(Verb.BUY, "2", "EUR")
        findCounterparties(b, listOf(req(Verb.SELL, "4", "EUR")), RATE, 1, peerTolerancePct = { 50 })
            .shouldHaveSize(1)
    }
    "the peer's own tolerance is consulted, not the subject's" {
        // The subject is small and generous; the peer is large and strict, so the peer refuses.
        val b = req(Verb.BUY, "2", "EUR")
        findCounterparties(b, listOf(req(Verb.SELL, "4", "EUR")), RATE, 100, peerTolerancePct = { 20 })
            .shouldBeEmpty()
    }
    "a residual exactly equal to the tolerance is accepted" {
        val a = req(Verb.SELL, "1000", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "800", "EUR")), RATE, 20) shouldHaveSize 1
        findCounterparties(a, listOf(req(Verb.BUY, "799", "EUR")), RATE, 20).shouldBeEmpty()
    }
    "a near-equal pairing leaves a residual well inside a 20 percent tolerance" {
        val a = req(Verb.SELL, "1000", "EUR")
        findCounterparties(a, listOf(req(Verb.BUY, "999", "EUR")), RATE, 20) shouldHaveSize 1
    }
    "a suppressed pairing is not a counterparty, and reappears when the suppression lifts" {
        val a = req(Verb.SELL, "1000", "EUR")
        val b = req(Verb.BUY, "1000", "EUR")
        findCounterparties(a, listOf(b), RATE, 20, suppressed = { _, _ -> true }).shouldBeEmpty()
        findCounterparties(a, listOf(b), RATE, 20, suppressed = { _, _ -> false }) shouldHaveSize 1
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.MatcherTest'`
Expected: FAIL — "a smaller counterparty is a counterparty..." fails under the old `|a − b| / larger` rule (50% > 20 rejects it even at tolerance 50? no — it passes there; the one that fails first is *"the peer's own tolerance is consulted"*, plus the two `peerTolerancePct` / `suppressed` cases fail to compile: `No parameter with name 'peerTolerancePct'`). A compile error is a valid red.

- [ ] **Step 3: Rewrite the matching rule**

Replace `findCounterparties` in `Matcher.kt` with:

```kotlin
/**
 * What is left of [mine] when [theirs] is smaller, as a fraction of [mine] — the
 * residual each side judges against its own size tolerance (ADR 0006). A counterparty
 * at least as big as you leaves you nothing, so your answer is always yes.
 */
private fun residualFraction(mine: BigDecimal, theirs: BigDecimal): BigDecimal =
    if (theirs >= mine) BigDecimal.ZERO else (mine - theirs).divide(mine, MC)

/**
 * Every resting request in the same chat that is on the opposite side and leaves each
 * side a residual it accepts, closest first. Reserves nothing (ADR 0001).
 *
 * Each side is judged separately against its own number: [tolerancePct] is the
 * subject's, [peerTolerancePct] answers for a candidate. In a chat both are that
 * chat's setting and this reduces to the old test exactly; on a no-names basis each
 * person brings their own. Counterparties are strictly pairwise — the bot never
 * searches for a set that together covers a size (ADR 0006).
 *
 * [suppressed] hides a pairing without storing anything: it is evaluated fresh on every
 * call, so a pairing suppressed while a live showing already pairs the two people, or
 * while one of them has declined, reappears the moment that stops being true.
 *
 * With no reference rate, only requests quoted in the same currency as the subject can
 * be compared — that comparison needs no conversion.
 */
fun findCounterparties(
    subject: Request,
    resting: List<Request>,
    rate: BigDecimal?,
    tolerancePct: Int,
    limit: Int = 5,
    peerTolerancePct: (Request) -> Int = { tolerancePct },
    suppressed: (Request, Request) -> Boolean = { _, _ -> false },
): List<Counterparty> {
    if (subject.state != RequestState.OPEN) return emptyList()
    val mineLimit = BigDecimal(tolerancePct).divide(HUNDRED, MC)
    return resting.asSequence()
        .filter { it.chatId == subject.chatId }
        .filter { it.pair == subject.pair }
        .filter { it.state == RequestState.OPEN }
        .filter { it.side != subject.side }
        .filter { it.userId != subject.userId }
        .filter { !suppressed(subject, it) }
        .mapNotNull { candidate ->
            val (a, b) = comparableSizes(subject, candidate, rate) ?: return@mapNotNull null
            // A non-positive size cannot carry a residual: dividing by it would throw, and
            // parseAmount rejects zero and negatives, so this only guards a corrupt row.
            if (a.signum() <= 0 || b.signum() <= 0) return@mapNotNull null
            if (residualFraction(a, b) > mineLimit) return@mapNotNull null
            val theirLimit = BigDecimal(peerTolerancePct(candidate)).divide(HUNDRED, MC)
            if (residualFraction(b, a) > theirLimit) return@mapNotNull null
            // Ordering only. Acceptance was decided by the two residuals above.
            val distance = (a - b).abs().divide(a.max(b), MC)
            Counterparty(candidate, notional(candidate, rate), distance)
        }
        .sortedBy { it.distance }
        .take(limit)
        .toList()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'fxbot.MatcherTest'`
Expected: PASS — new cases and all pre-existing ones, including "closest size first, capped at five" (the ordering is unchanged because `distance` is unchanged).

- [ ] **Step 5: Reword the tolerance confirmation**

`AdminService.setTolerance`'s reply no longer describes what the setting does. In `AdminService.kt` replace the success line:

```kotlin
        return "A counterparty now matches when what they'd leave you is within $pct% of your own amount."
```

Run: `./gradlew test --tests 'fxbot.AdminServiceTest'` — expected PASS (that test asserts only on the rejection text, which contains "between").

- [ ] **Step 6: Run the whole suite and commit**

Run: `./gradlew test`
Expected: PASS.

```bash
git add src/main/kotlin/fxbot/Matcher.kt src/main/kotlin/fxbot/AdminService.kt src/test/kotlin/fxbot/MatcherTest.kt
git commit -m "feat: judge size tolerance as each side's own residual"
```

---

### Task 3: `interest_token` on the request, and the sibling queries

**Files:**
- Modify: `src/main/kotlin/fxbot/Request.kt` (add `interestToken`, add `NO_NAMES_CHAT_ID`)
- Modify: `src/main/kotlin/fxbot/RequestRepository.kt`
- Test: `src/test/kotlin/fxbot/RequestRepositoryTest.kt`

**Interfaces:**
- Consumes: `Requests.interestToken` (Task 1).
- Produces:
  ```kotlin
  const val NO_NAMES_CHAT_ID = 0L
  data class Request(..., val expiresAt: Instant, val interestToken: String? = null)

  // RequestRepository — all new or changed:
  fun create(chatId, userId, username, side, statedCurrency, statedAmount, pair, tifDays,
             interestToken: String? = null): Request
  fun siblings(interestToken: String): List<Request>
  fun closeInterest(interestToken: String, to: RequestState): List<String>
  fun reopenInterest(interestToken: String, from: RequestState, tifFor: (Long) -> Int): List<String>
  fun countOpenInterests(userId: Long): Int
  fun noNamesPairs(): Set<CurrencyPair>
  fun expireDue(now: Instant): List<String>   // was Int
  ```
  `interestToken` is last on `create` with a default, so every existing call site and test compiles untouched. `expireDue` changes its return type from `Int` to the ref tokens lapsed, because Task 12 needs to edit the messages that carried them; `Housekeeping.sweep` is the only caller.

- [ ] **Step 1: Write the failing tests**

Append to `RequestRepositoryTest.kt`, inside the spec block (use the file's existing `repo(name)`-style helper — read the top of the file and reuse whatever it already provides for building a repository and a fixed clock):

```kotlin
    "rows born of one interest share its token, and siblings finds them all" {
        val r = repo("siblings")
        val tok = "int-1"
        val noNames = r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        r.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        r.create(-300L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7) // a different interest
        r.siblings(tok) shouldHaveSize 3
        r.byRefToken(noNames.refToken)!!.interestToken shouldBe tok
    }
    "a request typed in a chat has no interest token" {
        val r = repo("lonetoken")
        val a = r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7)
        r.byRefToken(a.refToken)!!.interestToken shouldBe null
    }
    "closing an interest closes every open row on its token, in one go" {
        val r = repo("closeinterest")
        val tok = "int-2"
        val a = r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        val b = r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        val closed = r.closeInterest(tok, RequestState.CANCELLED)
        closed.toSet() shouldBe setOf(a.refToken, b.refToken)
        r.byRefToken(a.refToken)!!.state shouldBe RequestState.CANCELLED
        r.byRefToken(b.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "closing an interest leaves an already-closed sibling alone and does not report it" {
        val r = repo("closeidempotent")
        val tok = "int-3"
        val a = r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        val b = r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        r.transition(b.refToken, RequestState.OPEN, RequestState.EXPIRED)
        r.closeInterest(tok, RequestState.DONE) shouldBe listOf(a.refToken)
        r.byRefToken(b.refToken)!!.state shouldBe RequestState.EXPIRED
    }
    "reopening an interest revives only the siblings closed the same way" {
        val r = repo("reopeninterest")
        val tok = "int-4"
        val a = r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        val b = r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        val c = r.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, tok)
        r.transition(c.refToken, RequestState.OPEN, RequestState.EXPIRED) // one chat's housekeeping, not a decision
        r.closeInterest(tok, RequestState.DONE)
        r.reopenInterest(tok, RequestState.DONE, tifFor = { 7 }).toSet() shouldBe setOf(a.refToken, b.refToken)
        r.byRefToken(a.refToken)!!.state shouldBe RequestState.OPEN
        r.byRefToken(b.refToken)!!.state shouldBe RequestState.OPEN
        r.byRefToken(c.refToken)!!.state shouldBe RequestState.EXPIRED
    }
    "the cap counts a person's resting no-names interests, not their showings" {
        val r = repo("countinterests")
        r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        r.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        r.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i3")
        r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7) // typed in a chat: uncapped
        r.countOpenInterests(1L) shouldBe 2
    }
    "a cancelled interest stops counting against the cap" {
        val r = repo("countafterclose")
        r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        r.closeInterest("i1", RequestState.CANCELLED)
        r.countOpenInterests(1L) shouldBe 0
    }
    "the pairs resting on a no-names basis can be enumerated for the rate refresh" {
        val r = repo("nonamespairs")
        r.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        r.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.OFFER, "CHF", BigDecimal("10"), CurrencyPair("CHF", "JPY"), 7, "i2")
        r.create(-100L, 3L, "cat", Side.OFFER, "USD", BigDecimal("10"), CurrencyPair("USD", "GBP"), 7)
        r.noNamesPairs() shouldBe setOf(EURRUB, CurrencyPair("CHF", "JPY"))
    }
    "the sweep reports which requests lapsed, so their messages can be edited" {
        val r = repoAt("sweeptokens", T0.plusSeconds(8 * 86_400))
        val a = r.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7)
        r.expireDue(T0.plusSeconds(8 * 86_400)) shouldBe listOf(a.refToken)
    }
```

If `RequestRepositoryTest.kt` has no `repoAt(name, at)` helper (a repository whose `Clock` is fixed at a chosen instant), add one next to the existing helper — the create clock stays `T0` so a 7-day time in force really has lapsed by `T0 + 8 days`.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.RequestRepositoryTest'`
Expected: FAIL to compile — `Unresolved reference: siblings`, `NO_NAMES_CHAT_ID`, `closeInterest`, `countOpenInterests`, `noNamesPairs`, `reopenInterest`.

- [ ] **Step 3: Add the sentinel and the field**

In `Request.kt`:

```kotlin
/**
 * The chat id a no-names request carries. Telegram never issues 0, and `Matcher`
 * already filters on `chatId ==`, so the two surfaces cannot meet by accident.
 */
const val NO_NAMES_CHAT_ID = 0L
```

and add the field last on `Request`, so every positional construction in the existing tests still compiles:

```kotlin
    val expiresAt: Instant,
    /** The sibling link: every row born of one interest carries the same value. Null for a request typed in a chat. */
    val interestToken: String? = null,
)
```

- [ ] **Step 4: Carry the token through the repository**

In `RequestRepository.kt`:

1. `create` — add `interestToken: String? = null` as the last parameter, write `it[Requests.interestToken] = interestToken` in the insert, and pass `interestToken = interestToken` into the returned `Request`.
2. `hydrate` — add `interestToken = row[Requests.interestToken]`.
3. `rewriteChatRef` — its `update` sets only `chatRef` and `payload`, so the token is preserved untouched; no change needed, but do not rebuild the row from scratch.
4. Add the new queries:

```kotlin
    /** Every row born of the same interest, whatever state each is in. */
    fun siblings(interestToken: String): List<Request> = transaction(db) {
        Requests.selectAll()
            .where { Requests.interestToken eq interestToken }
            .orderBy(Requests.rowId to SortOrder.ASC)
            .map { hydrate(it) }
    }

    /**
     * Done and cancel are decisions about the whole interest, so they close every OPEN
     * row sharing its token in one transaction, with the same terminal state. Expiry is
     * NOT this: a time in force running out is one chat's housekeeping policy, and
     * [expireDue] closes only the showing that lapsed.
     *
     * Returns the ref tokens actually closed — an already-closed sibling is left exactly
     * as it is and is not reported, so message cleanup does not restate somebody else's
     * outcome.
     */
    fun closeInterest(interestToken: String, to: RequestState): List<String> = transaction(db) {
        val open = Requests.selectAll()
            .where { (Requests.interestToken eq interestToken) and (Requests.state eq RequestState.OPEN.name) }
            .map { it[Requests.refToken] }
        val now = clock.instant()
        Requests.update({
            (Requests.interestToken eq interestToken) and (Requests.state eq RequestState.OPEN.name)
        }) {
            it[Requests.state] = to.name
            it[Requests.closedAt] = now
        }
        open
    }

    /**
     * Revives the siblings a done or a cancel closed, each with a fresh expiry from its
     * own chat's time in force ([tifFor] answers for a chat id; the no-names row uses the
     * 7-day default its caller passes). A sibling that merely LAPSED is left closed —
     * reopen undoes a decision, and an expiry was never one.
     */
    fun reopenInterest(interestToken: String, from: RequestState, tifFor: (Long) -> Int): List<String> =
        transaction(db) {
            val rows = Requests.selectAll()
                .where { (Requests.interestToken eq interestToken) and (Requests.state eq from.name) }
                .map { hydrate(it) }
            val now = clock.instant()
            for (r in rows) {
                Requests.update({ Requests.refToken eq r.refToken }) {
                    it[Requests.state] = RequestState.OPEN.name
                    it[Requests.expiresAt] = now.plusSeconds(tifFor(r.chatId).toLong() * 86_400)
                    it[Requests.closedAt] = null
                }
            }
            rows.map { it.refToken }
        }

    /**
     * How many interests this person has resting on a no-names basis — the figure the
     * five-interest cap is judged against. Counts interests, not showings: one statement
     * shown in four chats is one. Requests typed in a chat carry no token and are uncapped.
     */
    fun countOpenInterests(userId: Long): Int = transaction(db) {
        Requests.selectAll()
            .where {
                (Requests.chatRef eq crypto.ref(NO_NAMES_CHAT_ID.toString())) and
                    (Requests.userRef eq crypto.ref(userId.toString())) and
                    (Requests.state eq RequestState.OPEN.name)
            }
            .mapNotNull { it[Requests.interestToken] }
            .distinct()
            .size
    }

    /**
     * The pairs resting on a no-names basis. `Housekeeping.refreshRates` enumerates chat
     * pairs from `chat_settings`; without this, a pair no chat uses would never get a
     * reference rate and could never be compared across denominations.
     */
    fun noNamesPairs(): Set<CurrencyPair> = transaction(db) {
        Requests.selectAll()
            .where {
                (Requests.chatRef eq crypto.ref(NO_NAMES_CHAT_ID.toString())) and
                    (Requests.state eq RequestState.OPEN.name)
            }
            .map { hydrate(it).pair }
            .toSet()
    }
```

5. Change `expireDue` to report what it lapsed:

```kotlin
    /**
     * Lapses what is past its time in force and returns the ref tokens, so the messages
     * that carried them can be edited. Stamps `closed_at` like every other close, so
     * recency ordering sees expiries. One chat's housekeeping only: an interest's other
     * showings live on, and a chat with `/tif 1` drops out on day one without shortening
     * anything else.
     */
    fun expireDue(now: Instant): List<String> = transaction(db) {
        val due = Requests.selectAll()
            .where { (Requests.state eq RequestState.OPEN.name) and (Requests.expiresAt less now) }
            .map { it[Requests.refToken] }
        Requests.update({ (Requests.state eq RequestState.OPEN.name) and (Requests.expiresAt less now) }) {
            it[Requests.state] = RequestState.EXPIRED.name
            it[Requests.closedAt] = now
        }
        due
    }
```

- [ ] **Step 5: Fix the one caller of `expireDue`**

In `Tasks.kt`, `Housekeeping.sweep`:

```kotlin
        val expired = requests.expireDue(clock.instant())
        val pruned = log.prune(clock.instant().minus(RETENTION))
        logger.info("sweep: expired=${expired.size} pruned=$pruned")
        return expired.size
```

(`sweep` keeps returning `Int` for now; Task 14 gives it its final shape.)

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests 'fxbot.RequestRepositoryTest'` then `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/fxbot/Request.kt src/main/kotlin/fxbot/RequestRepository.kt src/main/kotlin/fxbot/Tasks.kt src/test/kotlin/fxbot/RequestRepositoryTest.kt
git commit -m "feat: link an interest's rows with an interest token"
```

---

### Task 4: A person's own size tolerance

**Files:**
- Create: `src/main/kotlin/fxbot/PersonSettingsRepository.kt`
- Test: `src/test/kotlin/fxbot/PersonSettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `PersonSettingsTable` (Task 1), `Crypto`, `connectExposed`.
- Produces:
  ```kotlin
  data class PersonSettings(val userId: Long, val tolerancePct: Int)
  const val DEFAULT_PERSON_TOLERANCE = 20
  class PersonSettingsRepository(ds: DataSource, crypto: Crypto, clock: Clock = Clock.systemUTC(), db: Database = connectExposed(ds)) {
      fun get(userId: Long): PersonSettings
      fun save(s: PersonSettings)
      fun setTolerance(userId: Long, raw: String): String   // the user-facing reply, 1–100 bounded
      fun delete(userId: Long)
  }
  ```

Unlike `ChatSettingsRepository.get`, this `get` does **not** persist defaults. That write exists there only so `allPairs()` sees a chat that never ran an admin command; nothing enumerates person rows, so writing one for every person who is merely looked at would store a row about somebody who never asked for one.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/fxbot/PersonSettingsRepositoryTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private fun people(name: String): Pair<PersonSettingsRepository, org.jetbrains.exposed.v1.jdbc.Database> {
    val ds = memDataSource(name)
    migrate(ds)
    return PersonSettingsRepository(ds, testCrypto()) to connectExposed(ds)
}

class PersonSettingsRepositoryTest : StringSpec({
    "someone the bot has never been told about gets the twenty percent default" {
        val (r, _) = people("persondefault")
        r.get(7L).tolerancePct shouldBe 20
    }
    "looking someone up does not write a row about them" {
        val (r, db) = people("personnowrite")
        r.get(7L)
        transaction(db) { PersonSettingsTable.selectAll().count() } shouldBe 0L
    }
    "a saved tolerance round-trips" {
        val (r, _) = people("personsave")
        r.save(PersonSettings(7L, 5))
        r.get(7L).tolerancePct shouldBe 5
    }
    "tolerances are per person" {
        val (r, _) = people("personper")
        r.save(PersonSettings(7L, 5))
        r.get(8L).tolerancePct shouldBe 20
    }
    "saving twice updates in place" {
        val (r, db) = people("personresave")
        r.save(PersonSettings(7L, 5))
        r.save(PersonSettings(7L, 40))
        r.get(7L).tolerancePct shouldBe 40
        transaction(db) { PersonSettingsTable.selectAll().count() } shouldBe 1L
    }
    "the tolerance command bounds the percentage between one and a hundred" {
        val (r, _) = people("personbounds")
        r.setTolerance(7L, "1")
        r.get(7L).tolerancePct shouldBe 1
        r.setTolerance(7L, "100")
        r.get(7L).tolerancePct shouldBe 100
        r.setTolerance(7L, "0") shouldContain "between"
        r.setTolerance(7L, "101") shouldContain "between"
        r.setTolerance(7L, "lots") shouldContain "between"
        r.get(7L).tolerancePct shouldBe 100 // none of the three rejections wrote anything
    }
    "forgetting drops the row" {
        val (r, _) = people("persondelete")
        r.save(PersonSettings(7L, 5))
        r.delete(7L)
        r.get(7L).tolerancePct shouldBe 20
    }
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'fxbot.PersonSettingsRepositoryTest'`
Expected: FAIL — `Unresolved reference: PersonSettingsRepository`.

- [ ] **Step 3: Write the repository**

Create `src/main/kotlin/fxbot/PersonSettingsRepository.kt`:

```kotlin
package fxbot

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import javax.sql.DataSource

/** One person's own size tolerance, used only for no-names working. It never overrides a chat's. */
data class PersonSettings(val userId: Long, val tolerancePct: Int)

@Serializable
private data class PersonPayload(
    val userId: Long,          // stored so a MAC-keyset rotation can re-derive user_ref
    val tolerancePct: Int,
)

const val DEFAULT_PERSON_TOLERANCE = 20

/**
 * Sealed like `chat_settings`, and for the same reason; the associated data here is the
 * `user_ref` itself, not a ref token. Keyed by `user_ref` so forgetting deletes it with
 * the predicate the schema already uses everywhere else.
 *
 * [get] deliberately does NOT persist the default, unlike [ChatSettingsRepository.get]:
 * that write exists there only so the daily rate refresh can enumerate a chat that never
 * ran an admin command, and nothing enumerates person rows. Writing one for everybody who
 * gets looked at would store a row about somebody who never asked for one.
 */
class PersonSettingsRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(userId: Long): PersonSettings = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        val payload = PersonSettingsTable.selectAll()
            .where { PersonSettingsTable.userRef eq userRef }
            .singleOrNull()
            ?.let { json.decodeFromString<PersonPayload>(crypto.open(it[PersonSettingsTable.payload], userRef)) }
        PersonSettings(userId, payload?.tolerancePct ?: DEFAULT_PERSON_TOLERANCE)
    }

    fun save(s: PersonSettings): Unit = transaction(db) {
        val userRef = crypto.ref(s.userId.toString())
        val sealed = crypto.seal(json.encodeToString(PersonPayload(s.userId, s.tolerancePct)), userRef)
        PersonSettingsTable.upsert {
            it[PersonSettingsTable.userRef] = userRef
            it[payload] = sealed
            it[updatedAt] = clock.instant()
        }
    }

    /** Bounded 1–100 like the chat command, and rejected without writing anything. */
    fun setTolerance(userId: Long, raw: String): String {
        val pct = raw.toIntOrNull()
        if (pct == null || pct !in 1..100) return "Give me a percentage between 1 and 100, like /tolerance 20"
        save(PersonSettings(userId, pct))
        return "A counterparty now matches when what they'd leave you is within $pct% of your own amount."
    }

    fun delete(userId: Long): Unit = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        PersonSettingsTable.deleteWhere { PersonSettingsTable.userRef eq userRef }
        Unit
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'fxbot.PersonSettingsRepositoryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/fxbot/PersonSettingsRepository.kt src/test/kotlin/fxbot/PersonSettingsRepositoryTest.kt
git commit -m "feat: store a person's own size tolerance"
```

---

### Task 5: Per-chat fan-out, and enumerating chats

**Files:**
- Modify: `src/main/kotlin/fxbot/ChatSettingsRepository.kt`
- Modify: `src/main/kotlin/fxbot/AdminService.kt`, `src/main/kotlin/fxbot/AdminCommands.kt`
- Modify: `src/main/kotlin/fxbot/Commands.kt` (the `/settings` reply)
- Test: `src/test/kotlin/fxbot/ChatSettingsRepositoryTest.kt`, `src/test/kotlin/fxbot/AdminServiceTest.kt`

**Interfaces:**
- Consumes: `ChatSettingsRepository` as it stands.
- Produces:
  ```kotlin
  data class ChatSettings(val chatId: Long, val pair: CurrencyPair, val tolerancePct: Int, val tifDays: Int, val fanOut: Boolean = true)
  fun ChatSettingsRepository.allChats(): List<ChatSettings>
  fun AdminService.setFanOut(chatId: Long, raw: String): String
  @CommandHandler(["/fanout"]) suspend fun fanout(update: ProcessedUpdate, bot: TelegramBot)
  ```
  `fanOut` is last with a default, so every existing positional `ChatSettings(...)` in tests compiles untouched. No SQL: the flag lives in the sealed payload with a Kotlin default of `true`, so an existing chat needs no backfill.

- [ ] **Step 1: Write the failing tests**

Append to `ChatSettingsRepositoryTest.kt`:

```kotlin
    "fan-out is on unless an admin turned it off" {
        settingsRepo("fanoutdefault").get(-100L).fanOut shouldBe true
    }
    "a row written before the flag existed still reads as fan-out on" {
        val r = settingsRepo("fanoutlegacy")
        r.save(ChatSettings(-100L, CurrencyPair("EUR", "RUB"), 20, 7)) // the four-argument form
        r.get(-100L).fanOut shouldBe true
    }
    "fan-out round-trips when it is turned off" {
        val r = settingsRepo("fanoutoff")
        r.save(ChatSettings(-100L, CurrencyPair("EUR", "RUB"), 20, 7, fanOut = false))
        r.get(-100L).fanOut shouldBe false
    }
    "every chat can be enumerated, with its own id, for the fan-out search" {
        val r = settingsRepo("allchats")
        r.save(ChatSettings(-100L, CurrencyPair("EUR", "RUB"), 20, 7))
        r.save(ChatSettings(-200L, CurrencyPair("USD", "GBP"), 5, 30, fanOut = false))
        r.allChats().map { it.chatId }.toSet() shouldBe setOf(-100L, -200L)
        r.allChats().single { it.chatId == -200L }.fanOut shouldBe false
        r.allChats().single { it.chatId == -200L }.pair shouldBe CurrencyPair("USD", "GBP")
    }
```

Append to `AdminServiceTest.kt`:

```kotlin
    "fan-out can be turned off and back on" {
        val (svc, settings) = admin("fanout")
        svc.setFanOut(-100L, "off") shouldContain "won't"
        settings.get(-100L).fanOut shouldBe false
        svc.setFanOut(-100L, "on") shouldContain "will"
        settings.get(-100L).fanOut shouldBe true
    }
    "fan-out refuses anything that isn't on or off" {
        val (svc, settings) = admin("fanoutbad")
        svc.setFanOut(-100L, "maybe") shouldContain "/fanout on"
        settings.get(-100L).fanOut shouldBe true
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.ChatSettingsRepositoryTest' --tests 'fxbot.AdminServiceTest'`
Expected: FAIL — `No parameter with name 'fanOut'`, `Unresolved reference: allChats`, `Unresolved reference: setFanOut`.

- [ ] **Step 3: Add the flag and the enumeration**

In `ChatSettingsRepository.kt`:

```kotlin
data class ChatSettings(
    val chatId: Long,
    val pair: CurrencyPair,
    val tolerancePct: Int,
    val tifDays: Int,
    /** Off means no showing is created in this chat at all — see [AdminService.setFanOut]. */
    val fanOut: Boolean = true,
)

@Serializable
private data class SettingsPayload(
    val chatId: Long,          // stored so a MAC-keyset rotation can re-derive chat_ref
    val base: String,
    val quote: String,
    val tolerancePct: Int,
    val tifDays: Int,
    // Defaulted, so a payload sealed before this field existed decodes as fan-out on and
    // no chat needs a backfill.
    val fanOut: Boolean = true,
)
```

Thread `fanOut` through `get`, the defaults it persists, and `save`:

```kotlin
        if (payload != null) {
            return@transaction ChatSettings(
                chatId, CurrencyPair(payload.base, payload.quote),
                payload.tolerancePct, payload.tifDays, payload.fanOut,
            )
        }
        val defaults = ChatSettings(chatId, DEFAULT_PAIR, DEFAULT_TOLERANCE, DEFAULT_TIF_DAYS)
        val body = SettingsPayload(
            chatId, DEFAULT_PAIR.base, DEFAULT_PAIR.quote, DEFAULT_TOLERANCE, DEFAULT_TIF_DAYS, defaults.fanOut,
        )
```

```kotlin
    fun save(s: ChatSettings): Unit = transaction(db) {
        val chatRef = crypto.ref(s.chatId.toString())
        val body = SettingsPayload(s.chatId, s.pair.base, s.pair.quote, s.tolerancePct, s.tifDays, s.fanOut)
        writeIn(chatRef, crypto.seal(json.encodeToString(body), chatRef))
    }
```

Replace `allPairs` with an enumeration that keeps it:

```kotlin
    /**
     * Every chat the bot has settings for, with its real id out of the sealed payload —
     * the chat_ref column is a keyed MAC and cannot be reversed. This is what the fan-out
     * search filters by pair and by the fan-out flag.
     */
    fun allChats(): List<ChatSettings> = transaction(db) {
        ChatSettingsTable.selectAll().map { row ->
            val p = json.decodeFromString<SettingsPayload>(
                crypto.open(row[ChatSettingsTable.payload], row[ChatSettingsTable.chatRef]),
            )
            ChatSettings(p.chatId, CurrencyPair(p.base, p.quote), p.tolerancePct, p.tifDays, p.fanOut)
        }
    }

    fun allPairs(): Set<CurrencyPair> = allChats().map { it.pair }.toSet()
```

- [ ] **Step 4: Add the admin command**

In `AdminService.kt`:

```kotlin
    /**
     * Off means no showing is created in this chat at all — not merely a suppressed
     * announcement. A silent-but-matchable showing is exactly what an admin turning this
     * off would object to. Showings created while it was on live out their time in force.
     */
    fun setFanOut(chatId: Long, raw: String): String {
        val on = when (raw.trim().lowercase()) {
            "on" -> true
            "off" -> false
            else -> return "Tell me on or off, like /fanout on"
        }
        settings.save(settings.get(chatId).copy(fanOut = on))
        return if (on) {
            "I will show interests here that people have stated to me privately."
        } else {
            "I won't show interests here that people have stated to me privately. Anything already waiting stays until it lapses."
        }
    }
```

In `AdminCommands.kt`:

```kotlin
@CommandHandler(["/fanout"])
suspend fun fanout(update: ProcessedUpdate, bot: TelegramBot) = adminOnly("fanout", update, bot) { args ->
    Registry.admin.setFanOut(update.getChat().id, args.firstOrNull().orEmpty())
}
```

In `Commands.kt`, extend the `/settings` reply so the flag is visible:

```kotlin
    message {
        "This chat swaps ${s.pair}. A counterparty matches when what they'd leave you is within " +
            "${s.tolerancePct}% of your own amount, and a request waits ${s.tifDays} days before it lapses. " +
            (if (s.fanOut) "I also show interests people state to me privately here. "
             else "I don't show interests people state to me privately here. ") +
            "Admins can change this with /pair, /tolerance, /tif and /fanout."
    }.send(chat.id, bot)
```

In `Main.kt`, add to the `commands` builder, after `botCommand("tif", ...)`:

```kotlin
        botCommand("fanout", "Admins: show privately stated interests here")
```

And in `Commands.kt`'s `HELP_TEXT`, after the `/tif` line:

```
    /fanout on — admins: whether I show interests stated to me privately here
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/fxbot/ChatSettingsRepository.kt src/main/kotlin/fxbot/AdminService.kt src/main/kotlin/fxbot/AdminCommands.kt src/main/kotlin/fxbot/Commands.kt src/main/kotlin/fxbot/Main.kt src/test/kotlin/fxbot/ChatSettingsRepositoryTest.kt src/test/kotlin/fxbot/AdminServiceTest.kt
git commit -m "feat: let a chat's admins turn fan-out off"
```

---

### Task 6: The give-up and pending-announcement repositories

**Files:**
- Create: `src/main/kotlin/fxbot/NameGiveUpRepository.kt`
- Create: `src/main/kotlin/fxbot/PendingAnnouncementRepository.kt`
- Test: `src/test/kotlin/fxbot/NameGiveUpRepositoryTest.kt`, `src/test/kotlin/fxbot/PendingAnnouncementRepositoryTest.kt`

**Interfaces:**
- Consumes: `NameGiveUps`, `PendingAnnouncements` (Task 1), `Requests` (Task 3), `Crypto`.
- Produces:
  ```kotlin
  enum class Stance { OFFERED, DECLINED }
  class NameGiveUpRepository(ds, crypto, clock = Clock.systemUTC(), db = connectExposed(ds)) {
      fun record(refToken: String, peerRefToken: String, userId: Long, stance: Stance)
      fun stanceOf(refToken: String, peerRefToken: String): Stance?
      fun declined(a: String, b: String): Boolean
      fun bothOffered(a: String, b: String): Boolean
      fun deleteFor(userId: Long)
      fun dropClosed(): Int
  }

  data class PendingAnnouncement(val chatRef: String, val interestToken: String, val createdAt: Instant)
  class PendingAnnouncementRepository(ds, crypto, clock = Clock.systemUTC(), db = connectExposed(ds)) {
      fun add(chatId: Long, interestToken: String, userId: Long)
      fun all(): List<PendingAnnouncement>
      fun allFor(userId: Long): List<PendingAnnouncement>
      fun isFor(row: PendingAnnouncement, chatId: Long): Boolean
      fun remove(chatRef: String, interestToken: String)
      fun deleteFor(userId: Long)
      fun dropOlderThan(cutoff: Instant): Int
      fun dropClosed(): Int
  }
  ```
  `PendingAnnouncement` carries the `chat_ref`, not a chat id, because that table has no sealed payload to hide one in. The flush resolves the real chat id from the interest's own showing rows, whose payloads do carry it, and pairs the two with [isFor].

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/fxbot/NameGiveUpRepositoryTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private fun giveUps(name: String): Pair<NameGiveUpRepository, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    return NameGiveUpRepository(ds, crypto) to RequestRepository(ds, crypto)
}

class NameGiveUpRepositoryTest : StringSpec({
    "one side's offer is recorded and does not read as both" {
        val (g, _) = giveUps("oneside")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.stanceOf("a", "b") shouldBe Stance.OFFERED
        g.stanceOf("b", "a") shouldBe null
        g.bothOffered("a", "b") shouldBe false
    }
    "both offers together mean both consented" {
        val (g, _) = giveUps("bothsides")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.record("b", "a", 2L, Stance.OFFERED)
        g.bothOffered("a", "b") shouldBe true
        g.bothOffered("b", "a") shouldBe true
    }
    "one decline suppresses the pairing for both, whichever way round it is asked" {
        val (g, _) = giveUps("decline")
        g.record("b", "a", 2L, Stance.DECLINED)
        g.declined("a", "b") shouldBe true
        g.declined("b", "a") shouldBe true
        g.bothOffered("a", "b") shouldBe false
    }
    "a second press on the same direction replaces the stance, it does not add a row" {
        val (g, _) = giveUps("restance")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.record("a", "b", 1L, Stance.DECLINED)
        g.stanceOf("a", "b") shouldBe Stance.DECLINED
        g.declined("a", "b") shouldBe true
    }
    "rows die with their requests" {
        val (g, requests) = giveUps("dropclosed")
        val a = requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        val b = requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7, "i2")
        g.record(a.refToken, b.refToken, 1L, Stance.OFFERED)
        g.record("gone-1", "gone-2", 3L, Stance.OFFERED) // neither request exists at all
        g.dropClosed() shouldBe 1
        g.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED

        requests.closeInterest("i2", RequestState.DONE)
        g.dropClosed() shouldBe 1
        g.stanceOf(a.refToken, b.refToken) shouldBe null
    }
    "forgetting drops the rows the person wrote" {
        val (g, _) = giveUps("giveupforget")
        g.record("a", "b", 1L, Stance.OFFERED)
        g.record("b", "a", 2L, Stance.OFFERED)
        g.deleteFor(1L)
        g.stanceOf("a", "b") shouldBe null
        g.stanceOf("b", "a") shouldBe Stance.OFFERED
    }
})
```

Create `src/test/kotlin/fxbot/PendingAnnouncementRepositoryTest.kt`:

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
private val T0 = Instant.parse("2026-09-06T12:00:00Z")

private fun pendings(name: String, at: Instant = T0): Pair<PendingAnnouncementRepository, RequestRepository> {
    val ds = memDataSource(name)
    migrate(ds)
    val crypto = testCrypto()
    val clock = Clock.fixed(at, ZoneOffset.UTC)
    return PendingAnnouncementRepository(ds, crypto, clock) to RequestRepository(ds, crypto, clock)
}

class PendingAnnouncementRepositoryTest : StringSpec({
    "what is owed an announcement is remembered, and can be tied back to its chat" {
        val (p, _) = pendings("pendadd")
        p.add(-100L, "i1", 1L)
        val row = p.all().single()
        row.interestToken shouldBe "i1"
        p.isFor(row, -100L) shouldBe true
        p.isFor(row, -200L) shouldBe false
    }
    "the same chat and interest is one row however often it is added" {
        val (p, _) = pendings("penddupe")
        p.add(-100L, "i1", 1L)
        p.add(-100L, "i1", 1L)
        p.all() shouldHaveSize 1
    }
    "a person's own pending announcements can be picked out" {
        val (p, _) = pendings("pendmine")
        p.add(-100L, "i1", 1L)
        p.add(-100L, "i2", 2L)
        p.allFor(1L).single().interestToken shouldBe "i1"
    }
    "a flushed announcement is removed" {
        val (p, _) = pendings("pendremove")
        p.add(-100L, "i1", 1L)
        val row = p.all().single()
        p.remove(row.chatRef, row.interestToken)
        p.all() shouldHaveSize 0
    }
    "an announcement older than an hour is dropped" {
        val (p, _) = pendings("pendstale")
        p.add(-100L, "i1", 1L)
        p.dropOlderThan(T0.minusSeconds(1)) shouldBe 0
        p.dropOlderThan(T0.plusSeconds(3_601)) shouldBe 1
        p.all() shouldHaveSize 0
    }
    "an announcement whose showings have all closed is dropped" {
        val (p, requests) = pendings("penddead")
        requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("10"), EURRUB, 7, "i1")
        p.add(-100L, "i1", 1L)
        p.add(-200L, "i-never-existed", 1L)
        p.dropClosed() shouldBe 1
        p.all().single().interestToken shouldBe "i1"
        requests.closeInterest("i1", RequestState.CANCELLED)
        p.dropClosed() shouldBe 1
        p.all() shouldHaveSize 0
    }
    "forgetting drops the person's pending announcements" {
        val (p, _) = pendings("pendforget")
        p.add(-100L, "i1", 1L)
        p.add(-100L, "i2", 2L)
        p.deleteFor(1L)
        p.all().single().interestToken shouldBe "i2"
    }
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.NameGiveUpRepositoryTest' --tests 'fxbot.PendingAnnouncementRepositoryTest'`
Expected: FAIL — `Unresolved reference: NameGiveUpRepository` / `PendingAnnouncementRepository`.

- [ ] **Step 3: Write `NameGiveUpRepository.kt`**

```kotlin
package fxbot

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import javax.sql.DataSource

/** What one side decided about one pairing. Two [OFFERED] rows mean both consented. */
enum class Stance { OFFERED, DECLINED }

/**
 * Consent for a name give-up, persisted rather than carried in a button (ADR 0007). A
 * hand-crafted callback payload claiming the other side agreed achieves nothing, because
 * this table is where agreement is read from.
 *
 * One row per DIRECTION of a pairing, keyed on the ordered token pair. A decline is read
 * in either direction: it covers the pairing symmetrically, so neither side is offered
 * the other again. Nothing here is a durable record of two people who don't want each
 * other — rows die with their requests ([dropClosed]), so someone who declines the same
 * person in a later interest will be offered them again.
 *
 * No name is ever stored here. The handles are looked up live when a give-up completes.
 */
class NameGiveUpRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    fun record(refToken: String, peerRefToken: String, userId: Long, stance: Stance): Unit = transaction(db) {
        NameGiveUps.upsert {
            it[NameGiveUps.refToken] = refToken
            it[NameGiveUps.peerRefToken] = peerRefToken
            it[NameGiveUps.userRef] = crypto.ref(userId.toString())
            it[NameGiveUps.stance] = stance.name
            it[decidedAt] = clock.instant()
        }
        Unit
    }

    fun stanceOf(refToken: String, peerRefToken: String): Stance? = transaction(db) {
        NameGiveUps.selectAll()
            .where { (NameGiveUps.refToken eq refToken) and (NameGiveUps.peerRefToken eq peerRefToken) }
            .singleOrNull()
            ?.let { Stance.valueOf(it[NameGiveUps.stance]) }
    }

    /** True when EITHER side declined — a decline covers the pairing symmetrically. */
    fun declined(a: String, b: String): Boolean =
        stanceOf(a, b) == Stance.DECLINED || stanceOf(b, a) == Stance.DECLINED

    fun bothOffered(a: String, b: String): Boolean =
        stanceOf(a, b) == Stance.OFFERED && stanceOf(b, a) == Stance.OFFERED

    fun deleteFor(userId: Long): Unit = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        NameGiveUps.deleteWhere { NameGiveUps.userRef eq userRef }
        Unit
    }

    /**
     * Housekeeping: a row whose own request or whose peer's request is no longer resting
     * has nothing left to consent about. Read-then-delete rather than a correlated
     * subquery — this is a small table on a single-process bot (ADR 0004), and the plain
     * form is the one that is obviously right.
     */
    fun dropClosed(): Int = transaction(db) {
        val live = Requests.selectAll()
            .where { Requests.state eq RequestState.OPEN.name }
            .map { it[Requests.refToken] }
            .toSet()
        val dead = NameGiveUps.selectAll()
            .map { it[NameGiveUps.refToken] to it[NameGiveUps.peerRefToken] }
            .filter { (mine, theirs) -> mine !in live || theirs !in live }
        for ((mine, theirs) in dead) {
            NameGiveUps.deleteWhere {
                (NameGiveUps.refToken eq mine) and (NameGiveUps.peerRefToken eq theirs)
            }
        }
        dead.size
    }
}
```

Drop the `or` import if the final file does not use it.

- [ ] **Step 4: Write `PendingAnnouncementRepository.kt`**

```kotlin
package fxbot

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource

/**
 * One chat still owed an announcement about one interest. It carries the chat REF, not a
 * chat id: this table has no sealed payload to hide an id in, and a ref is a keyed MAC
 * that cannot be reversed. The flush gets the real chat id from the interest's own
 * showing rows, whose payloads do carry it, and pairs the two with
 * [PendingAnnouncementRepository.isFor].
 */
data class PendingAnnouncement(val chatRef: String, val interestToken: String, val createdAt: Instant)

/**
 * What to announce, never how. The text is re-rendered from live state at flush time, so
 * a restart inside the batching window cannot leave a showing resting in a chat that was
 * never told — and cannot replay a composed message whose requests have since closed.
 */
class PendingAnnouncementRepository(
    ds: DataSource,
    private val crypto: Crypto,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    fun add(chatId: Long, interestToken: String, userId: Long): Unit = transaction(db) {
        PendingAnnouncements.upsert {
            it[chatRef] = crypto.ref(chatId.toString())
            it[PendingAnnouncements.interestToken] = interestToken
            it[userRef] = crypto.ref(userId.toString())
            it[createdAt] = clock.instant()
        }
        Unit
    }

    fun all(): List<PendingAnnouncement> = transaction(db) {
        PendingAnnouncements.selectAll().map { hydrate(it) }
    }

    fun allFor(userId: Long): List<PendingAnnouncement> = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        PendingAnnouncements.selectAll().where { PendingAnnouncements.userRef eq userRef }.map { hydrate(it) }
    }

    /** Whether [row] belongs to [chatId], without ever reversing the ref. */
    fun isFor(row: PendingAnnouncement, chatId: Long): Boolean = row.chatRef == crypto.ref(chatId.toString())

    fun remove(chatRef: String, interestToken: String): Unit = transaction(db) {
        PendingAnnouncements.deleteWhere {
            (PendingAnnouncements.chatRef eq chatRef) and (PendingAnnouncements.interestToken eq interestToken)
        }
        Unit
    }

    fun deleteFor(userId: Long): Unit = transaction(db) {
        val userRef = crypto.ref(userId.toString())
        PendingAnnouncements.deleteWhere { PendingAnnouncements.userRef eq userRef }
        Unit
    }

    /** An hour-late "someone just stated this" is noise, and the showing works silently regardless. */
    fun dropOlderThan(cutoff: Instant): Int = transaction(db) {
        PendingAnnouncements.deleteWhere { createdAt less cutoff }
    }

    /** An announcement whose showings have all closed has nothing left to say. */
    fun dropClosed(): Int = transaction(db) {
        val live = Requests.selectAll()
            .where { Requests.state eq RequestState.OPEN.name }
            .mapNotNull { it[Requests.interestToken] }
            .toSet()
        val dead = PendingAnnouncements.selectAll()
            .map { it[PendingAnnouncements.chatRef] to it[PendingAnnouncements.interestToken] }
            .filter { (_, token) -> token !in live }
        for ((chatRef, token) in dead) {
            PendingAnnouncements.deleteWhere {
                (PendingAnnouncements.chatRef eq chatRef) and (PendingAnnouncements.interestToken eq token)
            }
        }
        dead.size
    }

    private fun hydrate(row: org.jetbrains.exposed.v1.core.ResultRow) = PendingAnnouncement(
        chatRef = row[PendingAnnouncements.chatRef],
        interestToken = row[PendingAnnouncements.interestToken],
        createdAt = row[PendingAnnouncements.createdAt],
    )
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'fxbot.NameGiveUpRepositoryTest' --tests 'fxbot.PendingAnnouncementRepositoryTest'` then `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/fxbot/NameGiveUpRepository.kt src/main/kotlin/fxbot/PendingAnnouncementRepository.kt src/test/kotlin/fxbot/NameGiveUpRepositoryTest.kt src/test/kotlin/fxbot/PendingAnnouncementRepositoryTest.kt
git commit -m "feat: store give-up consent and pending announcements"
```

---

### Task 7: The message log remembers what it said

**Files:**
- Modify: `src/main/kotlin/fxbot/MessageLogRepository.kt`
- Modify: `src/main/kotlin/fxbot/Commands.kt` (pass the text and buttons it already has)
- Test: `src/test/kotlin/fxbot/MessageLogRepositoryTest.kt`

**Interfaces:**
- Consumes: `Button` from `Render.kt`.
- Produces:
  ```kotlin
  data class LoggedMessage(val chatId: Long, val messageId: Long, val text: String?, val buttons: List<Button>, val refTokens: List<String>)
  // MessageLogRepository:
  fun record(chatId, messageId, refTokens, userIds, text: String? = null, buttons: List<Button> = emptyList())
  fun logged(chatId: Long, messageId: Long): LoggedMessage?
  ```
  `text` and `buttons` are trailing with defaults, so `Commands.kt`'s existing call and `ForgetCommandTest`'s calls compile untouched. No SQL: both ride in the sealed `sent_message.payload`, nullable so rows written before V3 fall back to the current strip-only path.

- [ ] **Step 1: Write the failing test**

Append to `MessageLogRepositoryTest.kt`:

```kotlin
    "a message's own text and keyboard are remembered, so a later edit can rebuild them" {
        val l = log("remembers")
        val buttons = listOf(Button("✅ Done with ann", "done?a=tokA&b=tokB"), Button("✖️ Cancel my request", "cancel?t=tokA"))
        l.record(-100L, 10L, listOf("tokA", "tokB"), listOf(1L, 2L), "2 people match: ...", buttons)
        val m = l.logged(-100L, 10L)!!
        m.chatId shouldBe -100L
        m.text shouldBe "2 people match: ..."
        m.buttons shouldBe buttons
        m.refTokens.toSet() shouldBe setOf("tokA", "tokB")
    }
    "a message recorded without its text reads back as having none, not as empty" {
        val l = log("notext")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L))
        val m = l.logged(-100L, 10L)!!
        m.text shouldBe null
        m.buttons shouldBe emptyList()
    }
    "an unknown message is not logged" {
        log("unknownmsg").logged(-100L, 99L) shouldBe null
    }
    "re-recording the same message replaces its text and keyboard" {
        val l = log("rerecord")
        l.record(-100L, 10L, listOf("tokA"), listOf(1L), "first", listOf(Button("a", "cancel?t=tokA")))
        l.record(-100L, 10L, listOf("tokA"), listOf(1L), "second", emptyList())
        val m = l.logged(-100L, 10L)!!
        m.text shouldBe "second"
        m.buttons shouldBe emptyList()
    }
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'fxbot.MessageLogRepositoryTest'`
Expected: FAIL — `Unresolved reference: logged`, and `record` takes no text.

- [ ] **Step 3: Extend the payload and the API**

In `MessageLogRepository.kt`:

```kotlin
/** What one message said and offered. Both are nullable/empty for rows written before V3. */
data class LoggedMessage(
    val chatId: Long,
    val messageId: Long,
    val text: String?,
    val buttons: List<Button>,
    val refTokens: List<String>,
)

@Serializable
private data class StoredButton(val label: String, val data: String)

@Serializable
private data class MessagePayload(
    val chatId: Long,
    // Added in V3, inside the sealed payload rather than a new column. Nullable so a row
    // written before V3 falls back to the strip-only path instead of being rewritten to
    // an empty message.
    val text: String? = null,
    val buttons: List<StoredButton> = emptyList(),
)
```

Change `record` and add `logged`:

```kotlin
    /**
     * Records one sent message, every ref token (and person) it names, and — since V3 —
     * what it actually said and offered, so closing ONE of the requests a batched message
     * carries can rewrite that message instead of stripping the whole keyboard.
     */
    fun record(
        chatId: Long,
        messageId: Long,
        refTokens: List<String>,
        userIds: List<Long>,
        text: String? = null,
        buttons: List<Button> = emptyList(),
    ) {
        require(refTokens.size == userIds.size) { "refTokens and userIds must pair up 1:1" }
        transaction(db) {
            val chatRef = crypto.ref(chatId.toString())
            val aad = "$chatRef:$messageId"
            val body = MessagePayload(chatId, text, buttons.map { StoredButton(it.label, it.data) })
            SentMessages.upsert {
                it[SentMessages.chatRef] = chatRef
                it[SentMessages.messageId] = messageId
                it[sentAt] = clock.instant()
                it[payload] = crypto.seal(json.encodeToString(body), aad)
            }
            // Re-recording a message replaces what it names, rather than accumulating
            // duplicate refs alongside the old ones.
            SentMessageRefs.deleteWhere {
                (SentMessageRefs.chatRef eq chatRef) and (SentMessageRefs.messageId eq messageId)
            }
            SentMessageRefs.batchInsert(refTokens.indices, shouldReturnGeneratedValues = false) { i ->
                this[SentMessageRefs.chatRef] = chatRef
                this[SentMessageRefs.messageId] = messageId
                this[SentMessageRefs.refToken] = refTokens[i]
                this[SentMessageRefs.userRef] = crypto.ref(userIds[i].toString())
            }
        }
    }

    /** Everything needed to rewrite one message: its stored text, its keyboard, and what it names. */
    fun logged(chatId: Long, messageId: Long): LoggedMessage? = transaction(db) {
        val chatRef = crypto.ref(chatId.toString())
        val row = SentMessages.selectAll()
            .where { (SentMessages.chatRef eq chatRef) and (SentMessages.messageId eq messageId) }
            .singleOrNull() ?: return@transaction null
        val p = json.decodeFromString<MessagePayload>(crypto.open(row[SentMessages.payload], "$chatRef:$messageId"))
        val tokens = SentMessageRefs.selectAll()
            .where { (SentMessageRefs.chatRef eq chatRef) and (SentMessageRefs.messageId eq messageId) }
            .map { it[SentMessageRefs.refToken] }
            .distinct()
        LoggedMessage(p.chatId, messageId, p.text, p.buttons.map { Button(it.label, it.data) }, tokens)
    }
```

`rewriteChatRef` reseals a `MessagePayload(newChatId)` and would silently drop the text and buttons. Fix it to reseal what was there:

```kotlin
        for (messageId in messageIds) {
            val old = SentMessages.selectAll()
                .where { (SentMessages.chatRef eq oldRef) and (SentMessages.messageId eq messageId) }
                .single()
            val decoded = json.decodeFromString<MessagePayload>(
                crypto.open(old[SentMessages.payload], "$oldRef:$messageId"),
            )
            val resealed = crypto.seal(
                json.encodeToString(decoded.copy(chatId = newChatId)),
                "$newRef:$messageId",
            )
            ...
        }
```

- [ ] **Step 4: Record what `/sell` and `/buy` already know**

In `Commands.kt`'s `handlePost`, the text and buttons are already in scope. Pass them:

```kotlin
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    chat.id,
                    id,
                    listOf(result.request.refToken) + result.found.map { it.request.refToken },
                    listOf(result.request.userId) + result.found.map { it.request.userId },
                    text,
                    buttons,
                )
            }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: PASS, including the existing `ChatMigrationTest`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/fxbot/MessageLogRepository.kt src/main/kotlin/fxbot/Commands.kt src/test/kotlin/fxbot/MessageLogRepositoryTest.kt
git commit -m "feat: remember what each message said and offered"
```

---

### Task 8: Stating an interest privately

**Files:**
- Create: `src/main/kotlin/fxbot/InterestService.kt`
- Test: `src/test/kotlin/fxbot/InterestServiceTest.kt`

**Interfaces:**
- Consumes: `RequestRepository` (Task 3), `ChatSettingsRepository.allChats` (Task 5), `PersonSettingsRepository` (Task 4), `NameGiveUpRepository` (Task 6), `PendingAnnouncementRepository` (Task 6), `findCounterparties` with `peerTolerancePct`/`suppressed` (Task 2), `RateService`, `RateClient`.
- Produces:
  ```kotlin
  fun interface MembershipProbe { suspend fun isMember(chatId: Long, userId: Long): Boolean }

  const val MAX_RESTING_INTERESTS = 5
  const val NO_NAMES_TIF_DAYS = 7

  fun canonicalPair(a: String, b: String): CurrencyPair

  data class CounterpartyAppeared(val userId: Long, val refToken: String)
  data class InterestStanding(val interest: Request, val chatIds: List<Long>)

  sealed interface InterestResult {
      data class Stated(
          val interest: Request,
          val showings: List<Request>,
          val found: List<Counterparty>,
          val status: RateStatus,
          val appeared: List<CounterpartyAppeared>,
      ) : InterestResult
      data class Rejected(val reason: String) : InterestResult
  }

  class InterestService(requests, chats, people, rates, rateClient, giveUps, pending, membership) {
      suspend fun state(userId: Long, username: String?, verb: Verb, rawAmount: String, rawAmountCurrency: String, rawOtherCurrency: String): InterestResult
      fun counterparties(subject: Request): List<Counterparty>
      fun standings(userId: Long): List<InterestStanding>
  }
  ```
  `MembershipProbe` is a `fun interface` so tests supply a lambda and the Telegram layer (Task 13) supplies `getChatMember`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/fxbot/InterestServiceTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val T0 = Instant.parse("2026-09-06T12:00:00Z")
private val EURRUB = CurrencyPair("EUR", "RUB")

/** Everything an InterestService needs, plus the repositories a test wants to look at. */
private class Fixture(
    name: String,
    feedBody: String? = """{"result":"success","base_code":"EUR","rates":{"RUB":99.98}}""",
    val membership: MembershipProbe = MembershipProbe { _, _ -> true },
) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, clock)
    val chats = ChatSettingsRepository(ds, crypto, clock)
    val people = PersonSettingsRepository(ds, crypto, clock)
    val giveUps = NameGiveUpRepository(ds, crypto, clock)
    val pending = PendingAnnouncementRepository(ds, crypto, clock)
    val rateRepo = RateRepository(ds)
    var feedCalls = 0
    val client = RateClient(HttpClient(MockEngine {
        feedCalls++
        if (feedBody == null) respondError(HttpStatusCode.ServiceUnavailable)
        else respond(feedBody, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }))
    val rates = RateService(client, rateRepo, clock)
    val svc = InterestService(requests, chats, people, rates, client, giveUps, pending, membership)

    fun withRate() = apply { rateRepo.put("EUR", "RUB", BigDecimal("99.98"), T0) }
    fun chat(id: Long, pair: CurrencyPair = EURRUB, tolerance: Int = 20, tif: Int = 7, fanOut: Boolean = true) =
        apply { chats.save(ChatSettings(id, pair, tolerance, tif, fanOut)) }
}

class InterestServiceTest : StringSpec({
    "the pair is canonical whichever way round it is stated" {
        canonicalPair("RUB", "EUR") shouldBe EURRUB
        canonicalPair("EUR", "RUB") shouldBe EURRUB
    }

    "the two ways of saying the same thing land on the same pair and the same side" {
        // "sell 10 EUR for RUB" and "buy 10 RUB for EUR" both hand over EUR to receive
        // RUB. The canonical pair is what puts them in the same space; sideFor then
        // agrees they are the SAME side, so they are not counterparties — and a third
        // person on the opposite side meets either identically. See the plan's
        // interpretation note 1.
        val f = Fixture("sameside").withRate()
        val a = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        val b = f.svc.state(2L, "ann", Verb.BUY, "10", "RUB", "EUR")
        a.shouldBeInstanceOf<InterestResult.Stated>()
        b.shouldBeInstanceOf<InterestResult.Stated>()
        a.interest.pair shouldBe EURRUB
        b.interest.pair shouldBe EURRUB
        b.interest.side shouldBe a.interest.side
        b.found.shouldBeEmpty()

        val c = f.svc.state(3L, "cat", Verb.BUY, "10", "EUR", "RUB")
        c.shouldBeInstanceOf<InterestResult.Stated>()
        c.found.map { it.request.userId }.toSet() shouldBe setOf(1L, 2L)
    }

    "an interest rests on a no-names basis and in every fitting chat" {
        val f = Fixture("fanout").withRate()
            .chat(-100L, EURRUB)
            .chat(-200L, CurrencyPair("RUB", "EUR"))          // the same two currencies, other way round
            .chat(-300L, CurrencyPair("USD", "GBP"))          // a different pair
            .chat(-400L, EURRUB, fanOut = false)              // fan-out turned off
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.interest.chatId shouldBe NO_NAMES_CHAT_ID
        r.showings.map { it.chatId }.toSet() shouldBe setOf(-100L, -200L)
        r.showings.map { it.interestToken }.toSet() shouldBe setOf(r.interest.interestToken)
    }

    "a showing takes its own chat's pair orientation, so its side is right there" {
        val f = Fixture("orientation").withRate().chat(-200L, CurrencyPair("RUB", "EUR"))
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        val showing = r.showings.single()
        showing.pair shouldBe CurrencyPair("RUB", "EUR")
        // Handing over EUR is handing over the QUOTE of RUB/EUR, so in that chat this is a Bid.
        showing.side shouldBe Side.BID
        showing.statedCurrency shouldBe "EUR"
        showing.statedAmount shouldBe BigDecimal("10")
    }

    "a chat the person is not in is dropped" {
        val f = Fixture("notmember", membership = MembershipProbe { chatId, _ -> chatId == -100L })
            .withRate().chat(-100L).chat(-200L)
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.showings.map { it.chatId } shouldBe listOf(-100L)
    }

    "every showing rests immediately, and every chat is owed an announcement" {
        val f = Fixture("restfirst").withRate().chat(-100L).chat(-200L)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.requests.resting(-100L) shouldHaveSize 1
        f.requests.resting(-200L) shouldHaveSize 1
        f.pending.allFor(1L) shouldHaveSize 2
    }

    "the two surfaces never meet" {
        val f = Fixture("surfaces").withRate().chat(-100L)
        // Someone who has only ever typed in a chat is invisible to the no-names side.
        f.requests.create(-100L, 9L, "chatonly", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7)
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.found.shouldBeEmpty()
    }

    "each side of a no-names pairing is judged at its own tolerance" {
        val f = Fixture("owntolerance").withRate()
        f.people.save(PersonSettings(1L, 50))
        f.svc.state(1L, "bob", Verb.SELL, "4", "EUR", "RUB")
        val r = f.svc.state(2L, "ann", Verb.BUY, "2", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.found shouldHaveSize 1 // ann has no residual; bob's 50% leftover is within his own 50

        val g = Fixture("owntolerance2").withRate()
        g.people.save(PersonSettings(1L, 20))
        g.svc.state(1L, "bob", Verb.SELL, "4", "EUR", "RUB")
        val s = g.svc.state(2L, "ann", Verb.BUY, "2", "EUR", "RUB")
        s.shouldBeInstanceOf<InterestResult.Stated>()
        s.found.shouldBeEmpty() // bob's own 20 excludes it, so they are not counterparties
    }

    "a pairing is suppressed while a live showing already pairs the two in some chat" {
        val f = Fixture("suppressed").withRate().chat(-100L, EURRUB, tolerance = 20)
        f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        val r = f.svc.state(2L, "ann", Verb.BUY, "1000", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.found.shouldBeEmpty() // they can already see each other by name in -100

        // Close bob's showing in that chat only; the anonymous route comes back.
        val bobsShowing = f.requests.resting(-100L).single { it.userId == 1L }
        f.requests.transition(bobsShowing.refToken, RequestState.OPEN, RequestState.EXPIRED)
        f.svc.counterparties(r.interest) shouldHaveSize 1
    }

    "sharing a chat is not enough — the showings there must actually pair" {
        // Sizes that meet on a no-names basis at 100 but not at the chat's 5.
        val f = Fixture("sharechatnopair").withRate().chat(-100L, EURRUB, tolerance = 5)
        f.people.save(PersonSettings(1L, 100))
        f.people.save(PersonSettings(2L, 100))
        f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        val r = f.svc.state(2L, "ann", Verb.BUY, "500", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.found shouldHaveSize 1
    }

    "a declined pairing is suppressed for both" {
        val f = Fixture("declined").withRate()
        val a = f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB") as InterestResult.Stated
        val b = f.svc.state(2L, "ann", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        b.found shouldHaveSize 1
        f.giveUps.record(b.interest.refToken, a.interest.refToken, 2L, Stance.DECLINED)
        f.svc.counterparties(b.interest).shouldBeEmpty()
        f.svc.counterparties(a.interest).shouldBeEmpty()
    }

    "everyone who gains a counterparty is named so they can be pinged" {
        val f = Fixture("appeared").withRate()
        f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        val r = f.svc.state(2L, "ann", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        r.appeared.map { it.userId } shouldBe listOf(1L)
        r.appeared.single().refToken shouldBe f.requests.resting(NO_NAMES_CHAT_ID).single { it.userId == 1L }.refToken
    }

    "a sixth resting interest is refused, naming the cap and how to make room" {
        val f = Fixture("cap").withRate()
        repeat(5) { f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") }
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Rejected>()
        r.reason shouldContain "5"
        r.reason shouldContain "/cancel"
        f.requests.countOpenInterests(1L) shouldBe 5
    }

    "the cap counts only what is resting" {
        val f = Fixture("capfree").withRate()
        repeat(5) { f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") }
        val token = f.requests.resting(NO_NAMES_CHAT_ID).first { it.userId == 1L }.interestToken!!
        f.requests.closeInterest(token, RequestState.CANCELLED)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
    }

    "a pair no chat uses is priced once, live, before it is accepted" {
        val f = Fixture("pricelive") // no cached rate, no chats
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        f.feedCalls shouldBe 1
    }
    "a pair the feed answers without is refused" {
        val f = Fixture("pricemissing", feedBody = """{"result":"success","base_code":"EUR","rates":{"USD":1.1}}""")
        val r = f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Rejected>()
        r.reason shouldContain "EUR/RUB"
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
    }
    "a pair is accepted when the feed cannot be reached at all" {
        // Refusing a legitimate pair during an outage is the harder failure to explain.
        val f = Fixture("priceoutage", feedBody = null)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
    }
    "a pair some chat already uses costs no call at all" {
        val f = Fixture("pricechat", feedBody = null).chat(-100L, EURRUB)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
        f.feedCalls shouldBe 0
    }
    "a cached rate costs no call either" {
        val f = Fixture("pricecached", feedBody = null).withRate()
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB").shouldBeInstanceOf<InterestResult.Stated>()
        f.feedCalls shouldBe 0
    }

    "an unreadable amount, an unknown code, and the same code twice are all refused" {
        val f = Fixture("badinput").withRate()
        (f.svc.state(1L, "bob", Verb.SELL, "lots", "EUR", "RUB") as InterestResult.Rejected).reason shouldContain "amount"
        (f.svc.state(1L, "bob", Verb.SELL, "10", "XYZ", "RUB") as InterestResult.Rejected).reason shouldContain "XYZ"
        (f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "EUR") as InterestResult.Rejected).reason shouldContain "two different"
    }

    "status lists each interest once, with where it still rests" {
        val f = Fixture("standings").withRate().chat(-100L).chat(-200L)
        f.svc.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        val lapsed = f.requests.resting(-200L).single()
        f.requests.transition(lapsed.refToken, RequestState.OPEN, RequestState.EXPIRED)
        val standings = f.svc.standings(1L)
        standings shouldHaveSize 1
        standings.single().chatIds shouldContainExactlyInAnyOrder listOf(-100L)
    }
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.InterestServiceTest'`
Expected: FAIL — `Unresolved reference: InterestService`, `canonicalPair`, `MembershipProbe`.

- [ ] **Step 3: Write `InterestService.kt`**

```kotlin
package fxbot

import java.time.Instant

/**
 * Whether a person is in a chat, asked at fan-out time and never written down. The
 * Telegram layer answers with `getChatMember`; a test answers with a lambda.
 */
fun interface MembershipProbe {
    suspend fun isMember(chatId: Long, userId: Long): Boolean
}

/**
 * The cap on interests one person may have resting on a no-names basis. It exists to
 * bound fan-out, so requests typed in a chat are not counted against it. The sixth is
 * REFUSED rather than the oldest being dropped: silently cancelling something a person
 * deliberately stated is the one outcome they cannot undo by knowing the rule.
 */
const val MAX_RESTING_INTERESTS = 5

/** No-names working has no chat behind it to set a time in force, so it uses the default. */
const val NO_NAMES_TIF_DAYS = 7

/**
 * The pair a no-names request carries: the two codes sorted, so `EUR/RUB` whichever way
 * round it was stated. `Matcher` compares pairs by equality, and this is what puts the
 * two orientations of one pair in the same space.
 */
fun canonicalPair(a: String, b: String): CurrencyPair =
    if (a <= b) CurrencyPair(a, b) else CurrencyPair(b, a)

/** Someone who has just gained a counterparty and should be told, by their own request. */
data class CounterpartyAppeared(val userId: Long, val refToken: String)

/** One interest and the chats where it still rests. Chats whose showing has lapsed are simply absent. */
data class InterestStanding(val interest: Request, val chatIds: List<Long>)

sealed interface InterestResult {
    data class Stated(
        val interest: Request,
        val showings: List<Request>,
        val found: List<Counterparty>,
        val status: RateStatus,
        val appeared: List<CounterpartyAppeared>,
    ) : InterestResult

    data class Rejected(val reason: String) : InterestResult
}

/**
 * Everything about stating an interest to the bot privately, kept out of Telegram so it
 * is testable without a bot. The Telegram layer only extracts arguments and sends what
 * this returns.
 */
class InterestService(
    private val requests: RequestRepository,
    private val chats: ChatSettingsRepository,
    private val people: PersonSettingsRepository,
    private val rates: RateService,
    private val rateClient: RateClient,
    private val giveUps: NameGiveUpRepository,
    private val pending: PendingAnnouncementRepository,
    private val membership: MembershipProbe,
) {
    /**
     * [rawAmountCurrency] is the currency of the amount — what a `/sell` hands over and
     * what a `/buy` receives. [rawOtherCurrency] is the other leg, the one after `for`.
     *
     * Everything created here rests immediately; only the announcement waits for the
     * batch, so a counterparty is never missed because a message had not gone out yet.
     */
    suspend fun state(
        userId: Long,
        username: String?,
        verb: Verb,
        rawAmount: String,
        rawAmountCurrency: String,
        rawOtherCurrency: String,
    ): InterestResult {
        val amount = parseAmount(rawAmount)
            ?: return InterestResult.Rejected("I couldn't read \"$rawAmount\" as an amount. Try: /sell 10 EUR for RUB")
        val mine = parseCurrency(rawAmountCurrency)
            ?: return InterestResult.Rejected("\"$rawAmountCurrency\" isn't a currency code I know. Try: /sell 10 EUR for RUB")
        val other = parseCurrency(rawOtherCurrency)
            ?: return InterestResult.Rejected("\"$rawOtherCurrency\" isn't a currency code I know. Try: /sell 10 EUR for RUB")
        if (mine == other) return InterestResult.Rejected("That needs two different currencies, like /sell 10 EUR for RUB")

        if (requests.countOpenInterests(userId) >= MAX_RESTING_INTERESTS) {
            return InterestResult.Rejected(
                "You already have $MAX_RESTING_INTERESTS interests waiting with me, which is as many as I'll hold. " +
                    "Use /cancel to withdraw one and I'll take this instead.",
            )
        }

        val pair = canonicalPair(mine, other)
        if (!canBePriced(pair)) {
            return InterestResult.Rejected(
                "I can't get a rate for $pair, so I couldn't compare amounts across the two.",
            )
        }

        val interestToken = newRefToken()
        val interest = requests.create(
            NO_NAMES_CHAT_ID, userId, username, sideFor(verb, mine, pair), mine, amount, pair,
            NO_NAMES_TIF_DAYS, interestToken,
        )
        val showings = fanOutChats(userId, pair).map { chat ->
            // The chat's own orientation, so the side reads correctly to everyone there.
            requests.create(
                chat.chatId, userId, username, sideFor(verb, mine, chat.pair), mine, amount, chat.pair,
                chat.tifDays, interestToken,
            ).also { pending.add(chat.chatId, interestToken, userId) }
        }

        val found = counterparties(interest)
        return InterestResult.Stated(
            interest = interest,
            showings = showings,
            found = found,
            status = rates.status(pair),
            // Symmetric: each side's residual was already judged against its own number,
            // so everyone this interest found has just gained it in return.
            appeared = found.map { CounterpartyAppeared(it.request.userId, it.request.refToken) },
        )
    }

    /**
     * The counterparties resting on a no-names basis for [subject]. Each side is judged at
     * its own tolerance, and a pairing is hidden while the two can already see each other
     * by name, or while one of them has declined. Nothing about the suppression is stored:
     * it is re-evaluated here every time.
     */
    fun counterparties(subject: Request): List<Counterparty> = findCounterparties(
        subject = subject,
        resting = requests.resting(NO_NAMES_CHAT_ID),
        rate = rates.status(subject.pair).rate,
        tolerancePct = people.get(subject.userId).tolerancePct,
        peerTolerancePct = { people.get(it.userId).tolerancePct },
        suppressed = { a, b -> giveUps.declined(a.refToken, b.refToken) || alreadyPairedInAChat(a, b) },
    )

    /** Each interest once, with the chats where a showing still rests — where someone can still find you. */
    fun standings(userId: Long): List<InterestStanding> =
        requests.resting(NO_NAMES_CHAT_ID)
            .filter { it.userId == userId }
            .map { interest ->
                val chatIds = interest.interestToken
                    ?.let { requests.siblings(it) }
                    .orEmpty()
                    .filter { it.state == RequestState.OPEN && it.chatId != NO_NAMES_CHAT_ID }
                    .map { it.chatId }
                InterestStanding(interest, chatIds)
            }

    /**
     * The rate cache first, and only for a pair no chat already uses, one live fetch.
     * A feed that answers WITHOUT the pair is a refusal — that pair can never be priced.
     * A feed that cannot be reached is not: `RateClient` returns null for both, and
     * refusing a legitimate pair during an outage is the harder failure to explain.
     */
    private suspend fun canBePriced(pair: CurrencyPair): Boolean {
        val configured = chats.allPairs()
        val known = configured.any { setOf(it.base, it.quote) == setOf(pair.base, pair.quote) }
        if (known) return true
        if (rates.status(pair).rate != null) return true
        val fetched = rateClient.fetch(pair.base) ?: return true
        return fetched[pair.quote] != null
    }

    private suspend fun fanOutChats(userId: Long, pair: CurrencyPair): List<ChatSettings> =
        chats.allChats()
            .filter { it.fanOut }
            .filter { setOf(it.pair.base, it.pair.quote) == setOf(pair.base, pair.quote) }
            .filter { membership.isMember(it.chatId, userId) }

    /**
     * Whether a live showing already pairs these two people in some chat — in which case
     * the anonymous route adds nothing, because they can see each other by name there.
     * Co-presence is not enough: the two showings must actually be counterparties at that
     * chat's own tolerance, or a pairing that chat would never have suggested would
     * silently block the one this side would.
     */
    private fun alreadyPairedInAChat(a: Request, b: Request): Boolean {
        val mine = a.interestToken?.let { requests.siblings(it) }.orEmpty()
            .filter { it.state == RequestState.OPEN && it.chatId != NO_NAMES_CHAT_ID }
        if (mine.isEmpty()) return false
        val theirs = b.interestToken?.let { requests.siblings(it) }.orEmpty()
            .filter { it.state == RequestState.OPEN && it.chatId != NO_NAMES_CHAT_ID }
            .associateBy { it.chatId }
        return mine.any { showing ->
            val peer = theirs[showing.chatId] ?: return@any false
            val chat = chats.get(showing.chatId)
            findCounterparties(showing, listOf(peer), rates.status(chat.pair).rate, chat.tolerancePct).isNotEmpty()
        }
    }
}
```

Drop the `java.time.Instant` import if the final file does not use it.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests 'fxbot.InterestServiceTest'`
Expected: PASS.

- [ ] **Step 5: Run the whole suite and commit**

Run: `./gradlew test`

```bash
git add src/main/kotlin/fxbot/InterestService.kt src/test/kotlin/fxbot/InterestServiceTest.kt
git commit -m "feat: state an interest privately and fan it out"
```

---

### Task 9: Batching the announcements

**Files:**
- Create: `src/main/kotlin/fxbot/AnnouncementBatcher.kt`
- Modify: `src/main/kotlin/fxbot/Render.kt` (announcement and ping text, and their keyboards)
- Test: `src/test/kotlin/fxbot/AnnouncementBatcherTest.kt`

**Interfaces:**
- Consumes: `InterestService.counterparties` (Task 8), `PendingAnnouncementRepository` (Task 6), `RequestRepository.siblings` (Task 3), `ChatSettingsRepository` (Task 5).
- Produces:
  ```kotlin
  // Render.kt
  data class ShownInterest(val request: Request, val found: List<Counterparty>)
  fun renderAnnouncement(person: String, shown: List<ShownInterest>, status: RateStatus): String
  fun announcementButtons(shown: List<ShownInterest>): List<Button>
  fun renderAppeared(mine: List<ShownInterest>): String
  fun appearedButtons(mine: List<ShownInterest>): List<Button>
  object Cb { ... const val GIVE_UP = "giveup"; const val DECLINE = "decline"
              fun giveUp(mine: String, theirs: String): String
              fun decline(mine: String, theirs: String): String }

  // AnnouncementBatcher.kt
  data class Announcement(val chatId: Long, val text: String, val buttons: List<Button>, val refTokens: List<String>, val userIds: List<Long>)
  data class Ping(val userId: Long, val text: String, val buttons: List<Button>)
  fun interface AnnouncementSink { suspend fun deliver(announcements: List<Announcement>, pings: List<Ping>) }

  class AnnouncementBatcher(requests, chats, pending, interests, sink, scope, clock = Clock.systemUTC(), window = Duration.ofSeconds(60), maxAge = Duration.ofHours(1)) {
      fun enqueueAnnouncement(userId: Long)
      fun enqueueAppeared(appeared: List<CounterpartyAppeared>)
      suspend fun flush(userId: Long)
      suspend fun flushAppeared(userId: Long)
      suspend fun flushAllOnStartup()
  }
  ```

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/fxbot/AnnouncementBatcherTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

private val T0 = Instant.parse("2026-09-06T12:00:00Z")
private val EURRUB = CurrencyPair("EUR", "RUB")

private class BatchFixture(name: String, at: Instant = T0, window: Duration = Duration.ofSeconds(60)) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val clock: Clock = Clock.fixed(at, ZoneOffset.UTC)
    val requests = RequestRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
    val chats = ChatSettingsRepository(ds, crypto, clock)
    val people = PersonSettingsRepository(ds, crypto, clock)
    val giveUps = NameGiveUpRepository(ds, crypto, clock)
    val pending = PendingAnnouncementRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
    val rateRepo = RateRepository(ds).also { it.put("EUR", "RUB", BigDecimal("99.98"), T0) }
    val client = RateClient(HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) }))
    val rates = RateService(client, rateRepo, clock)
    val interests = InterestService(requests, chats, people, rates, client, giveUps, pending, MembershipProbe { _, _ -> true })
    val announcements = mutableListOf<Announcement>()
    val pings = mutableListOf<Ping>()
    val batcher = AnnouncementBatcher(
        requests, chats, pending, interests,
        AnnouncementSink { a, p -> announcements += a; pings += p },
        CoroutineScope(Dispatchers.Default), clock, window,
    )
    fun chat(id: Long) = apply { chats.save(ChatSettings(id, EURRUB, 20, 7)) }
}

class AnnouncementBatcherTest : StringSpec({
    "everything stated inside one window is one message per chat" {
        val f = BatchFixture("onepermessage").chat(-100L).chat(-200L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.interests.state(1L, "bob", Verb.SELL, "20", "EUR", "RUB")
        f.batcher.flush(1L)
        f.announcements shouldHaveSize 2
        f.announcements.map { it.chatId }.toSet() shouldBe setOf(-100L, -200L)
        // Each message carries every interest it covers, and every ref token it names.
        f.announcements.first().text shouldContain "10"
        f.announcements.first().text shouldContain "20"
        f.announcements.first().refTokens shouldHaveSize 2
    }

    "the message says the bot is showing these on someone's behalf" {
        val f = BatchFixture("onbehalf").chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.flush(1L)
        f.announcements.single().text shouldContain "on @bob's behalf"
    }

    "a flush empties the queue, so a second flush says nothing" {
        val f = BatchFixture("flushempties").chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.flush(1L)
        f.announcements.clear()
        f.batcher.flush(1L)
        f.announcements.shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
    }

    "the window is a tumbling one: a second statement joins the batch without extending it" {
        val f = BatchFixture("tumbling", window = Duration.ofMillis(120)).chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.enqueueAnnouncement(1L)
        delay(60)
        f.interests.state(1L, "bob", Verb.SELL, "20", "EUR", "RUB")
        f.batcher.enqueueAnnouncement(1L) // must NOT push the deadline out
        delay(150)
        f.announcements shouldHaveSize 1
        f.announcements.single().refTokens shouldHaveSize 2
    }

    "one recipient gets one ping however many counterparties appeared at once" {
        val f = BatchFixture("onepingeach")
        val mine = f.interests.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB") as InterestResult.Stated
        val a = f.interests.state(2L, "ann", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        val c = f.interests.state(3L, "cat", Verb.BUY, "1000", "EUR", "RUB") as InterestResult.Stated
        f.batcher.enqueueAppeared(a.appeared)
        f.batcher.enqueueAppeared(c.appeared)
        f.batcher.flushAppeared(1L)
        f.pings shouldHaveSize 1
        f.pings.single().userId shouldBe 1L
        // Side and stated amount only — never a handle, a name, a user id, or a chat.
        f.pings.single().text shouldContain "buy 1,000 EUR"
        f.pings.single().text.contains("ann") shouldBe false
        f.pings.single().text.contains("cat") shouldBe false
        mine.interest.userId shouldBe 1L
    }

    "an announcement is re-rendered from live state, not replayed" {
        val f = BatchFixture("rerender").chat(-100L)
        val r = f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") as InterestResult.Stated
        // A counterparty turns up in that chat between the statement and the flush.
        f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("10"), EURRUB, 7)
        f.batcher.flush(1L)
        f.announcements.single().text shouldContain "@ann"
        f.announcements.single().refTokens shouldHaveSize 2
        r.found.shouldBeEmpty() // nothing was known at statement time
    }

    "an announcement whose showings have all closed is dropped" {
        val f = BatchFixture("dropclosed").chat(-100L)
        val r = f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB") as InterestResult.Stated
        f.requests.closeInterest(r.interest.interestToken!!, RequestState.CANCELLED)
        f.batcher.flush(1L)
        f.announcements.shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
    }

    "an announcement older than an hour is dropped, and the showing keeps working silently" {
        val f = BatchFixture("dropstale", at = T0.plusSeconds(3_601)).chat(-100L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.batcher.flushAllOnStartup()
        f.announcements.shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
        f.requests.resting(-100L) shouldHaveSize 1
    }

    "a restart inside the window still tells every chat" {
        val f = BatchFixture("restart").chat(-100L).chat(-200L)
        f.interests.state(1L, "bob", Verb.SELL, "10", "EUR", "RUB")
        f.interests.state(2L, "ann", Verb.BUY, "10", "EUR", "RUB")
        f.batcher.flushAllOnStartup()
        f.announcements.map { it.chatId } shouldHaveSize 4 // two people, two chats each
        f.pending.all().shouldBeEmpty()
    }
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.AnnouncementBatcherTest'`
Expected: FAIL — `Unresolved reference: AnnouncementBatcher`, `Announcement`, `Ping`, `AnnouncementSink`.

- [ ] **Step 3: Add the rendering**

Append to `Render.kt`, and extend `Cb`:

```kotlin
object Cb {
    const val DONE = "done"
    const val CANCEL = "cancel"
    const val REOPEN = "reopen"
    const val GIVE_UP = "giveup"
    const val DECLINE = "decline"
    const val RESTATE = "restate"

    fun done(mine: String, theirs: String) = "$DONE?a=$mine&b=$theirs"
    fun cancel(token: String) = "$CANCEL?t=$token"
    fun reopen(token: String) = "$REOPEN?t=$token"
    fun giveUp(mine: String, theirs: String) = "$GIVE_UP?a=$mine&b=$theirs"
    fun decline(mine: String, theirs: String) = "$DECLINE?a=$mine&b=$theirs"
    fun restate(mine: String, theirs: String) = "$RESTATE?a=$mine&b=$theirs"
}
```

Every one of these stays inside Telegram's 64-byte `callback_data` limit: the longest prefix is `restate?a=` (10 bytes) plus a 22-byte token, `&b=` (3) and a second 22-byte token — 57 bytes.

```kotlin
/** One showing (or one no-names request) as it is about to be announced, with what it found. */
data class ShownInterest(val request: Request, val found: List<Counterparty>)

/**
 * A message nobody typed has to explain itself, so it says whose interest it is showing.
 * [person] is already a `mention(...)`, so send this with HTML parse mode.
 */
fun renderAnnouncement(person: String, shown: List<ShownInterest>, status: RateStatus): String {
    val text = StringBuilder("I'm showing this on $person's behalf:")
    for (s in shown) {
        text.append("\n• ").append(s.request.shortId).append(' ').append(describe(s.request))
        for (c in s.found) {
            val who = mention(c.request.username, c.request.userId, c.request.username ?: "this person")
            text.append("\n   ↳ ").append(who).append(" — ").append(describe(c.request))
        }
    }
    if (shown.any { it.found.isNotEmpty() }) text.append("\nAgree the rate between yourselves, then press Done.")
    when (status) {
        is RateStatus.Stale -> text.append("\n(rate from ").append(DAY_MONTH.format(status.fetchedAt)).append(", may be out of date)")
        RateStatus.Unavailable -> text.append("\n(I can't check rates right now, so I can only match amounts in the same currency)")
        is RateStatus.Fresh -> Unit
    }
    return text.toString()
}

fun announcementButtons(shown: List<ShownInterest>): List<Button> =
    shown.flatMap { s ->
        s.found.map { c -> Button("✅ Done with ${c.request.username ?: "them"}", Cb.done(s.request.refToken, c.request.refToken)) } +
            Button("✖️ Cancel ${s.request.shortId}", Cb.cancel(s.request.refToken))
    }

/**
 * The no-names ping: side and stated amount, nothing else. No handle, no name, no user
 * id, no chat, no hint of which chats are shared (ADR 0007).
 */
fun renderAppeared(mine: List<ShownInterest>): String {
    val text = StringBuilder("Someone matches an interest you have with me:")
    for (s in mine) {
        text.append("\n• your ").append(describe(s.request)).append(':')
        for (c in s.found) text.append("\n   ↳ someone wants to ").append(describe(c.request))
    }
    text.append("\nI haven't told them who you are. Offer to pass your name and I'll ask them the same.")
    return text.toString()
}

fun appearedButtons(mine: List<ShownInterest>): List<Button> =
    mine.flatMap { s ->
        s.found.flatMap { c ->
            listOf(
                Button("🤝 Pass my name (${describe(c.request)})", Cb.giveUp(s.request.refToken, c.request.refToken)),
                Button("🚫 Not this one", Cb.decline(s.request.refToken, c.request.refToken)),
            )
        }
    }
```

- [ ] **Step 4: Write `AnnouncementBatcher.kt`**

```kotlin
package fxbot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** One message the bot owes one chat. Composed at flush time from live state. */
data class Announcement(
    val chatId: Long,
    val text: String,
    val buttons: List<Button>,
    val refTokens: List<String>,
    val userIds: List<Long>,
)

/** One "a counterparty appeared" message the bot owes one person on the no-names side. */
data class Ping(val userId: Long, val text: String, val buttons: List<Button>)

/** Where a flush's output goes. The Telegram layer sends and records; a test collects. */
fun interface AnnouncementSink {
    suspend fun deliver(announcements: List<Announcement>, pings: List<Ping>)
}

/**
 * A showing rests the moment it is created; only its announcement waits here. The first
 * private statement opens a 60-second window, everything stated before it fires goes out
 * as one message per chat, and then the window RESETS — a sliding window would starve a
 * busy person indefinitely, and a showing must never be more than a minute behind the
 * person who stated it.
 *
 * Pings batch on their own window keyed by RECIPIENT, not by whoever caused them:
 * somebody else's typing speed must not decide how many messages you get.
 *
 * Nothing here is ever replayed. What is persisted is which chat is owed an announcement
 * about which interest ([PendingAnnouncementRepository]); the text is re-rendered from
 * live state at every flush, so a restart inside the window cannot leave a showing
 * resting in a chat that was never told, and cannot announce a request that has since
 * closed.
 */
class AnnouncementBatcher(
    private val requests: RequestRepository,
    private val chats: ChatSettingsRepository,
    private val pending: PendingAnnouncementRepository,
    private val interests: InterestService,
    private val sink: AnnouncementSink,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val window: Duration = Duration.ofSeconds(60),
    private val maxAge: Duration = Duration.ofHours(1),
) {
    private val announceWindows = ConcurrentHashMap<Long, Job>()
    private val appearedWindows = ConcurrentHashMap<Long, Job>()
    private val appearedQueue = ConcurrentHashMap<Long, MutableSet<String>>()

    /** Opens the window if it is not already open. The statement itself is already persisted. */
    fun enqueueAnnouncement(userId: Long) {
        // computeIfAbsent, not put: a second statement inside the window joins the batch
        // rather than pushing its deadline out.
        announceWindows.computeIfAbsent(userId) {
            scope.launch {
                delay(window.toMillis())
                announceWindows.remove(userId)
                flush(userId)
            }
        }
    }

    fun enqueueAppeared(appeared: List<CounterpartyAppeared>) {
        for (a in appeared) {
            appearedQueue.computeIfAbsent(a.userId) { ConcurrentHashMap.newKeySet() }.add(a.refToken)
            appearedWindows.computeIfAbsent(a.userId) {
                scope.launch {
                    delay(window.toMillis())
                    appearedWindows.remove(a.userId)
                    flushAppeared(a.userId)
                }
            }
        }
    }

    suspend fun flush(userId: Long) = deliver(pending.allFor(userId))

    /** At startup, everything still owed — from before a restart — is re-rendered and sent. */
    suspend fun flushAllOnStartup() = deliver(pending.all())

    private suspend fun deliver(rows: List<PendingAnnouncement>) {
        if (rows.isEmpty()) return
        val cutoff = clock.instant().minus(maxAge)
        val announcements = mutableListOf<Announcement>()
        // An hour-late "someone just stated this" is noise, and the showing keeps working
        // silently regardless, so a stale row is dropped rather than sent.
        val (stale, live) = rows.partition { it.createdAt.isBefore(cutoff) }
        for (row in stale) pending.remove(row.chatRef, row.interestToken)

        for ((chatId, forChat) in groupByChat(live)) {
            val shown = forChat.mapNotNull { row ->
                val showing = requests.siblings(row.interestToken)
                    .firstOrNull { it.chatId == chatId && it.state == RequestState.OPEN }
                    ?: return@mapNotNull null
                val chat = chats.get(chatId)
                ShownInterest(
                    showing,
                    findCounterparties(showing, requests.resting(chatId), rates(chat), chat.tolerancePct),
                )
            }
            // Every row for this chat is consumed whether or not it survived the re-render:
            // an announcement whose showings have all closed has nothing left to say.
            for (row in forChat) pending.remove(row.chatRef, row.interestToken)
            if (shown.isEmpty()) continue
            val owner = shown.first().request
            val chat = chats.get(chatId)
            announcements += Announcement(
                chatId = chatId,
                text = renderAnnouncement(
                    mention(owner.username, owner.userId, owner.username ?: "this person"),
                    shown,
                    ratesStatus(chat),
                ),
                buttons = announcementButtons(shown),
                refTokens = shown.map { it.request.refToken } + shown.flatMap { s -> s.found.map { it.request.refToken } },
                userIds = shown.map { it.request.userId } + shown.flatMap { s -> s.found.map { it.request.userId } },
            )
        }
        if (announcements.isNotEmpty()) sink.deliver(announcements, emptyList())
    }

    /** One message per recipient, whatever else was going on. */
    suspend fun flushAppeared(userId: Long) {
        val tokens = appearedQueue.remove(userId).orEmpty()
        if (tokens.isEmpty()) return
        val mine = tokens.mapNotNull { requests.byRefToken(it) }
            .filter { it.state == RequestState.OPEN }
            .map { ShownInterest(it, interests.counterparties(it)) }
            .filter { it.found.isNotEmpty() }
        if (mine.isEmpty()) return
        sink.deliver(emptyList(), listOf(Ping(userId, renderAppeared(mine), appearedButtons(mine))))
    }

    /** Pending rows carry a chat REF; the real id comes out of the interest's own showings. */
    private fun groupByChat(rows: List<PendingAnnouncement>): Map<Long, List<PendingAnnouncement>> {
        val out = mutableMapOf<Long, MutableList<PendingAnnouncement>>()
        for (row in rows) {
            val chatId = requests.siblings(row.interestToken)
                .map { it.chatId }
                .firstOrNull { it != NO_NAMES_CHAT_ID && pending.isFor(row, it) }
            if (chatId == null) {
                // The interest is gone entirely; there is nothing left to announce.
                pending.remove(row.chatRef, row.interestToken)
                continue
            }
            out.getOrPut(chatId) { mutableListOf() }.add(row)
        }
        return out
    }

    private fun ratesStatus(chat: ChatSettings) = interestsRateStatus(chat)
    private fun rates(chat: ChatSettings) = interestsRateStatus(chat).rate
    private fun interestsRateStatus(chat: ChatSettings) = rateStatusFor(chat.pair)
    private fun rateStatusFor(pair: CurrencyPair) = rateService.status(pair)
}
```

Note the last four private helpers are a placeholder for one dependency the class needs and does not yet declare. Replace them with a constructor parameter `private val rateService: RateService` (add it after `interests`) and two one-liners:

```kotlin
    private fun ratesStatus(chat: ChatSettings): RateStatus = rateService.status(chat.pair)
    private fun rates(chat: ChatSettings) = ratesStatus(chat).rate
```

Update `BatchFixture` in the test to pass `f.rates` in that position.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests 'fxbot.AnnouncementBatcherTest'`
Expected: PASS. If the tumbling-window test is flaky on a loaded machine, raise its window and delays proportionally — never by making the assertion weaker.

- [ ] **Step 6: Run the whole suite and commit**

Run: `./gradlew test`

```bash
git add src/main/kotlin/fxbot/AnnouncementBatcher.kt src/main/kotlin/fxbot/Render.kt src/test/kotlin/fxbot/AnnouncementBatcherTest.kt
git commit -m "feat: batch announcements on a tumbling window"
```

---

### Task 10: The name give-up

**Files:**
- Create: `src/main/kotlin/fxbot/GiveUpService.kt`
- Test: `src/test/kotlin/fxbot/GiveUpServiceTest.kt`

**Interfaces:**
- Consumes: `NameGiveUpRepository` (Task 6), `RequestRepository`.
- Produces:
  ```kotlin
  data class Handle(val username: String?, val displayName: String)
  fun interface NameLookup { suspend fun handleFor(userId: Long): Handle? }
  data class Party(val userId: Long, val refToken: String, val handle: String)

  sealed interface GiveUpResult {
      data class Asked(val peerUserId: Long, val peerRefToken: String, val myRefToken: String, val mine: Request) : GiveUpResult
      data class Disclosed(val a: Party, val b: Party) : GiveUpResult
      data class Recorded(val text: String) : GiveUpResult
      data class Refused(val text: String) : GiveUpResult
  }

  class GiveUpService(requests, giveUps, names) {
      suspend fun offer(userId: Long, myToken: String, peerToken: String): GiveUpResult
      fun decline(userId: Long, myToken: String, peerToken: String): GiveUpResult
      suspend fun introducible(a: Request, b: Request): Boolean
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/fxbot/GiveUpServiceTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private class GiveUpFixture(name: String, private val handles: Map<Long, Handle>) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val giveUps = NameGiveUpRepository(ds, crypto)
    var lookups = 0
    val svc = GiveUpService(requests, giveUps, NameLookup { id -> lookups++; handles[id] })

    fun rest(userId: Long, side: Side) =
        requests.create(NO_NAMES_CHAT_ID, userId, null, side, "EUR", BigDecimal("1000"), EURRUB, 7, "i$userId")
}

private fun handled(vararg pairs: Pair<Long, Handle>) = pairs.toMap()

class GiveUpServiceTest : StringSpec({
    "one side's offer discloses nothing and asks the other" {
        val f = GiveUpFixture("askpeer", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Asked>()
        r.peerUserId shouldBe 2L
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED
        f.giveUps.bothOffered(a.refToken, b.refToken) shouldBe false
    }

    "both offers disclose each side's handle to the other, once" {
        val f = GiveUpFixture("both", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken)
        val r = f.svc.offer(2L, b.refToken, a.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Disclosed>()
        setOf(r.a.userId, r.b.userId) shouldBe setOf(1L, 2L)
        (r.a.handle + r.b.handle) shouldContain "@bob"
        (r.a.handle + r.b.handle) shouldContain "@ann"
    }

    "names are looked up live at give-up time, never stored" {
        val f = GiveUpFixture("livelookup", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken)
        f.svc.offer(2L, b.refToken, a.refToken)
        f.lookups shouldBe 4 // both sides checked before consent, both fetched again at disclosure
        // Nothing about a name is in the consent table.
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED
    }

    "neither side having a handle is refused before anyone consents" {
        val f = GiveUpFixture("nohandle", handled(1L to Handle(null, "Bob"), 2L to Handle(null, "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        r.text shouldContain "@username"
        r.text shouldNotContain "Bob"
        r.text shouldNotContain "Ann"
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe null
    }

    "one handle between the two is enough" {
        val f = GiveUpFixture("onehandle", handled(1L to Handle(null, "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken).shouldBeInstanceOf<GiveUpResult.Asked>()
    }

    "a forged payload claiming the peer agreed discloses nothing" {
        val f = GiveUpFixture("forged", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        // Person 1 presses, but hands in the PEER's token as their own.
        f.svc.offer(1L, b.refToken, a.refToken).shouldBeInstanceOf<GiveUpResult.Refused>()
        f.giveUps.stanceOf(b.refToken, a.refToken) shouldBe null
        // And pressing legitimately still only records one side.
        f.svc.offer(1L, a.refToken, b.refToken).shouldBeInstanceOf<GiveUpResult.Asked>()
    }

    "a decline suppresses the pairing for both and names nobody" {
        val f = GiveUpFixture("decline", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        val r = f.svc.decline(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Recorded>()
        r.text shouldNotContain "Ann"
        f.giveUps.declined(a.refToken, b.refToken) shouldBe true
        f.svc.offer(2L, b.refToken, a.refToken).shouldBeInstanceOf<GiveUpResult.Refused>()
    }

    "an offer whose peer request has closed is refused, naming nobody" {
        val f = GiveUpFixture("peergone", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.offer(1L, a.refToken, b.refToken)
        f.requests.closeInterest("i2", RequestState.CANCELLED)
        val r = f.svc.offer(1L, a.refToken, b.refToken)
        r.shouldBeInstanceOf<GiveUpResult.Refused>()
        r.text shouldNotContain "Ann"
        r.text shouldNotContain "@ann"
    }

    "a decline dies with the requests" {
        val f = GiveUpFixture("declinedies", handled(1L to Handle("bob", "Bob"), 2L to Handle("ann", "Ann")))
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.svc.decline(1L, a.refToken, b.refToken)
        f.requests.closeInterest("i2", RequestState.DONE)
        f.giveUps.dropClosed()
        f.giveUps.declined(a.refToken, b.refToken) shouldBe false
    }
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'fxbot.GiveUpServiceTest'`
Expected: FAIL — `Unresolved reference: GiveUpService`, `NameLookup`, `Handle`.

- [ ] **Step 3: Write `GiveUpService.kt`**

```kotlin
package fxbot

/** What Telegram knows about somebody right now. Never stored (ADR 0007). */
data class Handle(val username: String?, val displayName: String)

/** Looks somebody up live. The Telegram layer answers with `getChat`; a test answers with a map. */
fun interface NameLookup {
    suspend fun handleFor(userId: Long): Handle?
}

/** One person as they are handed to the other: their own request, and how to reach them. */
data class Party(val userId: Long, val refToken: String, val handle: String)

sealed interface GiveUpResult {
    /** The presser's consent is recorded; the other side must now be asked. */
    data class Asked(
        val peerUserId: Long,
        val peerRefToken: String,
        val myRefToken: String,
        val mine: Request,
    ) : GiveUpResult

    /** Both consented: hand each side the other. */
    data class Disclosed(val a: Party, val b: Party) : GiveUpResult

    /** Something was written and there is nothing to disclose — a decline. */
    data class Recorded(val text: String) : GiveUpResult

    /** Nothing was written. The text never names anybody. */
    data class Refused(val text: String) : GiveUpResult
}

private const val NO_ROUTE =
    "I can't introduce you two: neither of you has an @username, and a name alone isn't something " +
        "the other person could act on."

private const val PEER_GONE = "That one is no longer waiting, so there's nobody left to introduce you to."

private const val NOT_YOURS = "That isn't your interest."

/**
 * Identities pass only by mutual give-up (ADR 0007). Consent is persisted, never carried
 * in a button: the presser is re-derived from `callback_query.from.id` by the caller, and
 * agreement is read from [NameGiveUpRepository], so a hand-crafted payload claiming the
 * other side agreed achieves nothing.
 *
 * Before EITHER side is offered anything, at least one of the two must have an
 * `@username`. A `tg://user?id=` link is reliably actionable only between people who
 * share a chat, which these two by definition do not — so a give-up without a handle
 * would hand over two names neither person can act on. Checking it first fails before
 * anyone consents rather than after both did.
 */
class GiveUpService(
    private val requests: RequestRepository,
    private val giveUps: NameGiveUpRepository,
    private val names: NameLookup,
) {
    /** At least one @username between the two. */
    suspend fun introducible(a: Request, b: Request): Boolean =
        names.handleFor(a.userId)?.username != null || names.handleFor(b.userId)?.username != null

    suspend fun offer(userId: Long, myToken: String, peerToken: String): GiveUpResult {
        val mine = requests.byRefToken(myToken) ?: return GiveUpResult.Refused(PEER_GONE)
        if (mine.userId != userId) return GiveUpResult.Refused(NOT_YOURS)
        val theirs = requests.byRefToken(peerToken) ?: return GiveUpResult.Refused(PEER_GONE)
        if (mine.state != RequestState.OPEN || theirs.state != RequestState.OPEN) {
            return GiveUpResult.Refused(PEER_GONE)
        }
        if (giveUps.declined(myToken, peerToken)) return GiveUpResult.Refused(PEER_GONE)
        if (!introducible(mine, theirs)) return GiveUpResult.Refused(NO_ROUTE)

        giveUps.record(myToken, peerToken, userId, Stance.OFFERED)
        if (!giveUps.bothOffered(myToken, peerToken)) {
            return GiveUpResult.Asked(theirs.userId, peerToken, myToken, mine)
        }
        // Looked up again, right now: a give-up happens days after the interest was
        // stated, so what passes is current, and forgetting has nothing extra to erase.
        val mineHandle = names.handleFor(mine.userId) ?: return GiveUpResult.Refused(NO_ROUTE)
        val theirsHandle = names.handleFor(theirs.userId) ?: return GiveUpResult.Refused(NO_ROUTE)
        return GiveUpResult.Disclosed(
            Party(mine.userId, myToken, mention(mineHandle.username, mine.userId, mineHandle.displayName)),
            Party(theirs.userId, peerToken, mention(theirsHandle.username, theirs.userId, theirsHandle.displayName)),
        )
    }

    /**
     * A decline covers this pairing of requests symmetrically — neither side is offered
     * the other again — and dies with either request. It is deliberately not a durable
     * record of two people who don't want each other.
     */
    fun decline(userId: Long, myToken: String, peerToken: String): GiveUpResult {
        val mine = requests.byRefToken(myToken) ?: return GiveUpResult.Refused(PEER_GONE)
        if (mine.userId != userId) return GiveUpResult.Refused(NOT_YOURS)
        giveUps.record(myToken, peerToken, userId, Stance.DECLINED)
        return GiveUpResult.Recorded("Noted — I won't offer you that one again while it's waiting.")
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'fxbot.GiveUpServiceTest'`
Expected: PASS. The `lookups shouldBe 4` assertion pins the "looked up live, never stored" behaviour — `introducible` reads both on each of the two presses (2 + 2 = 4) and the disclosure branch reads them again; if the implementation's call count differs, fix the assertion to the real number **and** keep a test that proves no name reaches the database.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/fxbot/GiveUpService.kt src/test/kotlin/fxbot/GiveUpServiceTest.kt
git commit -m "feat: pass names only when both sides agree"
```

---

### Task 11: Restating a residual

**Files:**
- Create: `src/main/kotlin/fxbot/Residual.kt`
- Test: `src/test/kotlin/fxbot/ResidualTest.kt`

**Interfaces:**
- Consumes: `notional` (Matcher.kt), `Request`.
- Produces:
  ```kotlin
  fun residualOf(mine: Request, theirs: Request, rate: BigDecimal?): BigDecimal?
  fun restateGoesPrivate(mine: Request): Boolean
  ```
  Both are pure. The button that carries them and the press handler are Task 13.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/fxbot/ResidualTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

private val EURRUB = CurrencyPair("EUR", "RUB")
private val RATE = BigDecimal("100")

private fun req(verb: Verb, amount: String, ccy: String, chatId: Long = -100L, interest: String? = null) =
    Request(
        refToken = "t".repeat(22), chatId = chatId, userId = 1L, username = "bob", shortId = "a1",
        side = sideFor(verb, ccy, EURRUB), statedCurrency = ccy, statedAmount = BigDecimal(amount),
        pair = EURRUB, state = RequestState.DONE, createdAt = Instant.EPOCH, expiresAt = Instant.EPOCH,
        interestToken = interest,
    )

class ResidualTest : StringSpec({
    "the larger side keeps the difference, in its own stated currency" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "600", "EUR"), RATE) shouldBe BigDecimal("400")
    }
    "the smaller side has none" {
        residualOf(req(Verb.BUY, "600", "EUR"), req(Verb.SELL, "1000", "EUR"), RATE) shouldBe null
    }
    "equal sizes leave none" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "1000", "EUR"), RATE) shouldBe null
    }
    "across currencies it is expressed in the presser's own, at the reference rate" {
        // 1000 EUR against 60,000 RUB (= 600 EUR at 100) leaves 400 EUR.
        val left = residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "60000", "RUB"), RATE)!!
        left.setScale(2, RoundingMode.HALF_UP) shouldBe BigDecimal("400.00")
    }
    "a presser who stated the quote currency gets their residual back in it" {
        // 100,000 RUB (= 1000 EUR) against 600 EUR leaves 400 EUR, i.e. 40,000 RUB.
        val left = residualOf(req(Verb.SELL, "100000", "RUB"), req(Verb.BUY, "600", "EUR"), RATE)!!
        left.setScale(2, RoundingMode.HALF_UP) shouldBe BigDecimal("40000.00")
    }
    "across currencies with no rate there is nothing to state" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "60000", "RUB"), null) shouldBe null
    }
    "a non-positive rate is treated as no rate at all" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "60000", "RUB"), BigDecimal.ZERO) shouldBe null
    }
    "same-currency amounts need no rate" {
        residualOf(req(Verb.SELL, "1000", "EUR"), req(Verb.BUY, "600", "EUR"), null) shouldBe BigDecimal("400")
    }

    "a residual of a privately stated interest is restated privately" {
        restateGoesPrivate(req(Verb.SELL, "1000", "EUR", chatId = NO_NAMES_CHAT_ID, interest = "i1")) shouldBe true
        restateGoesPrivate(req(Verb.SELL, "1000", "EUR", chatId = -100L, interest = "i1")) shouldBe true
    }
    "a residual of a request typed in a group stays in that group" {
        restateGoesPrivate(req(Verb.SELL, "1000", "EUR", chatId = -100L, interest = null)) shouldBe false
    }
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'fxbot.ResidualTest'`
Expected: FAIL — `Unresolved reference: residualOf`.

- [ ] **Step 3: Write `Residual.kt`**

```kotlin
package fxbot

import java.math.BigDecimal
import java.math.MathContext

private val MC = MathContext.DECIMAL64

/**
 * What is left of [mine] after a done against [theirs], in [mine]'s own stated currency,
 * or null when there is nothing to state.
 *
 * A done closes both requests even when one side has size left over, because the
 * alternative — reducing a stated amount and leaving the request resting — would have the
 * bot publishing a number nobody typed (ADR 0003, ADR 0006). The residual is therefore a
 * NEW thing the person states, computed here at press time from the two closed rows, and
 * the original's stated amount is never rewritten.
 *
 * Null when the residual is zero or negative (the smaller side has none), and when the
 * two are stated in different currencies with no reference rate to bridge them.
 */
fun residualOf(mine: Request, theirs: Request, rate: BigDecimal?): BigDecimal? {
    if (mine.statedCurrency == theirs.statedCurrency) {
        return (mine.statedAmount - theirs.statedAmount).takeIf { it.signum() > 0 }
    }
    if (rate == null || rate.signum() <= 0) return null
    val a = notional(mine, rate) ?: return null
    val b = notional(theirs, rate) ?: return null
    val leftInBase = a - b
    if (leftInBase.signum() <= 0) return null
    // Back into the presser's own words: base amounts are already there, a quote amount
    // is the base figure at the reference rate.
    return if (mine.statedCurrency == mine.pair.base) leftInBase else leftInBase.multiply(rate, MC)
}

/**
 * Where a restated residual goes. A request born of an interest stated privately — the
 * no-names row itself, or any of its showings — is restated as a new interest and fans
 * out like any other. A request TYPED in a group is restated in that group alone:
 * fanning it out bot-wide would put someone on the no-names side who never asked, and
 * consent is what puts them there (ADR 0007).
 */
fun restateGoesPrivate(mine: Request): Boolean = mine.interestToken != null || mine.chatId == NO_NAMES_CHAT_ID
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests 'fxbot.ResidualTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/fxbot/Residual.kt src/test/kotlin/fxbot/ResidualTest.kt
git commit -m "feat: work out the residual a done leaves"
```

---

### Task 12: Closing the whole interest, and editing the messages that carried it

**Files:**
- Modify: `src/main/kotlin/fxbot/LifecycleService.kt`
- Modify: `src/main/kotlin/fxbot/ButtonService.kt`
- Modify: `src/main/kotlin/fxbot/LifecycleCommands.kt`, `src/main/kotlin/fxbot/Callbacks.kt` (call sites)
- Test: `src/test/kotlin/fxbot/LifecycleServiceTest.kt`, `src/test/kotlin/fxbot/ButtonServiceTest.kt`

**Interfaces:**
- Consumes: `RequestRepository.closeInterest` / `reopenInterest` (Task 3), `MessageLogRepository.logged` (Task 7), `residualOf` (Task 11).
- Produces:
  ```kotlin
  data class RestateOffer(val myToken: String, val peerToken: String, val amount: BigDecimal, val currency: String)
  sealed interface ActionResult {
      data class Ok(override val text: String, val closedTokens: List<String>, val restate: RestateOffer? = null) : ActionResult
      ...
  }
  class LifecycleService(requests: RequestRepository, settings: ChatSettingsRepository, rates: RateService)

  class ButtonService(log: MessageLogRepository, requests: RequestRepository) {
      suspend fun refreshFor(closedTokens: List<String>, bot: TelegramBot)
  }
  ```
  `stripFor` is replaced by `refreshFor`, which takes no chat id: a closed token may be carried by messages in several chats at once (an interest is shown in several), and every one of them has to be told.

- [ ] **Step 1: Write the failing tests**

Update `LifecycleServiceTest.kt`'s `lifecycle(name)` helper to build the two new dependencies, then append:

```kotlin
    "cancelling one showing withdraws the whole interest" {
        val (svc, repo) = lifecycle("cancelinterest")
        val noNames = repo.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val here = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val there = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val r = svc.cancel(-100L, 1L, here.shortId)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.closedTokens.toSet() shouldBe setOf(noNames.refToken, here.refToken, there.refToken)
        repo.byRefToken(there.refToken)!!.state shouldBe RequestState.CANCELLED
        repo.byRefToken(noNames.refToken)!!.state shouldBe RequestState.CANCELLED
    }
    "a done closes both interests whole" {
        val (svc, repo) = lifecycle("doneinterest")
        val mineHere = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val mineThere = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val theirsThere = repo.create(-200L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        svc.done(1L, mineHere.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(mineThere.refToken)!!.state shouldBe RequestState.DONE
        repo.byRefToken(theirsThere.refToken)!!.state shouldBe RequestState.DONE
    }
    "a request typed in a chat still closes alone" {
        val (svc, repo) = lifecycle("loneclose")
        val a = repo.put(-100L, 1L, "bob", Side.OFFER)
        val b = repo.put(-200L, 1L, "bob", Side.OFFER)
        svc.cancel(-100L, 1L, a.shortId).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(b.refToken)!!.state shouldBe RequestState.OPEN
    }
    "reopen brings back the siblings it closed" {
        val (svc, repo) = lifecycle("reopeninterestsvc")
        val here = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val there = repo.create(-200L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        svc.cancel(-100L, 1L, here.shortId)
        svc.reopen(-100L, 1L, 7).shouldBeInstanceOf<ActionResult.Ok>()
        repo.byRefToken(here.refToken)!!.state shouldBe RequestState.OPEN
        repo.byRefToken(there.refToken)!!.state shouldBe RequestState.OPEN
    }
    "a done that leaves the presser a residual offers it back to them" {
        val (svc, repo) = lifecycle("residualoffered")
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7)
        val r = svc.done(1L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.restate!!.amount shouldBe BigDecimal("400")
        r.restate!!.currency shouldBe "EUR"
        r.restate!!.myToken shouldBe mine.refToken
        // The original is untouched: the residual is a new thing the person states.
        repo.byRefToken(mine.refToken)!!.statedAmount shouldBe BigDecimal("1000")
    }
    "the smaller side is offered nothing" {
        val (svc, repo) = lifecycle("noresidual")
        val mine = repo.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("600"), EURRUB, 7)
        val theirs = repo.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        val r = svc.done(1L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.restate shouldBe null
    }
```

Create `src/test/kotlin/fxbot/ButtonServiceTest.kt`:

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private data class Call(val path: String, val body: String)

private fun recordingBot(sink: MutableList<Call>): TelegramBot {
    val client = HttpClient(MockEngine { request ->
        val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)
        sink += Call(request.url.encodedPath.substringAfterLast('/'), bytes.decodeToString())
        respond(
            """{"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-100,"type":"group"}}}""",
            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
        )
    })
    return TelegramBot(token = "000:fake-token-for-button-test", httpClient = client)
}

private class ButtonFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val log = MessageLogRepository(ds, crypto)
    val svc = ButtonService(log, requests)
}

class ButtonServiceTest : StringSpec({
    "a closed request's message is rewritten to its own text plus a status line" {
        val f = ButtonFixture("editstatus")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        f.log.record(-100L, 10L, listOf(a.refToken), listOf(1L), "1 person matches:", listOf(Button("✖️ Cancel", Cb.cancel(a.refToken))))
        f.requests.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken), recordingBot(calls))
        val edit = calls.single { it.path == "editMessageText" }
        edit.body shouldContain "1 person matches:"
        edit.body shouldContain "withdrawn"
    }
    "the status line says how it closed" {
        val f = ButtonFixture("statuswords")
        val done = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val lapsed = f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        f.log.record(-100L, 10L, listOf(done.refToken), listOf(1L), "x", emptyList())
        f.log.record(-100L, 11L, listOf(lapsed.refToken), listOf(2L), "x", emptyList())
        f.requests.transition(done.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(lapsed.refToken, RequestState.OPEN, RequestState.EXPIRED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(done.refToken, lapsed.refToken), recordingBot(calls))
        val bodies = calls.filter { it.path == "editMessageText" }.joinToString("\n") { it.body }
        bodies shouldContain "done"
        bodies shouldContain "lapsed"
    }
    "closing one interest keeps the buttons of the others on a batched message" {
        val f = ButtonFixture("keepothers")
        val closing = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val staying = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("2"), EURRUB, 7)
        f.log.record(
            -100L, 10L, listOf(closing.refToken, staying.refToken), listOf(1L, 1L), "two interests",
            listOf(Button("✖️ Cancel a", Cb.cancel(closing.refToken)), Button("✖️ Cancel b", Cb.cancel(staying.refToken))),
        )
        f.requests.transition(closing.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(closing.refToken), recordingBot(calls))
        val edit = calls.single { it.path == "editMessageText" }
        edit.body shouldContain staying.refToken
        edit.body shouldNotContain closing.refToken
    }
    "a message recorded before the text was stored falls back to stripping its keyboard" {
        val f = ButtonFixture("legacystrip")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        f.log.record(-100L, 10L, listOf(a.refToken), listOf(1L)) // no text, no buttons
        f.requests.transition(a.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken), recordingBot(calls))
        calls.filter { it.path == "editMessageReplyMarkup" } shouldHaveSize 1
        calls.filter { it.path == "editMessageText" } shouldHaveSize 0
    }
    "each carrier message is edited once, however many of its tokens closed" {
        val f = ButtonFixture("onceper")
        val a = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7)
        val b = f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("1"), EURRUB, 7)
        f.log.record(-100L, 10L, listOf(a.refToken, b.refToken), listOf(1L, 2L), "both", emptyList())
        f.requests.transition(a.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(b.refToken, RequestState.OPEN, RequestState.DONE)
        val calls = mutableListOf<Call>()
        f.svc.refreshFor(listOf(a.refToken, b.refToken), recordingBot(calls))
        calls.filter { it.path == "editMessageText" } shouldHaveSize 1
    }
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.LifecycleServiceTest' --tests 'fxbot.ButtonServiceTest'`
Expected: FAIL — `Unresolved reference: refreshFor`, `restate`, and a constructor arity mismatch on `LifecycleService`/`ButtonService`.

- [ ] **Step 3: Close the whole interest**

In `LifecycleService.kt`:

```kotlin
/** A residual the presser can state in one press. Never written to the closed row (ADR 0003). */
data class RestateOffer(
    val myToken: String,
    val peerToken: String,
    val amount: java.math.BigDecimal,
    val currency: String,
)

sealed interface ActionResult {
    val text: String

    /** [closedTokens] lists every request just closed, so message cleanup knows what to rewrite. */
    data class Ok(
        override val text: String,
        val closedTokens: List<String>,
        val restate: RestateOffer? = null,
    ) : ActionResult
    data class Denied(override val text: String) : ActionResult
    data class Gone(override val text: String) : ActionResult
}

class LifecycleService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
) {
```

Inside the class:

```kotlin
    /**
     * Done and cancel are decisions about the whole interest, so they close every showing
     * with it. A request typed in a chat has no siblings and closes alone. Expiry is NOT
     * this — see [RequestRepository.expireDue].
     */
    private fun closeWhole(r: Request, to: RequestState): List<String> =
        r.interestToken?.let { requests.closeInterest(it, to) }
            ?: if (requests.transition(r.refToken, RequestState.OPEN, to)) listOf(r.refToken) else emptyList()

    /** Each chat's own time in force, and the no-names default for the row with no chat. */
    private fun tifFor(chatId: Long): Int =
        if (chatId == NO_NAMES_CHAT_ID) NO_NAMES_TIF_DAYS else settings.get(chatId).tifDays
```

Rewrite `cancelByToken` to use `closeWhole`:

```kotlin
    fun cancelByToken(userId: Long, token: String): ActionResult {
        val r = requests.byRefToken(token) ?: return ActionResult.Gone("That request is gone.")
        if (r.userId != userId) return ActionResult.Denied("That's not your request.")
        if (r.state != RequestState.OPEN) return ActionResult.Gone("That request is already closed.")
        val closed = closeWhole(r, RequestState.CANCELLED)
        return if (closed.isEmpty()) ActionResult.Gone("That request is already closed.")
        else ActionResult.Ok("Withdrawn.", closed)
    }
```

Rewrite `done`'s success branch. Keep every existing authorization check exactly as it is (the presser must own one of the two; the two must be same chat, opposite sides, different people; the size tolerance is deliberately not re-checked). Replace the `requests.markDone(...)` call with:

```kotlin
        if (mine.state != RequestState.OPEN) return ActionResult.Gone("That one is already closed.")
        val closedMine = closeWhole(mine, RequestState.DONE)
        if (closedMine.isEmpty()) return ActionResult.Gone("That one is already closed.")
        val closedTheirs = theirs?.takeIf { it.state == RequestState.OPEN }
            ?.let { closeWhole(it, RequestState.DONE) }
            .orEmpty()

        if (theirs != null && closedTheirs.isEmpty()) {
            return ActionResult.Ok(
                "Closed only ${nameOf(mine)}'s — ${nameOf(theirs)}'s was already closed.",
                closedMine,
            )
        }
        val text = if (theirs != null) {
            "Marked done: ${nameOf(mine)} and ${nameOf(theirs)}. If that's wrong, /reopen."
        } else {
            "Marked done. If that's wrong, /reopen."
        }
        // The residual is recomputed from the two closed rows, in the presser's own stated
        // currency. No offer when it is zero, or when the currencies differ and no rate is
        // available.
        val offer = theirs?.let { peer ->
            residualOf(mine, peer, rates.status(mine.pair).rate)?.let { left ->
                RestateOffer(mine.refToken, peer.refToken, left, mine.statedCurrency)
            }
        }
        return ActionResult.Ok(text, closedMine + closedTheirs, offer)
```

`RequestRepository.markDone` and `DoneOutcome` now have no caller. Delete both, and delete any test that exercised `markDone` directly — its behaviour is covered by `closeInterest` (Task 3) and the `done` tests above.

Rewrite `reopen`'s success branch to revive siblings:

```kotlin
        val revived = last.interestToken
            ?.let { requests.reopenInterest(it, last.state, ::tifFor) }
            ?: if (requests.reopen(last.refToken, tifDays)) listOf(last.refToken) else emptyList()
        return if (revived.isEmpty()) ActionResult.Gone("That one is already waiting.")
        else ActionResult.Ok("Back on the waitlist: ${describe(last)}", emptyList())
```

- [ ] **Step 4: Rewrite `ButtonService`**

```kotlin
package fxbot

import eu.vendeli.tgbot.TelegramBot
import eu.vendeli.tgbot.api.message.editMessageReplyMarkup
import eu.vendeli.tgbot.api.message.editMessageText
import eu.vendeli.tgbot.types.component.ParseMode

/** One `/cancel` can touch several messages, and Telegram rate-limits edits. */
private const val FAN_OUT = 10

/**
 * Rewrites every message whose buttons named a request that just closed: its stored text
 * plus a status line per closed token, with the keyboard rebuilt from live state in the
 * same pass so the rows whose requests are still open keep theirs.
 *
 * All-or-nothing stripping was tolerable when a message carried one request; a batched
 * message carries several, and closing one interest must not kill the buttons of the
 * others. A message recorded before V3 has no stored text, so it falls back to the old
 * strip — there is nothing to rebuild from.
 *
 * Bounded per token: [MessageLogRepository.messagesForToken] returns at most [FAN_OUT]
 * carriers, newest first. An edit that does not land leaves a message stale but harmless,
 * because [LifecycleService] re-derives state from the database on every press and never
 * trusts what a button looks like.
 */
class ButtonService(
    private val log: MessageLogRepository,
    private val requests: RequestRepository,
) {
    suspend fun refreshFor(closedTokens: List<String>, bot: TelegramBot) {
        val targets = closedTokens.flatMap { log.messagesForToken(it, FAN_OUT) }.distinct()
        for (target in targets) {
            val logged = log.logged(target.chatId, target.messageId)
            val storedText = logged?.text
            if (logged == null || storedText == null) {
                // No runCatching: send() never throws on a Telegram-side failure.
                editMessageReplyMarkup(target.messageId).send(target.chatId, bot)
                continue
            }
            val statuses = logged.refTokens.mapNotNull { token ->
                val r = requests.byRefToken(token) ?: return@mapNotNull null
                statusLine(r)
            }
            val keep = logged.buttons.filter { button ->
                logged.refTokens.none { it in button.data && requests.byRefToken(it)?.state?.isTerminal != false }
            }
            val text = (listOf(storedText) + statuses).joinToString("\n")
            editMessageText(target.messageId) { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { keep.forEach { b -> b.label callback b.data; br() } }
                .send(target.chatId, bot)
        }
    }

    /** Says how a request closed, in the vocabulary the person already knows. Null while it rests. */
    private fun statusLine(r: Request): String? = when (r.state) {
        RequestState.OPEN -> null
        RequestState.DONE -> "${r.shortId} — done"
        RequestState.CANCELLED -> "${r.shortId} — withdrawn"
        RequestState.EXPIRED -> "${r.shortId} — lapsed"
    }
}
```

The `keep` filter reads: drop a button whose callback data names any token that is no longer resting. Callback data always names its tokens verbatim (`done?a=X&b=Y`, `cancel?t=X`), so no structural knowledge of the original keyboard is needed.

- [ ] **Step 5: Update the two call sites**

In `LifecycleCommands.kt`'s `replyToClose` and `Callbacks.kt`'s `respond`, replace `Registry.buttons.stripFor(result.closedTokens, chatId, bot)` with `Registry.buttons.refreshFor(result.closedTokens, bot)`.

In `LifecycleCommands.replyToClose` and `Callbacks.respond`, when `result is ActionResult.Ok && result.restate != null`, add a second button beside Reopen:

```kotlin
            reply.inlineKeyboardMarkup {
                "↩️ Reopen" callback Cb.reopen(result.closedTokens.first())
                result.restate?.let {
                    br()
                    "➕ State the rest (${formatAmount(it.amount)} ${it.currency})" callback Cb.restate(it.myToken, it.peerToken)
                }
            }.send(chatId, bot)
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test`
Expected: PASS. Update `LifecycleServiceTest`'s helper and `Main.kt`'s wiring for the new constructor arities; the `restate` callback handler itself lands in Task 13.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/fxbot/LifecycleService.kt src/main/kotlin/fxbot/ButtonService.kt src/main/kotlin/fxbot/LifecycleCommands.kt src/main/kotlin/fxbot/Callbacks.kt src/main/kotlin/fxbot/RequestRepository.kt src/main/kotlin/fxbot/Main.kt src/test/kotlin/fxbot/LifecycleServiceTest.kt src/test/kotlin/fxbot/ButtonServiceTest.kt
git commit -m "feat: close an interest whole and edit the messages that carried it"
```

---

### Task 13: The private command surface, the new buttons, and the wiring

**Files:**
- Create: `src/main/kotlin/fxbot/PrivateCommands.kt`
- Modify: `src/main/kotlin/fxbot/Commands.kt`, `src/main/kotlin/fxbot/LifecycleCommands.kt`, `src/main/kotlin/fxbot/AdminCommands.kt`, `src/main/kotlin/fxbot/Callbacks.kt`, `src/main/kotlin/fxbot/Render.kt`, `src/main/kotlin/fxbot/Registry.kt`, `src/main/kotlin/fxbot/Main.kt`
- Test: `src/test/kotlin/fxbot/PrivateCommandTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 8–12.
- Produces:
  ```kotlin
  // Registry gains:
  lateinit var people: PersonSettingsRepository
  lateinit var interests: InterestService
  lateinit var batcher: AnnouncementBatcher
  lateinit var giveUp: GiveUpService
  lateinit var giveUps: NameGiveUpRepository
  lateinit var pending: PendingAnnouncementRepository

  // Render gains:
  fun renderStated(r: InterestResult.Stated): String
  fun statedButtons(r: InterestResult.Stated): List<Button>
  fun renderStandings(standings: List<InterestStanding>): String

  // Callbacks gain: giveup, decline, restate
  ```

`inGroupOrExplain` stops being a blanket refusal. It survives only for `/pair` and `/tif`, which keep the hint; every other command gets a private meaning.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/fxbot/PrivateCommandTest.kt`. Follow `ForgetCommandTest.kt`'s technique exactly: a real `Registry`, a real in-memory database, and a `TelegramBot` whose HTTP client is a `MockEngine` recording every outgoing call with its body. Copy that file's `Sent`, `botWith`, `recordingBot` and `updateFor` helpers into this one (they are `private` there).

```kotlin
class PrivateCommandTest : StringSpec({
    "a private /sell without 'for' is refused with the example" {
        val f = PrivateFixture("privsellnofor")
        val sent = mutableListOf<Sent>()
        sell(updateFor(555L, ChatType.Private, "/sell 10 EUR"), recordingBot(sent))
        sent.single().body shouldContain "/sell 10 EUR for RUB"
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
    }
    "a private /sell with 'for' states an interest and replies at once" {
        val f = PrivateFixture("privsell")
        val sent = mutableListOf<Sent>()
        sell(updateFor(555L, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        f.requests.resting(NO_NAMES_CHAT_ID) shouldHaveSize 1
        sent.last().path shouldBe "sendMessage"
    }
    "a group /sell is unchanged: one showing, no fan-out, no rate limit" {
        val f = PrivateFixture("groupsell")
        val sent = mutableListOf<Sent>()
        sell(updateFor(-100L, ChatType.Group, "/sell 1000 EUR"), recordingBot(sent))
        f.requests.resting(-100L) shouldHaveSize 1
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
        f.pending.all().shouldBeEmpty()
    }
    "a private /tolerance sets the person's own, a group /tolerance is still the admin command" {
        val f = PrivateFixture("privtolerance")
        val sent = mutableListOf<Sent>()
        tolerance(updateFor(555L, ChatType.Private, "/tolerance 5"), recordingBot(sent))
        f.people.get(1L).tolerancePct shouldBe 5
        f.chats.get(-100L).tolerancePct shouldBe 20 // untouched
    }
    "a private /status lists each interest once, with where it still rests" {
        val f = PrivateFixture("privstatus")
        val sent = mutableListOf<Sent>()
        sell(updateFor(555L, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        sent.clear()
        status(updateFor(555L, ChatType.Private, "/status"), recordingBot(sent))
        sent.single().body shouldContain "10"
    }
    "a private /cancel withdraws the interest and every showing with it" {
        val f = PrivateFixture("privcancel")
        val sent = mutableListOf<Sent>()
        sell(updateFor(555L, ChatType.Private, "/sell 10 EUR for RUB"), recordingBot(sent))
        val shortId = f.requests.resting(NO_NAMES_CHAT_ID).single().shortId
        cancel(updateFor(555L, ChatType.Private, "/cancel $shortId"), recordingBot(sent))
        f.requests.resting(NO_NAMES_CHAT_ID).shouldBeEmpty()
        f.requests.resting(-100L).shouldBeEmpty()
    }
    "a private /settings reports the person's own tolerance, not a chat's" {
        val f = PrivateFixture("privsettings")
        val sent = mutableListOf<Sent>()
        f.people.save(PersonSettings(1L, 40))
        settings(updateFor(555L, ChatType.Private, "/settings"), recordingBot(sent))
        sent.single().body shouldContain "40"
    }
    "a private /pair still gets the group hint" {
        val sent = mutableListOf<Sent>()
        pair(updateFor(555L, ChatType.Private, "/pair EUR RUB"), recordingBot(sent))
        sent.single().body shouldContain "group"
    }
    "a private /tif still gets the group hint" {
        val sent = mutableListOf<Sent>()
        tif(updateFor(555L, ChatType.Private, "/tif 7"), recordingBot(sent))
        sent.single().body shouldContain "group"
    }
    "a give-up press discloses nothing until the other side presses too" {
        val f = PrivateFixture("givepress")
        val a = f.requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val sent = mutableListOf<Sent>()
        giveUpCallback(a.refToken, b.refToken, callbackFrom(1L), recordingBot(sent))
        sent.joinToString { it.body } shouldNotContain "@ann"
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe Stance.OFFERED
    }
    "a forged give-up payload, pressed by someone who owns neither request, discloses nothing" {
        val f = PrivateFixture("giveforged")
        val a = f.requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val b = f.requests.create(NO_NAMES_CHAT_ID, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        f.giveUps.record(b.refToken, a.refToken, 2L, Stance.OFFERED)
        val sent = mutableListOf<Sent>()
        giveUpCallback(a.refToken, b.refToken, callbackFrom(99L), recordingBot(sent))
        sent.joinToString { it.body } shouldNotContain "@ann"
        sent.joinToString { it.body } shouldNotContain "@bob"
        f.giveUps.stanceOf(a.refToken, b.refToken) shouldBe null
    }
    "restating a residual makes a new request rather than rewriting the original" {
        val f = PrivateFixture("restate")
        val mine = f.requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7)
        val theirs = f.requests.create(-100L, 2L, "ann", Side.BID, "EUR", BigDecimal("600"), EURRUB, 7)
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.DONE)
        f.requests.transition(theirs.refToken, RequestState.OPEN, RequestState.DONE)
        val sent = mutableListOf<Sent>()
        restateCallback(mine.refToken, theirs.refToken, callbackFrom(1L, chatId = -100L), recordingBot(sent))
        val fresh = f.requests.resting(-100L).single()
        fresh.statedAmount shouldBe BigDecimal("400")
        fresh.shortId shouldNotBe mine.shortId
        f.requests.byRefToken(mine.refToken)!!.statedAmount shouldBe BigDecimal("1000")
    }
})
```

Add `PrivateFixture` (mirroring `InterestServiceTest`'s `Fixture` but assigning every field into `Registry`, including `Registry.batcher` with a sink that records into a list) and `callbackFrom(userId, chatId = 555L)`, which builds a `CallbackQueryUpdate` the same way `updateFor` builds a `MessageUpdate` — read the framework's `CallbackQueryUpdate` and `CallbackQuery` constructors and fill only the fields the handlers touch (`from`, `message`/`chat`, `id`).

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests 'fxbot.PrivateCommandTest'`
Expected: FAIL to compile — `giveUpCallback`, `restateCallback`, `Registry.interests` and the rest do not exist.

- [ ] **Step 3: Split the group and private paths**

In `Commands.kt`:

```kotlin
private const val PRIVATE_HINT =
    "That one is for a group chat's admins. Add me to your group and use it there."

/** Groups have a per-person sender to match, mention, and authorize; channels do not. */
internal fun ProcessedUpdate.isGroupChat(): Boolean =
    getChat().type == ChatType.Group || getChat().type == ChatType.Supergroup

/**
 * Kept for `/pair` and `/tif` only. Every other command now has a private meaning: a
 * person states an interest to the bot privately and it is shown in the chats they share
 * with it, so a blanket "add me to a group" refusal would refuse the whole point.
 */
internal suspend fun inGroupOrExplain(update: ProcessedUpdate, bot: TelegramBot): Boolean {
    if (update.isGroupChat()) return true
    message { PRIVATE_HINT }.send(update.getChat().id, bot)
    return false
}
```

`handlePost` branches at the top:

```kotlin
private suspend fun handlePost(verb: Verb, update: ProcessedUpdate, bot: TelegramBot) {
    if (!update.isGroupChat()) return handlePrivatePost(verb, update, bot)
    ... // the existing body, unchanged
}
```

`/status`, `/settings` and `/help` branch the same way to private counterparts in `PrivateCommands.kt`. `/start` in a private chat sends the private help text instead of the old hint.

In `LifecycleCommands.kt`, `/cancel` and `/done` replace `if (!inGroupOrExplain(update, bot)) return` with a chat-id choice — in a private chat they act on the no-names space:

```kotlin
    val scopeId = if (update.isGroupChat()) chat.id else NO_NAMES_CHAT_ID
```

and pass `scopeId` to `Registry.lifecycle.cancel` / `doneByShortId` and to `resolvePeer`. `resolvePeer`'s `Registry.requests.resting(chatId)` fallback then looks in the right place: privately, an `@username` is matched against the handles on requests resting on a no-names basis. A peer with no `@username` cannot be addressed by the typed form at all — the Done button on the give-up message is the reliable path, and no second identifier scheme is invented to make them typeable.

In `AdminCommands.kt`, `/tolerance` branches before `adminOnly`:

```kotlin
@CommandHandler(["/tolerance"])
suspend fun tolerance(update: ProcessedUpdate, bot: TelegramBot) {
    if (!update.isGroupChat()) return privateTolerance(update, bot)
    adminOnly("tolerance", update, bot) { args ->
        Registry.admin.setTolerance(update.getChat().id, args.firstOrNull().orEmpty())
    }
}
```

`/pair`, `/tif` and `/fanout` keep `adminOnly` unchanged and therefore keep the hint.

- [ ] **Step 4: Write `PrivateCommands.kt`**

It holds: `handlePrivatePost`, `privateStatus`, `privateSettings`, `privateTolerance`, `PRIVATE_HELP_TEXT`, and the three Telegram-side adapters.

```kotlin
package fxbot

// ... framework imports as in Commands.kt

/** `/sell 10 EUR for RUB` — the amount's currency, then `for`, then the other leg. */
private const val EXAMPLE = "Tell me both currencies, like: /sell 10 EUR for RUB"

internal suspend fun handlePrivatePost(verb: Verb, update: ProcessedUpdate, bot: TelegramBot) {
    val chat = update.getChat()
    val user = update.getUser()
    val args = update.text.trim().split(Regex("\\s+")).drop(1)
    val command = verb.name.lowercase()
    if (args.size < 4 || !args[2].equals("for", ignoreCase = true)) {
        logCommand(command, "missing_args")
        message { EXAMPLE }.send(chat.id, bot)
        return
    }
    when (val result = Registry.interests.state(user.id, user.username, verb, args[0], args[1], args[3])) {
        is InterestResult.Rejected -> {
            logCommand(command, "rejected")
            message { result.reason }.send(chat.id, bot)
        }
        is InterestResult.Stated -> {
            logCommand(command, "stated")
            // At once, and never waiting for the batch: the counterparties found, and
            // where this is about to be shown.
            val text = renderStated(result)
            val buttons = statedButtons(result)
            val sent = message { text }
                .options { parseMode = ParseMode.HTML }
                .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
                .sendReturning(chat.id, bot)
                .getOrNull()
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    chat.id, id,
                    listOf(result.interest.refToken) + result.found.map { it.request.refToken },
                    listOf(result.interest.userId) + result.found.map { it.request.userId },
                    text, buttons,
                )
            }
            Registry.batcher.enqueueAnnouncement(user.id)
            Registry.batcher.enqueueAppeared(result.appeared)
        }
    }
}
```

`privateStatus` sends `renderStandings(Registry.interests.standings(user.id))`; `privateSettings` sends `"Your size tolerance is ${Registry.people.get(user.id).tolerancePct}%. Change it with /tolerance."`; `privateTolerance` sends `Registry.people.setTolerance(user.id, args.firstOrNull().orEmpty())`.

The three adapters, each a one-liner over the framework:

```kotlin
/**
 * Membership is probed per fan-out and the answer is used and discarded — no record of
 * which chats a person belongs to is written anywhere. Denies on any failure, like
 * `AdminCommands.isAdmin`: a network hiccup must never read as "probably a member".
 * Cancellation is not a failure and is rethrown.
 */
fun telegramMembership(bot: TelegramBot) = MembershipProbe { chatId, userId ->
    val member = try {
        getChatMember(userId).sendReturning(chatId, bot).getOrNull()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    when (member?.status) {
        "creator", "administrator", "member", "restricted" -> true
        else -> false
    }
}

/** Looked up live at give-up time, so what passes is current and nothing is stored (ADR 0007). */
fun telegramNames(bot: TelegramBot) = NameLookup { userId ->
    val chat = try {
        getChat().sendReturning(userId, bot).getOrNull()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    chat?.let { Handle(it.username, it.firstName ?: it.title ?: "this person") }
}
```

Check the exact `getChat`/`ChatMember` member names against the 9.6.0 sources before writing these two — the surrounding code (`AdminCommands.isAdmin`, `ChatMigration`) shows the house style for verifying a framework shape rather than guessing it. If `getChat` cannot be addressed by user id in this framework version, fall back to `getChatMember(userId).sendReturning(chatId, bot)` against a chat the two share — and if there is none, `NameLookup` returns null, which `GiveUpService` already treats as "no route".

The sink:

```kotlin
/** Sends what a flush produced and records every message against every token it names. */
fun telegramSink(bot: TelegramBot) = AnnouncementSink { announcements, pings ->
    for (a in announcements) {
        val sent = message { a.text }
            .options { parseMode = ParseMode.HTML }
            .inlineKeyboardMarkup { a.buttons.forEach { b -> b.label callback b.data; br() } }
            .sendReturning(a.chatId, bot)
            .getOrNull()
        sent?.messageId?.let { Registry.messages.record(a.chatId, it, a.refTokens, a.userIds, a.text, a.buttons) }
    }
    for (p in pings) {
        message { p.text }
            .inlineKeyboardMarkup { p.buttons.forEach { b -> b.label callback b.data; br() } }
            .send(p.userId, bot)
    }
}
```

A ping names nobody, so it is not recorded against anyone.

- [ ] **Step 5: Add the rendering**

Append to `Render.kt`:

```kotlin
/** The immediate private reply: what was found, and where this is about to be shown. */
fun renderStated(r: InterestResult.Stated): String {
    val text = StringBuilder("Noted: ${describe(r.interest)} (${r.interest.shortId}).")
    if (r.found.isEmpty()) {
        text.append("\nNobody matches yet on a no-names basis — you're waiting.")
    } else {
        text.append(if (r.found.size == 1) "\n1 person matches, no names either way:" else "\n${r.found.size} people match, no names either way:")
        for (c in r.found) text.append("\n• someone wants to ").append(describe(c.request))
        text.append("\nOffer to pass your name and I'll ask them the same.")
    }
    text.append(
        when (r.showings.size) {
            0 -> "\nI'm not showing this in any group — either we share none that swap this pair, or their admins turned that off."
            1 -> "\nI'll show this in 1 group shortly."
            else -> "\nI'll show this in ${r.showings.size} groups shortly."
        },
    )
    return text.toString()
}

fun statedButtons(r: InterestResult.Stated): List<Button> =
    r.found.flatMap { c ->
        listOf(
            Button("🤝 Pass my name (${describe(c.request)})", Cb.giveUp(r.interest.refToken, c.request.refToken)),
            Button("🚫 Not this one", Cb.decline(r.interest.refToken, c.request.refToken)),
        )
    } + Button("✖️ Cancel ${r.interest.shortId}", Cb.cancel(r.interest.refToken))

/** Each interest once, and where it still rests. A chat whose showing has lapsed is simply absent. */
fun renderStandings(standings: List<InterestStanding>): String {
    if (standings.isEmpty()) return "You have nothing waiting with me right now."
    val text = StringBuilder("Waiting with me:")
    for (s in standings) {
        text.append("\n• ").append(s.interest.shortId).append(' ').append(describe(s.interest))
        text.append(
            when (s.chatIds.size) {
                0 -> " — on a no-names basis only"
                1 -> " — on a no-names basis and in 1 group"
                else -> " — on a no-names basis and in ${s.chatIds.size} groups"
            },
        )
    }
    return text.toString()
}
```

A chat's own `/status` is unchanged and does not mark showings: provenance explains a message nobody typed, not a standing list.

- [ ] **Step 6: Add the three callbacks**

In `Callbacks.kt`, following the existing pattern exactly — every parameter nullable, the presser re-derived from `update.getUser().id`, `autoAnswer = false`:

```kotlin
@CommandHandler.CallbackQuery(["giveup"], autoAnswer = false)
suspend fun giveUpCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    if (a == null || b == null) return respond(ActionResult.Denied(BROKEN_BUTTON), update, bot)
    // Consent is read from the table, not from this payload: a hand-crafted `b` claiming
    // the other side agreed achieves nothing.
    when (val r = Registry.giveUp.offer(update.getUser().id, a, b)) {
        is GiveUpResult.Asked -> {
            logCommand("giveup_button", "asked")
            // Nothing about the presser reaches the peer here — side and stated amount only.
            message { "Someone matching your ${describe(r.mine)} has offered to pass their name. Pass yours back?" }
                .inlineKeyboardMarkup {
                    "🤝 Pass my name" callback Cb.giveUp(r.peerRefToken, r.myRefToken); br()
                    "🚫 No thanks" callback Cb.decline(r.peerRefToken, r.myRefToken)
                }
                .send(r.peerUserId, bot)
            answerCallbackQuery(update, bot, "I've asked them. I'll tell you if they agree.")
        }
        is GiveUpResult.Disclosed -> {
            logCommand("giveup_button", "disclosed")
            // Recorded in the message log so forgetting can redact them (ADR 0005).
            discloseTo(r.a, r.b, bot)
            discloseTo(r.b, r.a, bot)
        }
        is GiveUpResult.Recorded -> answerCallbackQuery(update, bot, r.text)
        is GiveUpResult.Refused -> answerCallbackQuery(update, bot, r.text)
    }
}

@CommandHandler.CallbackQuery(["decline"], autoAnswer = false)
suspend fun declineCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) { ... }

@CommandHandler.CallbackQuery(["restate"], autoAnswer = false)
suspend fun restateCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) { ... }
```

`discloseTo(to, other, bot)` sends `to.userId` a message naming `other.handle` with a Done button (`Cb.done(to.refToken, other.refToken)`), under `ParseMode.HTML`, and records it via `Registry.messages.record(to.userId, messageId, listOf(to.refToken, other.refToken), listOf(to.userId, other.userId), text, buttons)` — both people, so forgetting reaches it.

`restateCallback` re-derives the presser, requires them to own `a`, recomputes `residualOf` from the two closed rows at press time, and then:
- `restateGoesPrivate(mine)` true → `Registry.interests.state(...)` with the verb recovered by `verbFor(mine.side, mine.statedCurrency, mine.pair)` and the residual as the amount, then `Registry.batcher.enqueueAnnouncement` / `enqueueAppeared` — it joins the current batch and pays the same cap as any other statement.
- false → `Registry.service.post(mine.chatId, ...)` with the same verb and the residual amount, replying in that chat.

Both paths create a request with a NEW short id; neither touches the original's stated amount.

Add a small helper beside `respond` so the three handlers share one way of answering:

```kotlin
private suspend fun answerCallbackQuery(update: ProcessedUpdate, bot: TelegramBot, text: String) {
    (update as? CallbackQueryUpdate)?.callbackQuery?.id?.let {
        answerCallbackQuery(it).options { this.text = text; showAlert = true }.send(update.getUser().id, bot)
    }
}
```

- [ ] **Step 7: Wire it up**

`Registry.kt` gains the six new fields. `Main.kt` builds them after `bot` exists (the three adapters need it), which means moving the `Registry` assignments that depend on the bot to just after the `TelegramBot { }` block:

```kotlin
    Registry.people = PersonSettingsRepository(ds, crypto, db = db)
    Registry.giveUps = NameGiveUpRepository(ds, crypto, db = db)
    Registry.pending = PendingAnnouncementRepository(ds, crypto, db = db)
    Registry.buttons = ButtonService(Registry.messages, Registry.requests)
    Registry.lifecycle = LifecycleService(Registry.requests, Registry.settings, Registry.rates)
    // ... after the bot is constructed:
    Registry.interests = InterestService(
        Registry.requests, Registry.settings, Registry.people, Registry.rates, rateClient,
        Registry.giveUps, Registry.pending, telegramMembership(bot),
    )
    Registry.giveUp = GiveUpService(Registry.requests, Registry.giveUps, telegramNames(bot))
    Registry.batcher = AnnouncementBatcher(
        Registry.requests, Registry.settings, Registry.pending, Registry.interests, Registry.rates,
        telegramSink(bot), this, // `this` is main()'s coroutineScope
    )
    // A restart inside the window must not leave showings resting in chats that were
    // never told. Re-rendered from live state, never replayed.
    launch { runCatching { Registry.batcher.flushAllOnStartup() } }
```

Extend `setMyCommands` with a private-scope list (`BotCommandScope.AllPrivateChats`) describing the private meanings, and add the private `HELP_TEXT` counterpart:

```
    /sell 10 EUR for RUB — you're handing over 10 EUR
    /buy 10 RUB for EUR — you want to receive 10 RUB
    /tolerance 5 — how much you'll accept being left with, 1-100
    /status — your interests and where each still rests
    /cancel a1 — withdraw an interest, every showing with it
    /done a1 @anna — you two swapped
    /settings — your size tolerance
    /forget — erase what I hold about you (add 'all' to reach every group)
```

- [ ] **Step 8: Run the tests and commit**

Run: `./gradlew test`

```bash
git add src/main/kotlin/fxbot src/test/kotlin/fxbot/PrivateCommandTest.kt
git commit -m "feat: state, work and close an interest from a private chat"
```

---

### Task 14: Forgetting, and the housekeeping the new tables need

**Files:**
- Modify: `src/main/kotlin/fxbot/ForgetService.kt`, `src/main/kotlin/fxbot/LifecycleCommands.kt` (the `/forget` handler)
- Modify: `src/main/kotlin/fxbot/Tasks.kt`, `src/main/kotlin/fxbot/Main.kt`
- Test: `src/test/kotlin/fxbot/ForgetCommandTest.kt`, `src/test/kotlin/fxbot/ForgetServiceTest.kt`, `src/test/kotlin/fxbot/TasksTest.kt`

**Interfaces:**
- Consumes: `PersonSettingsRepository.delete`, `NameGiveUpRepository.deleteFor`/`dropClosed`, `PendingAnnouncementRepository.deleteFor`/`dropClosed`/`dropOlderThan`, `RequestRepository.expireDue` (now returning tokens) and `noNamesPairs`.
- Produces:
  ```kotlin
  class ForgetService(requests, log, people, giveUps, pending, clock = Clock.systemUTC()) {
      fun plan(userId: Long, chatId: Long?, personal: Boolean): ForgetPlan
  }
  class Housekeeping(requests, settings, rates, log, giveUps, pending, clock = Clock.systemUTC(), onClosed: suspend (List<String>) -> Unit = {}) {
      suspend fun sweep(): Int
      suspend fun refreshRates()
  }
  ```
  `sweep` becomes `suspend` so it can hand the lapsed tokens to the message-editing pass. `Tasks.startScheduler` already wraps the rate refresh in `runBlocking`; wrap the sweep the same way.

`/forget` has three shapes, and this task pins all three:
- In a group: that group alone. It may delete one showing of an interest whose siblings live on — forgetting removes a record, it does not withdraw an interest.
- Privately, plain: the person's no-names requests, their `person_settings` row, their `name_give_up` rows, their pending announcements, and redaction of give-up messages that named someone.
- Privately, `all`: the above **and** showings in every chat, which `deleteFor(userId, chatId = null)` already does.

- [ ] **Step 1: Write the failing tests**

Append to `ForgetCommandTest.kt`:

```kotlin
    "plain /forget in a private chat erases the no-names side and everything personal" {
        val f = CommandFixture("privforget")
        f.requests.create(NO_NAMES_CHAT_ID, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.people.save(PersonSettings(1L, 40))
        f.giveUps.record("tokA", "tokB", 1L, Stance.OFFERED)
        f.pending.add(-100L, "i1", 1L)
        val sent = mutableListOf<Sent>()
        forget(updateFor(555L, ChatType.Private, "/forget"), recordingBot(sent))

        f.requests.resting(NO_NAMES_CHAT_ID) shouldHaveSize 0
        f.people.get(1L).tolerancePct shouldBe 20
        f.giveUps.stanceOf("tokA", "tokB") shouldBe null
        f.pending.all() shouldHaveSize 0
        // A showing in a group is a record in that group; plain private /forget does not reach it.
        f.requests.resting(-100L) shouldHaveSize 1
    }
    "/forget all reaches the groups too, and still erases everything personal" {
        val f = CommandFixture("allforget")
        f.requests.create(NO_NAMES_CHAT_ID, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.people.save(PersonSettings(1L, 40))
        val sent = mutableListOf<Sent>()
        forget(updateFor(555L, ChatType.Private, "/forget all"), recordingBot(sent))
        f.requests.resting(NO_NAMES_CHAT_ID) shouldHaveSize 0
        f.requests.resting(-100L) shouldHaveSize 0
        f.people.get(1L).tolerancePct shouldBe 20
    }
    "/forget in a group leaves the person's own settings and no-names side alone" {
        val f = CommandFixture("groupforgetscope")
        f.requests.create(NO_NAMES_CHAT_ID, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.requests.create(-100L, 1L, "a", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        f.people.save(PersonSettings(1L, 40))
        val sent = mutableListOf<Sent>()
        forget(updateFor(-100L, ChatType.Group, "/forget"), recordingBot(sent))
        f.requests.resting(-100L) shouldHaveSize 0
        f.requests.resting(NO_NAMES_CHAT_ID) shouldHaveSize 1
        f.people.get(1L).tolerancePct shouldBe 40
    }
```

`CommandFixture` gains `people`, `giveUps` and `pending`, all assigned into `Registry`, and its `Registry.forget` gets the new constructor.

Append to `TasksTest.kt`:

```kotlin
    "the refresh also prices a pair only the no-names side is using" {
        val ds = memDataSource("refreshnonames")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0, ZoneOffset.UTC)
        val requests = RequestRepository(ds, crypto, clock)
        requests.create(NO_NAMES_CHAT_ID, 1L, "bob", Side.OFFER, "CHF", BigDecimal("10"), CurrencyPair("CHF", "JPY"), 7, "i1")
        val rateRepo = RateRepository(ds)
        val bases = mutableListOf<String>()
        val client = RateClient(HttpClient(MockEngine { request ->
            bases += request.url.encodedPath.substringAfterLast('/')
            respond(BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val settings = ChatSettingsRepository(ds, crypto, clock).also { it.save(ChatSettings(-100L, EURRUB, 20, 7)) }
        housekeepingWith(ds, crypto, clock, requests, settings, RateService(client, rateRepo, clock)).refreshRates()
        bases.toSet() shouldBe setOf("EUR", "CHF")
    }
    "the sweep drops give-up rows and pending announcements whose requests have closed" {
        val ds = memDataSource("sweepdrops")
        migrate(ds)
        val crypto = testCrypto()
        val clock = Clock.fixed(T0.plusSeconds(8 * 86_400), ZoneOffset.UTC)
        val requests = RequestRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC))
        val a = requests.create(-100L, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1"), EURRUB, 7, "i1")
        val giveUps = NameGiveUpRepository(ds, crypto, clock).also { it.record(a.refToken, "gone", 1L, Stance.OFFERED) }
        val pending = PendingAnnouncementRepository(ds, crypto, Clock.fixed(T0, ZoneOffset.UTC)).also { it.add(-100L, "i1", 1L) }
        val lapsed = mutableListOf<String>()
        val hk = housekeepingWith(ds, crypto, clock, requests, giveUps = giveUps, pending = pending) { lapsed += it }
        hk.sweep() shouldBe 1
        lapsed shouldBe listOf(a.refToken)
        giveUps.stanceOf(a.refToken, "gone") shouldBe null
        pending.all() shouldHaveSize 0
    }
```

Add a `housekeepingWith(...)` helper in that file so the two tests are not two walls of constructor arguments.

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'fxbot.ForgetCommandTest' --tests 'fxbot.TasksTest'`
Expected: FAIL — constructor arity mismatches on `ForgetService` and `Housekeeping`.

- [ ] **Step 3: Widen the forget plan**

```kotlin
/**
 * Erases what is stored, and works out what can still be cleaned up in the chat. A
 * message that named other people is redacted rather than deleted (ADR 0005).
 *
 * [personal] covers what belongs to the person rather than to a chat: their own size
 * tolerance, their give-up consents, and their pending announcements. It is true for
 * both private forms and false for the per-chat one, because `/forget` typed in a group
 * still means that group alone.
 */
class ForgetService(
    private val requests: RequestRepository,
    private val log: MessageLogRepository,
    private val people: PersonSettingsRepository,
    private val giveUps: NameGiveUpRepository,
    private val pending: PendingAnnouncementRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun plan(userId: Long, chatId: Long?, personal: Boolean): ForgetPlan {
        val messages = log.messagesForUser(userId, chatId)
        val (redact, delete) = messages.partition { log.namesOthers(it.messageId, it.chatId, userId) }
        val removed = requests.deleteFor(userId, chatId).size
        if (personal) {
            people.delete(userId)
            giveUps.deleteFor(userId)
            pending.deleteFor(userId)
        }
        log.forget(userId, chatId)
        return ForgetPlan(removed, delete, redact)
    }
}
```

In `LifecycleCommands.kt`'s `forget` handler, replace the plan call:

```kotlin
    val private = chat.type == ChatType.Private
    val scope = when {
        global -> null                       // every chat, plus the no-names side
        private -> NO_NAMES_CHAT_ID          // the person's own side of the bot
        else -> chat.id                      // this group alone
    }
    val plan = Registry.forget.plan(user.id, scope, personal = private || global)
```

and let plain `/forget` through in a private chat by replacing the `else if (!inGroupOrExplain(update, bot)) return` branch with nothing — the three shapes above are now all valid. Keep the `/forget all`-from-a-group refusal exactly as it is: from a group it would silently reach into the caller's other groups.

- [ ] **Step 4: Extend housekeeping**

```kotlin
class Housekeeping(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
    private val log: MessageLogRepository,
    private val giveUps: NameGiveUpRepository,
    private val pending: PendingAnnouncementRepository,
    private val clock: Clock = Clock.systemUTC(),
    /** Hands the lapsed tokens to the message-editing pass. Defaulted so tests need no bot. */
    private val onClosed: suspend (List<String>) -> Unit = {},
) {
    /**
     * Lapses what is past its time in force, prunes the message record, and drops the rows
     * that only made sense while their requests were resting. Counts only — no chat or
     * request identity belongs in this log line.
     */
    suspend fun sweep(): Int {
        val expired = requests.expireDue(clock.instant())
        val pruned = log.prune(clock.instant().minus(RETENTION))
        val staleGiveUps = giveUps.dropClosed()
        val stalePending = pending.dropClosed() + pending.dropOlderThan(clock.instant().minus(PENDING_MAX_AGE))
        logger.info("sweep: expired=${expired.size} pruned=$pruned giveUps=$staleGiveUps pending=$stalePending")
        if (expired.isNotEmpty()) onClosed(expired)
        return expired.size
    }

    /**
     * Chat pairs AND the pairs resting on a no-names basis — a pair no chat uses would
     * otherwise never get a reference rate. The feed is per base currency, so the cost is
     * one GET per distinct base per day, not per pair.
     */
    suspend fun refreshRates() = rates.refresh(settings.allPairs() + requests.noNamesPairs())
}

private val PENDING_MAX_AGE: Duration = Duration.ofHours(1)
```

In `startScheduler`, wrap the sweep like the refresh already is:

```kotlin
    val sweep = Tasks.recurring("sweep", Schedules.fixedDelay(Duration.ofHours(24)))
        .execute { _, _ -> runBlocking { housekeeping.sweep() } }
```

In `Main.kt`, build `Housekeeping` with the two new repositories and wire `onClosed` to the button pass — which means constructing it after the bot exists:

```kotlin
    val housekeeping = Housekeeping(
        Registry.requests, Registry.settings, Registry.rates, Registry.messages,
        Registry.giveUps, Registry.pending,
    ) { tokens -> Registry.buttons.refreshFor(tokens, bot) }
    startScheduler(ds, housekeeping)
```

- [ ] **Step 5: Run the tests and the whole suite**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Update the documentation**

- `README.md`: add the private surface (`/sell 10 EUR for RUB`, `/tolerance`, `/status`, `/cancel`, `/done`, `/settings` privately) and the `/fanout` admin command to whatever command list it carries.
- `docs/decisions-log.md`: one line pointing at ADR 0006 and ADR 0007 and this plan, in the file's existing style.

Do **not** edit `CONTEXT.md` — the spec says its vocabulary already covers this work.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: forget the private side, and sweep what the new tables leave behind"
```

---

## Self-review

Run this checklist against the spec after the last task, before offering the branch for review.

**Spec coverage** — every section of `2026-09-06-private-interests-design.md` maps to a task:

| Spec section | Task |
|---|---|
| Per-side residual, inclusive boundary, no combinations | 2 |
| Showings matched at the chat's tolerance, no-names at each person's | 2, 4, 8 |
| `setTolerance` reworded | 2 |
| The two surfaces never meet | 3 (sentinel), 8 (test) |
| Suppressed pairings (live showing, decline) | 6, 8 |
| Residuals after a done, restate button | 11, 12, 13 |
| Stating an interest privately, every command's private meaning | 13 |
| Posting: canonical pair, pair pricing, create, chat enumeration, immediate reply | 8, 13 |
| Batching, the five-interest cap, the tumbling window, per-recipient pings | 8, 9 |
| Pending announcements persisted and re-rendered | 6, 9 |
| Fan-out per chat, `/fanout` | 5 |
| Name give-up, handle requirement, live lookup, decline, forged payload | 6, 10, 13 |
| Closing: interest-wide done/cancel, expiry per showing, reopen siblings | 3, 12 |
| Message editing with a status line and a rebuilt keyboard | 7, 12 |
| Storage: V3, `interest_token`, three tables, two payload fields | 1, 5, 7 |
| Privacy and forgetting | 14 |
| Testing (every bullet) | the task that owns the behaviour |

**Placeholder scan** — there are no "add appropriate error handling", "TBD", or "similar to Task N" steps; every code step carries the code. The two places a task says "check the framework shape before writing" (Task 13's `telegramNames` and the `CallbackQueryUpdate` test fixture) are deliberate: guessing a library signature is exactly what this codebase's comments repeatedly warn against, and both name the fallback if the shape is not what is expected.

**Type consistency** — the names used across tasks: `NO_NAMES_CHAT_ID`, `interestToken`, `closeInterest`, `reopenInterest`, `countOpenInterests`, `noNamesPairs`, `siblings`, `expireDue` (returning `List<String>` from Task 3 onward), `allChats`, `fanOut`, `logged`/`LoggedMessage`, `canonicalPair`, `MembershipProbe`, `NameLookup`, `AnnouncementSink`, `ShownInterest`, `Announcement`, `Ping`, `CounterpartyAppeared`, `InterestStanding`, `Stance`, `residualOf`, `restateGoesPrivate`, `RestateOffer`, `refreshFor`. Each is introduced in exactly one task and used under that spelling everywhere after.

**Deletions** — Task 12 removes `RequestRepository.markDone`, `DoneOutcome` and `ButtonService.stripFor`. Nothing later reintroduces them.

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-06-private-interests.md`. Two execution options:

**1. Subagent-Driven (recommended)** — a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using `superpowers:executing-plans`, batch execution with checkpoints.

Which approach?
