# Named Counterparties and Confirmed Dones Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Name every counterparty the moment they are suggested, and make every `/done` a question the counterparty must answer before anything closes.

**Architecture:** Anonymity and the two-sided name give-up are deleted outright — service, repository, table, callbacks, buttons and their tests. In their place, `LifecycleService.done` stops closing anything: it resolves the pairing, guards it, and returns an `ActionResult.Asked` carrying a question for the counterparty, delivered to wherever that counterparty spoke to the bot. A `yes` callback closes both interests through the existing `closeBothWhole`; a `no` closes nothing and increments a per-pairing refusal counter that blocks a third ask. No table records an outstanding ask — the two ref tokens on the buttons plus live rows are enough.

**Tech Stack:** Kotlin/JVM (toolchain 25), `eu.vendeli:telegram-bot` 9.6.0 (+ `ktnip` KSP), Exposed 1.5.0 (core + jdbc only), H2 2.4.240 with `CIPHER=AES` in PostgreSQL mode, Flyway 11.8.2, HikariCP, db-scheduler 16.12.0, Ktor client CIO, Google Tink 1.18.0, Kotest 6.2.3.

**Spec:** `docs/superpowers/specs/2026-09-07-named-and-confirmed-design.md`
**Glossary:** `CONTEXT.md` — its vocabulary is binding in code, comments and user strings. Task 11 amends it; until then, follow the spec's *Vocabulary* section.
**Decisions:** `docs/adr/0001`–`0007`. This plan supersedes ADR 0007 and writes ADR 0008 and 0009.

**Branch:** all of this lands on `private-interests`, amending the existing pull request. Nothing here has ever run outside a development machine, which is why `V3__private_interests.sql` is edited in place rather than superseded by a `V4`.

## Global Constraints

- Kotlin `2.4.10`, KSP `2.3.10`, JVM toolchain **25** (BellSoft), Gradle/shadow `9.6.1`. Do not change any of these.
- Pinned versions stay exactly as `gradle/libs.versions.toml` has them. **No new dependency may be added by any task in this plan.** Exposed is `exposed-core` + `exposed-jdbc` only — never `exposed-migration-jdbc`, `exposed-java-time`, or `exposed-kotlin-datetime`.
- **Flyway owns the schema; Exposed is a query layer.** Schema changes go in `V3__private_interests.sql` (edited in place — see *Branch* above) and are mirrored by hand in `Tables.kt`. Never call `SchemaUtils.create`.
- **H2 runs in PostgreSQL compatibility mode.** Schema columns are `TEXT`, `BYTEA`, `BIGINT`, `INT`, `TIMESTAMP` — never a guessed `CHAR(n)` width, never `BLOB`.
- **Vocabulary is binding** (`CONTEXT.md`): *interest*, *showing*, *residual*, *size tolerance*, *counterparty*, *resting*, *notional*, *time in force*, *done*, *confirmation*, `Side.BID` / `Side.OFFER`. The words **order**, **book**, **fill**, **execution**, **pool**, **market**, **dark pool**, **reveal**, **match** (as a noun for a pairing) must not appear in identifiers, comments, or user strings. As of this plan **no-names basis**, **name give-up** and every `noNames` identifier are gone too (Task 1).
- All user-facing strings are **plain English** — never `notional`, `bid`, `offer`, `residual` in a chat message.
- Money is always `BigDecimal`, never `Double`, serialized as a JSON **string**.
- Everything identifying is either a sealed Tink AEAD payload or a keyed HMAC ref (`crypto.ref`). Plaintext columns are allowed only for random tokens (`ref_token`, `interest_token`, `peer_ref_token`) and public data (`fx_rate`).
- `callback_data` must stay ≤ 64 bytes and is never trusted: every callback re-derives the presser from `callback_query.from.id` and re-authorizes against the database. A ref token is 22 characters, so `yes?a=<22>&b=<22>` is 53 bytes.
- Logging: counts and fixed outcome labels only. Never a chat id, user id, username, amount, or message text. Use `logCommand(command, outcome)` on the command surface.
- The sentinel `chatId = 0` means "no chat" — Telegram never issues 0.
- Run `./gradlew test` before every commit. Commit messages are Conventional Commits, and end with the `Co-Authored-By` trailer this repository already uses.

## Interpretation notes (ambiguities resolved once, here)

1. **"The ask goes wherever the counterparty spoke."** A row whose `interestToken` is non-null was created from an interest stated privately — including its showings in groups. So *spoke privately* is `interestToken != null || chatId == NO_CHAT_ID`, and it is the same predicate `restateGoesPrivate` already uses. Task 5 names it `Request.spokePrivately()` and both callers share it. A private chat's id **is** the person's user id, which this codebase already relies on (`telegramGiveUpDied`).

2. **What "answered privately, wherever the counterparty was found" costs.** A person who states an interest privately must hear about counterparties found in their *showings* too, not just bot-wide — otherwise a muted group loses them a swap. So `InterestResult.Stated` carries one `ShownInterest` per row (the chatless row first, then each showing), and the private reply lists all of them (Task 8). The chat announcement still goes out unchanged: that is how somebody who typed in the group hears, in the group.

3. **Who is offered the residual after a confirmed done.** Both sides. The confirmer gets it on the reply to their own press (as today); the declarer gets it on the `Notice` the same press sends them. Only one of the two can have a residual at a time (the smaller side has none), so at most one of the two messages carries the button.

4. **No chat titles.** The spec's artwork says things like "He's in Belgrade Expats". The bot stores no chat title and this plan does not start storing one; the private reply says how many groups a showing rests in, as `renderStandings` already does.

---

## File Structure

**New files**

- `src/main/kotlin/fxbot/DoneRefusalRepository.kt` — the per-pairing refusal count, one direction only.
- `docs/adr/0008-counterparties-are-named-on-sight.md`
- `docs/adr/0009-a-done-is-confirmed-by-the-counterparty.md`

**Deleted files**

- `src/main/kotlin/fxbot/GiveUpService.kt` (its `Handle` and `NameLookup` move to `Render.kt` first).
- `src/main/kotlin/fxbot/NameGiveUpRepository.kt`
- `src/test/kotlin/fxbot/GiveUpServiceTest.kt`
- `src/test/kotlin/fxbot/NameGiveUpRepositoryTest.kt`

**Modified files**

`Request.kt`, `Render.kt`, `Tables.kt`, `V3__private_interests.sql`, `LifecycleService.kt`, `LifecycleCommands.kt`, `Callbacks.kt`, `Commands.kt`, `PrivateCommands.kt`, `InterestService.kt`, `RequestService.kt`, `AnnouncementBatcher.kt`, `Matcher.kt`, `RequestRepository.kt`, `ForgetService.kt`, `Tasks.kt`, `Registry.kt`, `Main.kt`, `CONTEXT.md`, `docs/adr/0007-no-names-working-and-mutual-name-give-up.md`.

**Tests** mirror main under `src/test/kotlin/fxbot/`: new `DoneRefusalRepositoryTest`; extended `SchemaDriftTest`, `LifecycleServiceTest`, `LifecycleCommandTest`, `RenderTest`, `PrivateCommandTest`, `InterestServiceTest`, `MatcherTest`, `RequestServiceTest`, `AnnouncementBatcherTest`, `ForgetCommandTest`, `TasksTest`.

---

### Task 1: Retire the "no-names" vocabulary

The spec deletes **No-names basis** from `CONTEXT.md`, and the Global Constraints make the glossary binding on identifiers. Rename before any new code is written, so nothing later is authored against a dead word. This task is mechanical and changes no behaviour.

**Files:**
- Modify: `src/main/kotlin/fxbot/Request.kt`, `InterestService.kt`, `RequestRepository.kt`, and every file naming the constants (a rename sweep).
- Test: the whole suite — this task's proof is that it still passes.

**Interfaces:**
- Consumes: nothing.
- Produces: `const val NO_CHAT_ID = 0L` (was `NO_NAMES_CHAT_ID`), `const val NO_CHAT_TIF_DAYS = 7` (was `NO_NAMES_TIF_DAYS`), `RequestRepository.noChatPairs(): Set<CurrencyPair>` (was `noNamesPairs`). Every later task uses the new names.

- [ ] **Step 1: Sweep the identifiers**

```bash
cd /home/finkel/work_self/exchange-bot
grep -rl 'NO_NAMES_CHAT_ID\|NO_NAMES_TIF_DAYS\|noNamesPairs' src | \
  xargs sed -i 's/NO_NAMES_CHAT_ID/NO_CHAT_ID/g; s/NO_NAMES_TIF_DAYS/NO_CHAT_TIF_DAYS/g; s/noNamesPairs/noChatPairs/g'
```

- [ ] **Step 2: Fix the doc comments the sweep did not touch**

In `Request.kt`, the constant's comment becomes:

```kotlin
/**
 * The chat id a request with no chat behind it carries. Telegram never issues 0, and
 * `Matcher` already filters on `chatId ==`, so the two surfaces cannot meet by accident.
 */
const val NO_CHAT_ID = 0L
```

In `InterestService.kt`:

```kotlin
/** An interest worked with no chat behind it has no chat to set a time in force, so it uses the default. */
const val NO_CHAT_TIF_DAYS = 7
```

Then read every remaining hit and rewrite the prose:

```bash
grep -rn 'no-names\|no names' src docs/adr/0006-size-tolerance-is-a-per-side-residual.md
```

Every occurrence in `src/` becomes wording that names the thing without the retired term — "with no chat behind it", "bot-wide", or "with me" in user-facing strings. Two user-facing strings must change here, because they are the only ones a person reads:

- `Render.renderStated`: `"\nNobody matches yet on a no-names basis — you're waiting."` → `"\nNobody matches yet — you're waiting."` (Task 8 rewrites the rest of this function.)
- `Render.renderStandings`: the three-branch `when` becomes

```kotlin
        text.append(
            when (s.chatIds.size) {
                0 -> " — waiting with me"
                1 -> " — waiting with me and in 1 group"
                else -> " — waiting with me and in ${s.chatIds.size} groups"
            },
        )
```

Leave `docs/adr/0007` alone — Task 11 supersedes it, and an ADR records what was decided at the time.

- [ ] **Step 3: Run the whole suite**

Run: `./gradlew test`
Expected: PASS. Any failure here is a missed rename, not a behaviour change.

- [ ] **Step 4: Commit**

```bash
git add -A src CONTEXT.md
git commit -m "refactor: name the chatless sentinel for what it is, not for anonymity"
```

---

### Task 2: A name for somebody with no handle

`mention()` already renders a `tg://user?id=` link for a person with no `@username`, but labels it `"this person"`. The spec keeps the live lookup as the one fallback the stored row cannot supply — a display name. This task moves `Handle`/`NameLookup` out of the doomed `GiveUpService.kt` and introduces the one-call-site-per-message book that feeds `mention`.

**Files:**
- Modify: `src/main/kotlin/fxbot/Render.kt`, `GiveUpService.kt` (delete the two moved declarations), `Registry.kt`, `Main.kt`, `AnnouncementBatcher.kt`, `Commands.kt`, `PrivateCommands.kt`, `Callbacks.kt`
- Test: `src/test/kotlin/fxbot/RenderTest.kt`

**Interfaces:**
- Consumes: `mention(username, userId, displayName)`, `Request`.
- Produces, all in `Render.kt`:
  - `data class Handle(val username: String?, val displayName: String)`
  - `fun interface NameLookup { suspend fun handleFor(userId: Long): Handle? }`
  - `@JvmInline value class NameBook(private val byUserId: Map<Long, String>)` with `fun label(r: Request): String` and `companion object { val EMPTY: NameBook }`
  - `suspend fun nameBookFor(requests: List<Request>, names: NameLookup): NameBook`
  - `internal fun mentionOf(r: Request, book: NameBook = NameBook.EMPTY): String`
  - `internal fun plainName(r: Request, book: NameBook = NameBook.EMPTY): String`
  - `renderSuggestions`, `renderStatus`, `renderAnnouncement`, `suggestionButtons`, `announcementButtons` each gain a trailing `book: NameBook = NameBook.EMPTY` parameter.
  - `Registry.names: NameLookup`

- [ ] **Step 1: Write the failing test**

Append to `src/test/kotlin/fxbot/RenderTest.kt` (inside the existing `StringSpec` block):

```kotlin
    "a stored handle beats a looked-up display name, and no lookup is made for it" {
        val r = req(userId = 7L, username = "bob")
        var lookups = 0
        val book = nameBookFor(listOf(r), NameLookup { lookups++; Handle(null, "Robert") })
        mentionOf(r, book) shouldBe "@bob"
        lookups shouldBe 0
    }

    "somebody with no handle is a link over the display name the lookup gave"  {
        val r = req(userId = 7L, username = null)
        val book = nameBookFor(listOf(r), NameLookup { Handle(null, "Boris <the> Great") })
        mentionOf(r, book) shouldBe """<a href="tg://user?id=7">Boris &lt;the&gt; Great</a>"""
    }

    "an unreachable person is still a link, labelled the way they always were" {
        val r = req(userId = 7L, username = null)
        val book = nameBookFor(listOf(r), NameLookup { null })
        mentionOf(r, book) shouldBe """<a href="tg://user?id=7">this person</a>"""
        plainName(r, book) shouldBe "this person"
    }
```

Add this helper at the top of the file, beside its existing private helpers:

```kotlin
private fun req(userId: Long, username: String?) = Request(
    refToken = "t$userId", chatId = -100L, userId = userId, username = username,
    shortId = "a1", side = Side.OFFER, statedCurrency = "EUR",
    statedAmount = java.math.BigDecimal("100"), pair = CurrencyPair("EUR", "RUB"),
    state = RequestState.OPEN,
    createdAt = java.time.Instant.EPOCH, expiresAt = java.time.Instant.EPOCH.plusSeconds(60),
)
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew test --tests 'fxbot.RenderTest'`
Expected: FAIL — `Unresolved reference: nameBookFor` (and `NameBook`, `plainName`).

- [ ] **Step 3: Move the two declarations and add the book**

Cut `Handle` and `NameLookup` out of `GiveUpService.kt` verbatim, and paste them into `Render.kt` directly above `mention`, with the comment updated (nothing about them is about anonymity any more):

```kotlin
/** What Telegram knows about somebody right now, asked at render time and never stored. */
data class Handle(val username: String?, val displayName: String)

/** Looks somebody up live. The Telegram layer answers with `getChat`; a test answers with a map. */
fun interface NameLookup {
    suspend fun handleFor(userId: Long): Handle?
}
```

Then, below `mention`:

```kotlin
/**
 * The display names for the people a message is about to name who carry no stored handle.
 * Empty is always a valid answer: a person nothing could be looked up for keeps the label
 * they have always had, and their `tg://user?id=` link still works.
 */
@JvmInline
value class NameBook(private val byUserId: Map<Long, String>) {
    fun label(r: Request): String = byUserId[r.userId] ?: "this person"

    companion object {
        val EMPTY = NameBook(emptyMap())
    }
}

/**
 * One lookup per person who needs one, and none at all for anybody whose handle is already
 * sealed into their request — the stored username is the source, and this is the fallback
 * for the one thing it cannot supply.
 */
suspend fun nameBookFor(requests: List<Request>, names: NameLookup): NameBook {
    val need = requests.filter { it.username == null }.map { it.userId }.distinct()
    if (need.isEmpty()) return NameBook.EMPTY
    return NameBook(need.mapNotNull { id -> names.handleFor(id)?.let { id to it.displayName } }.toMap())
}

/** How a request's author is named everywhere: the handle, or a link over their display name. */
internal fun mentionOf(r: Request, book: NameBook = NameBook.EMPTY): String =
    mention(r.username, r.userId, r.username ?: book.label(r))

/** The same person on a button, where Telegram allows no markup at all. */
internal fun plainName(r: Request, book: NameBook = NameBook.EMPTY): String = r.username ?: book.label(r)
```

Give `renderSuggestions`, `renderStatus`, `renderAnnouncement`, `suggestionButtons` and `announcementButtons` a trailing `book: NameBook = NameBook.EMPTY` parameter and pass it into every `mentionOf(...)` call inside them. In the two button builders, replace `c.request.username ?: "them"` with `plainName(c.request, book)`.

- [ ] **Step 4: Wire a lookup into the Registry and the call sites**

`Registry.kt` — add beside the other declarations:

```kotlin
    lateinit var names: NameLookup
```

`Main.kt` — replace the `Registry.giveUpService = ...` line's neighbourhood so the lookup is built once and shared:

```kotlin
    Registry.names = telegramNames(bot)
    Registry.giveUpService = GiveUpService(Registry.requests, Registry.giveUps, Registry.names)
```

`AnnouncementBatcher` gains a constructor parameter after `clock`:

```kotlin
    private val names: NameLookup = NameLookup { null },
```

and in `deliverLocked`, before building each `Announcement`, replace the `renderAnnouncement`/`announcementButtons` pair with:

```kotlin
                val book = nameBookFor(listOf(owner) + mine.flatMap { s -> s.found.map { it.request } }, names)
                announcements += Announcement(
                    chatId = chatId,
                    text = renderAnnouncement(mentionOf(owner, book), mine, status, book),
                    buttons = announcementButtons(mine, book),
```

Pass `Registry.names` at the construction site in `Main.kt` (positionally after the sink and scope arguments' existing order — name it `names = Registry.names` to be safe).

In `Commands.handlePost` (group branch) and `Commands.status`, and in `Callbacks.restateInChat`, build a book before rendering:

```kotlin
            val book = nameBookFor(
                listOf(result.request) + result.found.map { it.request }, Registry.names,
            )
            val text = renderSuggestions(result.found, result.status, book)
            val buttons = suggestionButtons(result.request, result.found, book)
```

`Commands.status` renders a chat's resting list, so its book is over that list:

```kotlin
    val resting = Registry.requests.resting(chat.id)
    val book = nameBookFor(resting, Registry.names)
    message { renderStatus(resting, user.id, book = book) }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "feat: name somebody who has no handle by their display name"
```

---

### Task 3: The `done_refusal` table

**Files:**
- Modify: `src/main/resources/db/migration/V3__private_interests.sql`, `src/main/kotlin/fxbot/Tables.kt`
- Test: `src/test/kotlin/fxbot/SchemaDriftTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object DoneRefusals : Table("done_refusal")` with `refToken`, `peerRefToken`, `refusals: Column<Int>`, `refusedAt: Column<Instant>`, primary key `(refToken, peerRefToken)`.

`name_give_up` stays in the migration for now; Task 9 removes it, once nothing reads it. Both tables coexisting is what keeps the build green in between.

- [ ] **Step 1: Write the failing test**

In `SchemaDriftTest.kt`, extend the `tables` array:

```kotlin
            val tables = arrayOf(
                Requests, ChatSettingsTable, FxRates, SentMessages, SentMessageRefs,
                PersonSettingsTable, NameGiveUps, PendingAnnouncements, DoneRefusals,
            )
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew test --tests 'fxbot.SchemaDriftTest'`
Expected: FAIL — `Unresolved reference: DoneRefusals`.

- [ ] **Step 3: Add the table to the migration**

Append to `src/main/resources/db/migration/V3__private_interests.sql`:

```sql
-- One row per pairing that was asked about and refused, keyed in ONE direction: ref_token
-- is the person who declared the done, peer_ref_token the person who said no. The
-- asymmetry of the key is what makes the block one-directional, with no column to express
-- it. Both are random tokens carrying no personal data. Rows die with their requests, so
-- this is never a durable record of who refused whom.
CREATE TABLE done_refusal (
    ref_token      TEXT      NOT NULL,
    peer_ref_token TEXT      NOT NULL,
    refusals       INT       NOT NULL,
    refused_at     TIMESTAMP NOT NULL,
    PRIMARY KEY (ref_token, peer_ref_token)
);
```

- [ ] **Step 4: Mirror it in Tables.kt**

Add below `NameGiveUps`:

```kotlin
object DoneRefusals : Table("done_refusal") {
    val refToken = text("ref_token")
    val peerRefToken = text("peer_ref_token")
    val refusals = integer("refusals")
    val refusedAt = timestamp("refused_at")
    override val primaryKey = PrimaryKey(refToken, peerRefToken)
}
```

- [ ] **Step 5: Run the test**

Run: `./gradlew test --tests 'fxbot.SchemaDriftTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/db/migration/V3__private_interests.sql src/main/kotlin/fxbot/Tables.kt src/test/kotlin/fxbot/SchemaDriftTest.kt
git commit -m "feat: a table for a refused done, counted one direction only"
```

---

### Task 4: DoneRefusalRepository

**Files:**
- Create: `src/main/kotlin/fxbot/DoneRefusalRepository.kt`
- Test: `src/test/kotlin/fxbot/DoneRefusalRepositoryTest.kt`

**Interfaces:**
- Consumes: `DoneRefusals`, `Requests`, `connectExposed`.
- Produces: `class DoneRefusalRepository(ds: DataSource, clock: Clock = Clock.systemUTC(), db: Database = connectExposed(ds))` with
  - `fun record(declarerToken: String, refuserToken: String): Int` — increments and returns the new count
  - `fun count(declarerToken: String, refuserToken: String): Int`
  - `fun deleteFor(tokens: List<String>): Int`
  - `fun dropClosed(): Int`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/fxbot/DoneRefusalRepositoryTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

private val EURRUB = CurrencyPair("EUR", "RUB")

private class RefusalFixture(name: String) {
    val ds = memDataSource(name).also { migrate(it) }
    val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto)
    val refusals = DoneRefusalRepository(ds)

    fun rest(userId: Long, side: Side) =
        requests.create(NO_CHAT_ID, userId, null, side, "EUR", BigDecimal("1000"), EURRUB, 7, "i$userId")
}

class DoneRefusalRepositoryTest : StringSpec({
    "an unrecorded pairing has no refusals" {
        val f = RefusalFixture("refusal_none")
        f.refusals.count("a", "b") shouldBe 0
    }

    "each refusal counts once and the count is returned" {
        val f = RefusalFixture("refusal_count")
        f.refusals.record("a", "b") shouldBe 1
        f.refusals.record("a", "b") shouldBe 2
        f.refusals.count("a", "b") shouldBe 2
    }

    "the count is one-directional" {
        val f = RefusalFixture("refusal_direction")
        f.refusals.record("a", "b")
        f.refusals.record("a", "b")
        f.refusals.count("b", "a") shouldBe 0
    }

    "a row dies when either request stops resting" {
        val f = RefusalFixture("refusal_dropclosed")
        val a = f.rest(1L, Side.OFFER)
        val b = f.rest(2L, Side.BID)
        f.refusals.record(a.refToken, b.refToken)
        f.refusals.dropClosed() shouldBe 0
        f.requests.transition(b.refToken, RequestState.OPEN, RequestState.CANCELLED) shouldBe true
        f.refusals.dropClosed() shouldBe 1
        f.refusals.count(a.refToken, b.refToken) shouldBe 0
    }

    "forgetting a person's tokens erases their rows in both directions" {
        val f = RefusalFixture("refusal_forget")
        f.refusals.record("mine", "theirs")
        f.refusals.record("theirs", "mine")
        f.refusals.deleteFor(listOf("mine")) shouldBe 2
        f.refusals.count("mine", "theirs") shouldBe 0
        f.refusals.count("theirs", "mine") shouldBe 0
    }

    "forgetting nothing deletes nothing" {
        val f = RefusalFixture("refusal_forget_empty")
        f.refusals.record("a", "b")
        f.refusals.deleteFor(emptyList()) shouldBe 0
        f.refusals.count("a", "b") shouldBe 1
    }
})
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `./gradlew test --tests 'fxbot.DoneRefusalRepositoryTest'`
Expected: FAIL — `Unresolved reference: DoneRefusalRepository`.

- [ ] **Step 3: Write the repository**

Create `src/main/kotlin/fxbot/DoneRefusalRepository.kt`:

```kotlin
package fxbot

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import javax.sql.DataSource

/**
 * How many times one person's declared done about one pairing has been refused by the
 * other. Keyed in one direction — [DoneRefusals.refToken] is the declarer's request,
 * [DoneRefusals.peerRefToken] the refuser's — so the block that a second refusal earns
 * sits on whoever kept asking, and never on the person being pestered.
 *
 * Nothing here identifies anybody. Both columns are random ref tokens, and the rows die
 * with their requests ([dropClosed]), so this is deliberately not a durable record of two
 * people who do not want each other.
 */
class DoneRefusalRepository(
    ds: DataSource,
    private val clock: Clock = Clock.systemUTC(),
    // connectExposed(ds) is memoized per DataSource; see RequestRepository's constructor comment.
    private val db: Database = connectExposed(ds),
) {
    fun count(declarerToken: String, refuserToken: String): Int = transaction(db) {
        DoneRefusals.selectAll()
            .where {
                (DoneRefusals.refToken eq declarerToken) and (DoneRefusals.peerRefToken eq refuserToken)
            }
            .singleOrNull()
            ?.get(DoneRefusals.refusals)
            ?: 0
    }

    /**
     * Read-then-write rather than a SQL increment: this is a small table on a single-process
     * bot (ADR 0004), the surrounding transaction is the guard, and the plain form is the one
     * that is obviously right.
     */
    fun record(declarerToken: String, refuserToken: String): Int = transaction(db) {
        val next = count(declarerToken, refuserToken) + 1
        DoneRefusals.upsert {
            it[refToken] = declarerToken
            it[peerRefToken] = refuserToken
            it[refusals] = next
            it[refusedAt] = clock.instant()
        }
        next
    }

    /** Forgetting: every row naming one of this person's requests, whichever side it sits on. */
    fun deleteFor(tokens: List<String>): Int = transaction(db) {
        if (tokens.isEmpty()) {
            0
        } else {
            DoneRefusals.deleteWhere {
                (DoneRefusals.refToken inList tokens) or (DoneRefusals.peerRefToken inList tokens)
            }
        }
    }

    /**
     * Housekeeping: a row whose declarer's or refuser's request is no longer resting has
     * nothing left to block. Read-then-delete, for the reason [record] gives.
     */
    fun dropClosed(): Int = transaction(db) {
        val live = Requests.selectAll()
            .where { Requests.state eq RequestState.OPEN.name }
            .map { it[Requests.refToken] }
            .toSet()
        val dead = DoneRefusals.selectAll()
            .map { it[DoneRefusals.refToken] to it[DoneRefusals.peerRefToken] }
            .filter { (declarer, refuser) -> declarer !in live || refuser !in live }
        for ((declarer, refuser) in dead) {
            DoneRefusals.deleteWhere {
                (DoneRefusals.refToken eq declarer) and (DoneRefusals.peerRefToken eq refuser)
            }
        }
        dead.size
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew test --tests 'fxbot.DoneRefusalRepositoryTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/fxbot/DoneRefusalRepository.kt src/test/kotlin/fxbot/DoneRefusalRepositoryTest.kt
git commit -m "feat: count a refused done, per pairing, one direction only"
```

---

### Task 5: A done asks, and closes nothing until the answer

The heart of the change. `LifecycleService.done` stops closing; `confirm` and `refuse` are the two answers.

**Files:**
- Modify: `src/main/kotlin/fxbot/Request.kt`, `src/main/kotlin/fxbot/Residual.kt`, `src/main/kotlin/fxbot/LifecycleService.kt`, `src/main/kotlin/fxbot/Registry.kt`, `src/main/kotlin/fxbot/Main.kt`, `src/main/kotlin/fxbot/Callbacks.kt`, `src/main/kotlin/fxbot/LifecycleCommands.kt`
- Test: `src/test/kotlin/fxbot/LifecycleServiceTest.kt`

**Interfaces:**
- Consumes: `DoneRefusalRepository`, `PersonSettingsRepository`, `NameLookup`, `NameBook`, `nameBookFor`, `RequestRepository.closeBothWhole`, `residualOf`.
- Produces:
  - `fun Request.spokePrivately(): Boolean` and `fun Request.answerChatId(): Long` in `Request.kt`
  - `data class Notice(val chatId: Long, val text: String, val reopenToken: String, val restate: RestateOffer?)`
  - `ActionResult.Ok` gains `val notify: Notice? = null`
  - `ActionResult.Asked(text, declarerUserId, peerUserId, peerChatId, question, myToken, peerToken)`
  - `const val MAX_REFUSALS = 2`
  - `LifecycleService(requests, settings, rates, people, refusals, names)` — `giveUps` is gone from its parameter list
  - `suspend fun done(userId: Long, mineToken: String, theirsToken: String): ActionResult`
  - `suspend fun confirm(userId: Long, declarerToken: String, peerToken: String): ActionResult`
  - `fun refuse(userId: Long, declarerToken: String, peerToken: String): ActionResult`
  - `Registry.refusals: DoneRefusalRepository`

Note: `doneByShortId` still exists and still compiles here; it gets a placeholder refusal for the "no counterparty resolved" case which **Task 6 replaces**. `GiveUpService` keeps working — nothing in this task touches it.

- [ ] **Step 1: Write the failing tests**

Add to `LifecycleServiceTest.kt`. First a fixture beside the existing ones (replacing `ConsentFixture`'s role for the new tests — leave `ConsentFixture` in place; Task 9 deletes it):

```kotlin
/** The wiring a done needs now: person tolerances for the candidate search, and the refusal count. */
private class AskFixture(name: String) {
    private val ds = memDataSource(name).also { migrate(it) }
    private val clock: Clock = Clock.fixed(T0, ZoneOffset.UTC)
    private val crypto = testCrypto()
    val requests = RequestRepository(ds, crypto, clock)
    val refusals = DoneRefusalRepository(ds, clock)
    val svc = LifecycleService(
        requests,
        ChatSettingsRepository(ds, crypto, clock),
        deadRateService(ds, clock),
        PersonSettingsRepository(ds, crypto, clock),
        refusals,
        NameLookup { null },
    )

    fun rest(chatId: Long, userId: Long, name: String?, side: Side, interest: String? = null) =
        requests.create(chatId, userId, name, side, "EUR", BigDecimal("1000"), EURRUB, 7, interest)
}
```

Then the cases:

```kotlin
    "a done asks the counterparty and closes nothing" {
        val f = AskFixture("ask_nothing_closes")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.done(1L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Asked>()
        r.peerUserId shouldBe 2L
        r.myToken shouldBe mine.refToken
        r.peerToken shouldBe theirs.refToken
        r.question shouldContain "@bob"
        r.question shouldContain "1,000 EUR"
        r.text shouldContain "Nothing's closed yet"
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    "the ask goes to the chat somebody typed in, and privately to somebody who did not" {
        val f = AskFixture("ask_delivery")
        val typed = f.rest(-100L, 1L, "bob", Side.OFFER)
        val showing = f.rest(-100L, 2L, "ann", Side.BID, interest = "i2")
        val toShowing = f.svc.done(1L, typed.refToken, showing.refToken)
        toShowing.shouldBeInstanceOf<ActionResult.Asked>()
        toShowing.peerChatId shouldBe 2L
        val toTyped = f.svc.done(2L, showing.refToken, typed.refToken)
        toTyped.shouldBeInstanceOf<ActionResult.Asked>()
        toTyped.peerChatId shouldBe -100L
    }

    "only the counterparty's Yes closes anything" {
        val f = AskFixture("confirm_authorized")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.confirm(3L, mine.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        val ok = f.svc.confirm(2L, mine.refToken, theirs.refToken)
        ok.shouldBeInstanceOf<ActionResult.Ok>()
        ok.touchedTokens shouldContainExactlyInAnyOrder listOf(mine.refToken, theirs.refToken)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.DONE
        ok.text shouldContain "@bob"
        ok.text shouldContain "@ann"
        ok.notify.shouldNotBeNull().chatId shouldBe -100L
        ok.notify!!.reopenToken shouldBe mine.refToken
    }

    "a Yes about a request that has already closed refuses, and names which side" {
        val f = AskFixture("confirm_gone")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.requests.transition(mine.refToken, RequestState.OPEN, RequestState.CANCELLED)
        val r = f.svc.confirm(2L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Gone>()
        r.text shouldContain "@bob"
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    "a No closes nothing and leaves both resting" {
        val f = AskFixture("refuse_nothing")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.refuse(2L, mine.refToken, theirs.refToken)
        r.shouldBeInstanceOf<ActionResult.Ok>()
        r.touchedTokens.shouldBeEmpty()
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
        f.refusals.count(mine.refToken, theirs.refToken) shouldBe 1
    }

    "a second No blocks a third ask, and the counterparty is not asked again" {
        val f = AskFixture("refuse_twice")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        val blocked = f.svc.done(1L, mine.refToken, theirs.refToken)
        blocked.shouldBeInstanceOf<ActionResult.Denied>()
        blocked.text shouldContain "twice"
    }

    "the block sits on whoever kept asking, not on the person refusing" {
        val f = AskFixture("refuse_one_way")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        f.svc.refuse(2L, mine.refToken, theirs.refToken)
        f.svc.done(2L, theirs.refToken, mine.refToken).shouldBeInstanceOf<ActionResult.Asked>()
    }

    "a forged Yes naming somebody else's request as the presser's own closes nothing" {
        val f = AskFixture("confirm_forged")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val bystander = f.rest(-100L, 3L, "cat", Side.BID)
        f.svc.confirm(3L, mine.refToken, theirs.refToken).shouldBeInstanceOf<ActionResult.Denied>()
        f.svc.confirm(3L, mine.refToken, bystander.refToken).shouldBeInstanceOf<ActionResult.Ok>()
        f.requests.byRefToken(theirs.refToken)!!.state shouldBe RequestState.OPEN
    }

    "the same-chat force close now only asks its victim" {
        val f = AskFixture("force_close_asks")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val uninvolved = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.done(1L, mine.refToken, uninvolved.refToken)
        r.shouldBeInstanceOf<ActionResult.Asked>()
        f.requests.byRefToken(uninvolved.refToken)!!.state shouldBe RequestState.OPEN
    }
```

Add the imports these need to the file's import block if absent: `io.kotest.matchers.collections.shouldBeEmpty`, `io.kotest.matchers.collections.shouldContainExactlyInAnyOrder`.

Existing cases in this file that assert `done` closed something must be updated in Step 5 — do not delete them.

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew test --tests 'fxbot.LifecycleServiceTest'`
Expected: FAIL — `Unresolved reference: confirm` and a constructor arity error on `LifecycleService`.

- [ ] **Step 3: Add the two request predicates**

In `Request.kt`, below the `Request` data class:

```kotlin
/**
 * Whether somebody spoke to the bot privately. True for every row born of a stated
 * interest — including its showings in chats, which the person never typed in — and for
 * the chatless row itself.
 */
fun Request.spokePrivately(): Boolean = interestToken != null || chatId == NO_CHAT_ID

/**
 * Where this person is answered: their own chat with the bot when they spoke privately,
 * otherwise the chat they typed in. A private chat's id IS the person's user id.
 */
fun Request.answerChatId(): Long = if (spokePrivately()) userId else chatId
```

In `Residual.kt`, make the existing predicate share it rather than restate it:

```kotlin
fun restateGoesPrivate(mine: Request): Boolean = mine.spokePrivately()
```

- [ ] **Step 4: Rewrite LifecycleService**

Replace the `RestateOffer`/`ActionResult` block at the top of `LifecycleService.kt`:

```kotlin
/**
 * A second person who has to be told about an outcome somebody else's press produced,
 * in the place THEY spoke to the bot. Carries its own reopen token and its own residual,
 * because the two sides of a done are not left holding the same thing.
 */
data class Notice(
    val chatId: Long,
    val text: String,
    val reopenToken: String,
    val restate: RestateOffer?,
)

sealed interface ActionResult {
    val text: String

    data class Ok(
        override val text: String,
        val touchedTokens: List<String>,
        val restate: RestateOffer? = null,
        /** Somebody else who must hear about this, where they spoke. */
        val notify: Notice? = null,
    ) : ActionResult

    data class Denied(override val text: String) : ActionResult
    data class Gone(override val text: String) : ActionResult

    /**
     * The counterparty has been asked and nothing has closed. [text] is for the person who
     * declared it; [question] is the message the counterparty gets, in [peerChatId], with
     * the two buttons `askButtons` builds from [myToken] and [peerToken].
     */
    data class Asked(
        override val text: String,
        val declarerUserId: Long,
        val peerUserId: Long,
        val peerChatId: Long,
        val question: String,
        val myToken: String,
        val peerToken: String,
    ) : ActionResult
}

internal fun ActionResult.outcomeLabel(): String = when (this) {
    is ActionResult.Ok -> "ok"
    is ActionResult.Denied -> "denied"
    is ActionResult.Gone -> "gone"
    is ActionResult.Asked -> "asked"
}
```

Add beside `NOT_A_PAIR`:

```kotlin
/**
 * Two refusals end it. A first No is usually an honest mix-up — the wrong short id, the
 * wrong person, a misremembered swap. A second is a pattern.
 */
const val MAX_REFUSALS = 2

private const val REFUSED_TWICE =
    "They've said no to that twice, so I won't ask them again."

private const val NOT_ASKED = "That isn't a question I asked you."
```

Change the constructor and add the name helper:

```kotlin
class LifecycleService(
    private val requests: RequestRepository,
    private val settings: ChatSettingsRepository,
    private val rates: RateService,
    /** One side of the candidate search: a person's own size tolerance. */
    private val people: PersonSettingsRepository,
    /** Two refusals about one pairing, and no third ask. */
    private val refusals: DoneRefusalRepository,
    /** The one thing a stored row cannot supply: a display name for somebody with no handle. */
    private val names: NameLookup,
) {

    /** Text sent with HTML parse mode — see [mention] — so callers must send it that way. */
    private fun nameOf(r: Request, book: NameBook) = mentionOf(r, book)
```

Delete the old private `nameOf(r: Request)`.

Now replace `done` entirely:

```kotlin
    /**
     * Resolves the pairing, guards it, and asks. Nothing closes here, ever.
     *
     * Two checks remain from before: the presser must own one of the two requests, and the
     * two must be a pairing the bot could plausibly have suggested — same chat, opposite
     * sides, different people. The third guard this used to carry, a consent lookup for the
     * chatless side, is gone: the confirmation replaces it with something stronger, because
     * the person who would have been wronged by a force close is the person now being asked.
     *
     * The size tolerance is deliberately NOT re-checked: two people are free to agree a swap
     * the bot would not have introduced them for, and this only records that they did.
     */
    suspend fun done(userId: Long, mineToken: String, theirsToken: String): ActionResult {
        val a = requests.byRefToken(mineToken) ?: return ActionResult.Gone("That request is gone.")
        val b = requests.byRefToken(theirsToken) ?: return ActionResult.Gone("That request is gone.")
        if (a.userId != userId && b.userId != userId) {
            return ActionResult.Denied("Only the two people swapping can mark this done.")
        }
        // Reported relative to whoever acted, not to the argument order.
        val mine = if (a.userId == userId) a else b
        val theirs = if (a.userId == userId) b else a
        if (!pairable(mine, theirs)) return ActionResult.Denied(NOT_A_PAIR)
        if (mine.state != RequestState.OPEN) return ActionResult.Gone("That one is already closed.")
        val book = nameBookFor(listOf(mine, theirs), names)
        if (theirs.state != RequestState.OPEN) {
            return ActionResult.Gone("${nameOf(theirs, book)}'s request isn't waiting anymore.")
        }
        if (refusals.count(mine.refToken, theirs.refToken) >= MAX_REFUSALS) {
            return ActionResult.Denied(REFUSED_TWICE)
        }
        return ActionResult.Asked(
            text = "Asked ${nameOf(theirs, book)} to confirm. Nothing's closed yet.",
            declarerUserId = mine.userId,
            peerUserId = theirs.userId,
            peerChatId = theirs.answerChatId(),
            question = "${nameOf(mine, book)} says you two swapped " +
                "${formatAmount(mine.statedAmount)} ${mine.statedCurrency}. Did you?",
            myToken = mine.refToken,
            peerToken = theirs.refToken,
        )
    }

    /** Same chat, opposite sides, two different people — the shape a suggestion always has. */
    private fun pairable(mine: Request, theirs: Request): Boolean =
        theirs.chatId == mine.chatId && theirs.side != mine.side && theirs.userId != mine.userId

    /**
     * The counterparty says yes. Everything is re-derived here: the presser from the caller's
     * user id, both rows from the database, the pairing structurally. A payload claiming a Yes
     * on somebody else's behalf achieves nothing, because [peerToken] must be the presser's own.
     */
    suspend fun confirm(userId: Long, declarerToken: String, peerToken: String): ActionResult {
        val theirs = requests.byRefToken(declarerToken) ?: return ActionResult.Gone("That request is gone.")
        val mine = requests.byRefToken(peerToken) ?: return ActionResult.Gone("That request is gone.")
        if (mine.userId != userId) return ActionResult.Denied(NOT_ASKED)
        if (!pairable(mine, theirs)) return ActionResult.Denied(NOT_A_PAIR)
        if (mine.state != RequestState.OPEN) return ActionResult.Gone("Your own request is already closed.")
        val book = nameBookFor(listOf(mine, theirs), names)
        if (theirs.state != RequestState.OPEN) {
            return ActionResult.Gone("${nameOf(theirs, book)}'s request is already closed, so there's nothing to confirm.")
        }
        // Both interests close together, in one transaction: a swap is one decision, and a
        // half-applied one would leave a person resting against a counterparty who is gone.
        val closed = requests.closeBothWhole(mine, theirs, RequestState.DONE)
        if (closed.mine.isEmpty()) return ActionResult.Gone("That one is already closed.")
        val both = "${nameOf(mine, book)} and ${nameOf(theirs, book)}"
        return ActionResult.Ok(
            text = "Marked done: $both. If that's wrong, /reopen.",
            touchedTokens = closed.mine + closed.theirs,
            restate = offerFor(mine, theirs),
            notify = Notice(
                chatId = theirs.answerChatId(),
                text = "${nameOf(mine, book)} confirmed. Marked done: $both. If that's wrong, /reopen.",
                reopenToken = theirs.refToken,
                restate = offerFor(theirs, mine),
            ),
        )
    }

    /**
     * The counterparty says no. Nothing closes and the pairing stands — somebody denying a
     * done they did not make must not lose a real counterparty for it. The refusal is
     * counted against the DECLARER, so a second one blocks them and nobody else.
     */
    fun refuse(userId: Long, declarerToken: String, peerToken: String): ActionResult {
        val theirs = requests.byRefToken(declarerToken) ?: return ActionResult.Gone("That request is gone.")
        val mine = requests.byRefToken(peerToken) ?: return ActionResult.Gone("That request is gone.")
        if (mine.userId != userId) return ActionResult.Denied(NOT_ASKED)
        if (!pairable(mine, theirs)) return ActionResult.Denied(NOT_A_PAIR)
        refusals.record(theirs.refToken, mine.refToken)
        return ActionResult.Ok("Noted — nothing's closed. Your request is still waiting.", emptyList())
    }

    /**
     * What a done leaves [mine] holding, in their own stated currency. Nothing is offered
     * when there is none left over, or when the two are stated in different currencies with
     * no reference rate to bridge them.
     */
    private fun offerFor(mine: Request, theirs: Request): RestateOffer? =
        residualOf(mine, theirs, rates.status(mine.pair).rate)
            ?.let { RestateOffer(mine.refToken, theirs.refToken, it, mine.statedCurrency) }
```

In `doneByShortId`, delete both `giveUps.bothOffered(...)` guards and make the null case explicit — Task 6 replaces this whole body:

```kotlin
        val theirs = (peer as? NamedPeer.Somebody)
            ?.let { named -> requests.resting(chatId).firstOrNull { it.userId == named.userId } }
            ?: return ActionResult.Denied(NOT_A_PAIR)
        return done(userId, mine.refToken, theirs.refToken)
```

and mark it `suspend`.

- [ ] **Step 5: Update the callers and the existing tests**

`Registry.kt`: add `lateinit var refusals: DoneRefusalRepository`.

`Main.kt`: build it and pass the new parameters.

```kotlin
    Registry.refusals = DoneRefusalRepository(ds, db = db)
    Registry.people = PersonSettingsRepository(ds, crypto, db = db)
    Registry.lifecycle = LifecycleService(
        Registry.requests, Registry.settings, Registry.rates,
        Registry.people, Registry.refusals, Registry.names,
    )
```

`Registry.people` and `Registry.names` must now be assigned **before** `Registry.lifecycle`; move those two lines up. `Registry.names = telegramNames(bot)` needs the bot, so move the whole `Registry.lifecycle` assignment down to the block after the `TelegramBot` is constructed.

`Callbacks.doneCallback` and `LifecycleCommands.done` do not compile until Task 7 handles `ActionResult.Asked`; for now add a `is ActionResult.Asked ->` branch to `respond` and `replyToDecision` that sends only `result.text` to the acting chat, with a `// Task 7 delivers the question.` comment. Task 7 replaces both.

In `LifecycleServiceTest.kt`, the existing cases that asserted `done` closed things now assert it asks. Work through each failure and rewrite it: a case that used to expect `ActionResult.Ok` from `done` becomes `Asked` followed by `confirm` from the counterparty's user id. The `BreakableClock` case that interrupts between the two closes moves from `done` to `confirm`, which is where `closeBothWhole` now runs. Leave `ConsentFixture` and its give-up cases untouched — Task 9 removes them.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "feat: a done asks the counterparty and closes nothing until they agree"
```

---

### Task 6: `/done a1`, with the handle optional

**Files:**
- Modify: `src/main/kotlin/fxbot/LifecycleService.kt`, `src/main/kotlin/fxbot/Render.kt`
- Test: `src/test/kotlin/fxbot/LifecycleServiceTest.kt`

**Interfaces:**
- Consumes: `findCounterparties`, `PersonSettingsRepository.get`, `ChatSettingsRepository.get`, `ActionResult.Asked`.
- Produces:
  - `ActionResult.Choose(text, mineToken, candidates: List<Request>)`
  - `fun chooseButtons(r: ActionResult.Choose, book: NameBook = NameBook.EMPTY): List<Button>` in `Render.kt`
  - `outcomeLabel()` gains `is ActionResult.Choose -> "choose"`

- [ ] **Step 1: Write the failing tests**

```kotlin
    "one counterparty and no handle typed: they are asked" {
        val f = AskFixture("done_sole")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Nobody)
        r.shouldBeInstanceOf<ActionResult.Asked>()
        r.peerToken shouldBe theirs.refToken
    }

    "several counterparties and no handle typed: nothing is asked and nothing closes" {
        val f = AskFixture("done_several")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val one = f.rest(-100L, 2L, "ann", Side.BID)
        val two = f.rest(-100L, 3L, "cat", Side.BID)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Nobody)
        r.shouldBeInstanceOf<ActionResult.Choose>()
        r.mineToken shouldBe mine.refToken
        r.candidates.map { it.refToken } shouldContainExactlyInAnyOrder listOf(one.refToken, two.refToken)
        f.requests.byRefToken(one.refToken)!!.state shouldBe RequestState.OPEN
        f.requests.byRefToken(two.refToken)!!.state shouldBe RequestState.OPEN
    }

    "no counterparty at all: the refusal points at cancel" {
        val f = AskFixture("done_none")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Nobody)
        r.shouldBeInstanceOf<ActionResult.Gone>()
        r.text shouldContain "/cancel ${mine.shortId}"
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
    }

    "a named counterparty still resolves" {
        val f = AskFixture("done_named")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        val theirs = f.rest(-100L, 2L, "ann", Side.BID)
        f.rest(-100L, 3L, "cat", Side.BID)
        val r = f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Somebody(2L))
        r.shouldBeInstanceOf<ActionResult.Asked>()
        r.peerToken shouldBe theirs.refToken
    }

    "a name nothing here belongs to refuses" {
        val f = AskFixture("done_unplaceable")
        val mine = f.rest(-100L, 1L, "bob", Side.OFFER)
        f.rest(-100L, 2L, "ann", Side.BID)
        f.svc.doneByShortId(-100L, 1L, mine.shortId, NamedPeer.Unplaceable)
            .shouldBeInstanceOf<ActionResult.Denied>()
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew test --tests 'fxbot.LifecycleServiceTest'`
Expected: FAIL — `Unresolved reference: Choose`.

- [ ] **Step 3: Add the Choose result**

In `LifecycleService.kt`, inside `sealed interface ActionResult`:

```kotlin
    /**
     * More than one person could be meant, so nobody is asked and nothing closes. The
     * declarer picks, and the pick is an ordinary done.
     */
    data class Choose(
        override val text: String,
        val mineToken: String,
        val candidates: List<Request>,
    ) : ActionResult
```

and in `outcomeLabel()`:

```kotlin
    is ActionResult.Choose -> "choose"
```

- [ ] **Step 4: Rewrite doneByShortId**

```kotlin
    /**
     * The typed form. A done means a swap, and a swap has a counterparty, so there is no
     * longer any route that closes a request without asking somebody: a name nothing here
     * belongs to refuses, and no name at all resolves to whoever the bot would have
     * suggested — one of them asked, several of them offered as a choice.
     *
     * The refusal for an unplaceable name is [NOT_A_PAIR], deliberately: a distinct "nobody
     * by that name rests anything" would answer, for any handle a stranger cares to type,
     * whether that person is resting anything with the bot.
     */
    suspend fun doneByShortId(chatId: Long, userId: Long, shortId: String, peer: NamedPeer): ActionResult {
        val mine = requests.byShortId(chatId, shortId)
            // shortId is raw user input, never validated — escaped in case this text is
            // ever sent under an HTML parse mode by some future caller.
            ?: return ActionResult.Gone("I can't find a waiting request called ${escapeHtml(shortId)} here.")
        if (mine.userId != userId) return ActionResult.Denied("That's not your request.")
        if (peer is NamedPeer.Unplaceable) return ActionResult.Denied(NOT_A_PAIR)
        if (peer is NamedPeer.Somebody) {
            val theirs = requests.resting(chatId).firstOrNull { it.userId == peer.userId }
                ?: return ActionResult.Denied(NOT_A_PAIR)
            return done(userId, mine.refToken, theirs.refToken)
        }
        val candidates = candidatesFor(mine)
        return when (candidates.size) {
            0 -> ActionResult.Gone(
                "I can't see anyone here you could have swapped with. " +
                    "If you just want the request gone, /cancel ${mine.shortId}.",
            )
            1 -> done(userId, mine.refToken, candidates.single().refToken)
            else -> ActionResult.Choose(
                "More than one person here could be the one. Which of them?",
                mine.refToken,
                candidates,
            )
        }
    }

    /**
     * Who the bot would suggest for [r] right now, judged the way that scope judges: a
     * chat's own size tolerance for a showing or a typed request, each person's own for
     * a request with no chat behind it.
     */
    private fun candidatesFor(r: Request): List<Request> {
        val resting = requests.resting(r.chatId)
        val rate = rates.status(r.pair).rate
        val found = if (r.chatId == NO_CHAT_ID) {
            findCounterparties(
                r, resting, rate, people.get(r.userId).tolerancePct,
                peerTolerancePct = { people.get(it.userId).tolerancePct },
            )
        } else {
            findCounterparties(r, resting, rate, settings.get(r.chatId).tolerancePct)
        }
        return found.map { it.request }
    }
```

- [ ] **Step 5: Add the buttons**

In `Render.kt`, beside `suggestionButtons`:

```kotlin
/** One button per person the declarer could have meant; pressing one is an ordinary done. */
fun chooseButtons(r: ActionResult.Choose, book: NameBook = NameBook.EMPTY): List<Button> =
    r.candidates.map { c ->
        Button("✅ Done with ${plainName(c, book)}", Cb.done(r.mineToken, c.refToken))
    }
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests 'fxbot.LifecycleServiceTest' --tests 'fxbot.RenderTest'`
Expected: PASS. Then `./gradlew test` — the `LifecycleCommandTest` cases that expected `/done a1` with no handle to close the caller's own request now expect an ask or a choice; update them.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "feat: drop the handle from /done, and ask whoever it resolves to"
```

---

### Task 7: The Telegram side of the ask

**Files:**
- Modify: `src/main/kotlin/fxbot/Render.kt`, `src/main/kotlin/fxbot/Callbacks.kt`, `src/main/kotlin/fxbot/LifecycleCommands.kt`
- Test: `src/test/kotlin/fxbot/LifecycleCommandTest.kt`

**Interfaces:**
- Consumes: `ActionResult.Asked`, `ActionResult.Choose`, `Notice`, `chooseButtons`, `nameBookFor`, `Registry.names`, `Registry.messages`, `Registry.lifecycle`.
- Produces:
  - `Cb.CONFIRM = "yes"`, `Cb.REFUSE = "no"`, `Cb.confirm(declarer, mine)`, `Cb.refuse(declarer, mine)`
  - `fun askButtons(r: ActionResult.Asked): List<Button>` and `fun noticeButtons(n: Notice): List<Button>` in `Render.kt`
  - `internal suspend fun sendAsk(r: ActionResult.Asked, bot: TelegramBot)`, `internal suspend fun sendNotice(n: Notice, bot: TelegramBot)`, `internal suspend fun sendChoice(chatId: Long, r: ActionResult.Choose, bot: TelegramBot)` in `LifecycleCommands.kt`
  - `@CommandHandler.CallbackQuery(["yes"])` → `confirmDoneCallback`, `@CommandHandler.CallbackQuery(["no"])` → `refuseDoneCallback`

In `Cb.confirm(declarer, mine)` the presser owns **`b`**, not `a` — the reverse of `Cb.done`. That is deliberate and stated in the doc comment: `a` is always the declarer's request, in both the done and its answer.

- [ ] **Step 1: Write the failing tests**

Add to `LifecycleCommandTest.kt`, following its existing recordingBot style:

```kotlin
    "a done sends the question to the counterparty and tells the declarer nothing closed" {
        val f = CommandFixture("cmd_ask")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        done(f.groupUpdate(1L, "/done ${mine.shortId}"), bot)
        val sent = calls.filter { it.path == "sendMessage" }
        sent shouldHaveSize 2
        sent.first { it.body.contains("Did you?") }.body shouldContain "\"yes?a="
        sent.first { it.body.contains("Did you?") }.body shouldContain "\"no?a="
        sent.first { it.body.contains("Nothing's closed yet") }.shouldNotBeNull()
    }

    "a Yes closes both and tells the declarer where they spoke" {
        val f = CommandFixture("cmd_yes")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        confirmDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), bot)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.DONE
        calls.count { it.path == "sendMessage" && it.body.contains("Marked done") } shouldBe 2
    }

    "a No closes nothing and the declarer is not told" {
        val f = CommandFixture("cmd_no")
        val mine = f.rest(GROUP, 1L, "bob", Side.OFFER)
        val theirs = f.rest(GROUP, 2L, "ann", Side.BID)
        val calls = mutableListOf<Call>()
        val bot = recordingBot(calls)
        refuseDoneCallback(mine.refToken, theirs.refToken, f.callback(2L), bot)
        f.requests.byRefToken(mine.refToken)!!.state shouldBe RequestState.OPEN
        calls.none { it.path == "sendMessage" } shouldBe true
    }
```

`CommandFixture` is this file's existing fixture; add `rest`, `groupUpdate` and `callback` helpers to it in the same shape `PrivateCommandTest` already uses for its update builders, and a `GROUP` constant if the file lacks one.

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew test --tests 'fxbot.LifecycleCommandTest'`
Expected: FAIL — `Unresolved reference: confirmDoneCallback`.

- [ ] **Step 3: Add the callback payloads and the two button builders**

In `Render.kt`'s `Cb` object:

```kotlin
    const val CONFIRM = "yes"
    const val REFUSE = "no"

    /**
     * Answering a done. `a` is the DECLARER's request and `b` the request of the person
     * being asked — the reverse of [done]'s ownership, and checked as such: a press is
     * honoured only when the presser owns `b`.
     */
    fun confirm(declarer: String, mine: String) = "$CONFIRM?a=$declarer&b=$mine"
    fun refuse(declarer: String, mine: String) = "$REFUSE?a=$declarer&b=$mine"
```

Beside `decisionButtons`:

```kotlin
/** The two answers a done allows, in one place so every ask offers exactly these words. */
fun askButtons(r: ActionResult.Asked): List<Button> = listOf(
    Button("✅ Yes, we did", Cb.confirm(r.myToken, r.peerToken)),
    Button("✖️ No, we didn't", Cb.refuse(r.myToken, r.peerToken)),
)

/** What the other side of a confirmed done is offered: undo, and whatever it left them holding. */
internal fun noticeButtons(n: Notice): List<Button> =
    listOf(Button("↩️ Reopen", Cb.reopen(n.reopenToken))) +
        listOfNotNull(
            n.restate?.let {
                Button(
                    "➕ State the rest (${formatAmount(it.amount)} ${it.currency})",
                    Cb.restate(it.myToken, it.peerToken),
                )
            },
        )
```

- [ ] **Step 4: Send the ask, the choice and the notice**

In `LifecycleCommands.kt`, add three internal senders (both command and callback surfaces use them):

```kotlin
/**
 * The question, where the counterparty spoke. Recorded against BOTH people, because it
 * names the declarer and a `/forget` from either side must reach it (ADR 0005).
 */
internal suspend fun sendAsk(r: ActionResult.Asked, bot: TelegramBot) {
    val buttons = askButtons(r)
    val sent = message { r.question }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .sendReturning(r.peerChatId, bot)
        .getOrNull()
    sent?.messageId?.let { id ->
        Registry.messages.record(
            r.peerChatId, id,
            listOf(r.myToken, r.peerToken),
            listOf(r.declarerUserId, r.peerUserId),
            r.question, buttons,
        )
    }
}

/** The other side of a confirmed done, told where THEY spoke rather than where the press happened. */
internal suspend fun sendNotice(n: Notice, bot: TelegramBot) {
    val buttons = noticeButtons(n)
    message { n.text }
        .options { parseMode = ParseMode.HTML }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .send(n.chatId, bot)
}

/** Nobody is asked and nothing closes — the declarer just picks. */
internal suspend fun sendChoice(chatId: Long, r: ActionResult.Choose, bot: TelegramBot) {
    val book = nameBookFor(r.candidates, Registry.names)
    val buttons = chooseButtons(r, book)
    message { r.text }
        .inlineKeyboardMarkup { buttons.forEach { b -> b.label callback b.data; br() } }
        .send(chatId, bot)
}
```

Then give `replyToDecision` the two new branches, before its existing `Ok` handling:

```kotlin
    when (result) {
        is ActionResult.Asked -> {
            message { result.text }.options { parseMode = ParseMode.HTML }.send(chatId, bot)
            sendAsk(result, bot)
            return
        }
        is ActionResult.Choose -> {
            sendChoice(chatId, result, bot)
            return
        }
        else -> {}
    }
```

and after `Registry.buttons.refreshFor(result.touchedTokens, bot)` in the `Ok` path:

```kotlin
    (result as? ActionResult.Ok)?.notify?.let { sendNotice(it, bot) }
```

Do the same in `Callbacks.respond`: an `Asked` sends `result.text` as the callback answer via `answerCallbackQuery` and then `sendAsk`; a `Choose` clears the spinner and calls `sendChoice(update.getChat().id, result, bot)`; the `Ok` branch calls `sendNotice` after `refreshFor`.

- [ ] **Step 5: Add the two callbacks**

In `Callbacks.kt`, replacing the give-up pair's position in the file:

```kotlin
/**
 * The counterparty's answer. Nothing here is trusted: the presser is re-derived from
 * `callback_query.from.id`, both rows are re-read, and [LifecycleService.confirm] honours
 * the press only when the presser owns the request `b` names.
 */
@CommandHandler.CallbackQuery(["yes"], autoAnswer = false)
suspend fun confirmDoneCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val result = if (a == null || b == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.confirm(update.getUser().id, a, b)
    logCommand("confirm_button", result.outcomeLabel())
    respond(result, update, bot)
}

/**
 * Saying no. Nothing closes, both requests keep resting, and the refusal is counted
 * against the declarer — never against the person refusing.
 */
@CommandHandler.CallbackQuery(["no"], autoAnswer = false)
suspend fun refuseDoneCallback(a: String?, b: String?, update: ProcessedUpdate, bot: TelegramBot) {
    val result = if (a == null || b == null) ActionResult.Denied(BROKEN_BUTTON)
        else Registry.lifecycle.refuse(update.getUser().id, a, b)
    logCommand("refuse_button", result.outcomeLabel())
    // An empty `touchedTokens` means `respond` answers the press without a keyboard and
    // without a refresh pass — which is exactly right: nothing changed state.
    respond(result, update, bot)
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "feat: deliver the ask where the counterparty spoke, and act on their answer"
```

---

### Task 8: The private surface names people

**Files:**
- Modify: `src/main/kotlin/fxbot/InterestService.kt`, `src/main/kotlin/fxbot/Render.kt`, `src/main/kotlin/fxbot/AnnouncementBatcher.kt`, `src/main/kotlin/fxbot/PrivateCommands.kt`, `src/main/kotlin/fxbot/Callbacks.kt`
- Test: `src/test/kotlin/fxbot/InterestServiceTest.kt`, `src/test/kotlin/fxbot/RenderTest.kt`, `src/test/kotlin/fxbot/PrivateCommandTest.kt`, `src/test/kotlin/fxbot/AnnouncementBatcherTest.kt`

**Interfaces:**
- Consumes: `ShownInterest`, `NameBook`, `Cb.done`, `plainName`, `mentionOf`.
- Produces:
  - `InterestResult.Stated(interest, showings, shown: List<ShownInterest>, status, appeared)` — the `found` field is gone; `shown` holds the chatless row first, then one entry per showing.
  - `renderStated(r, book)` and `statedButtons(r, book)` name counterparties and offer `✅ Done with …`
  - `renderAppeared(mine, book)` and `appearedButtons(mine, book)` likewise
  - `Ping(userId, text, buttons, refTokens, userIds)` — the two new fields let `telegramSink` record a ping

- [ ] **Step 1: Write the failing tests**

`InterestServiceTest.kt`:

```kotlin
    "a statement reports counterparties from its showings as well as bot-wide" {
        val f = InterestFixture("stated_shown")
        f.chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))
        val inGroup = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        val r = f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.shown.first().request.chatId shouldBe NO_CHAT_ID
        r.shown.flatMap { s -> s.found.map { it.request.refToken } } shouldContain inGroup.refToken
    }

    "nobody is named twice in one reply" {
        val f = InterestFixture("stated_dedup")
        f.chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))
        f.chats.save(ChatSettings(OTHER_GROUP, EURRUB, 20, 7, fanOut = true))
        f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        f.requests.create(OTHER_GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        val r = f.svc.state(1L, "bob", Verb.SELL, "1000", "EUR", "RUB")
        r.shouldBeInstanceOf<InterestResult.Stated>()
        r.shown.flatMap { s -> s.found.map { it.request.userId } } shouldBe listOf(2L)
    }
```

`InterestFixture` is this file's existing fixture; add an `OTHER_GROUP` constant and make its membership probe answer true for both groups.

`RenderTest.kt`:

```kotlin
    "a private reply names its counterparties and offers a done for each" {
        val mine = req(userId = 1L, username = "bob")
        val theirs = req(userId = 2L, username = "ann")
        val stated = InterestResult.Stated(
            interest = mine, showings = emptyList(),
            shown = listOf(ShownInterest(mine, listOf(Counterparty(theirs, null, java.math.BigDecimal.ZERO)))),
            status = RateStatus.Unavailable, appeared = emptyList(),
        )
        renderStated(stated) shouldContain "@ann"
        renderStated(stated) shouldNotContain "no names"
        statedButtons(stated).map { it.data } shouldContain Cb.done(mine.refToken, theirs.refToken)
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew test --tests 'fxbot.InterestServiceTest' --tests 'fxbot.RenderTest'`
Expected: FAIL — `Unresolved reference: shown`.

- [ ] **Step 3: Change what a statement returns**

In `InterestService.kt`:

```kotlin
sealed interface InterestResult {
    data class Stated(
        val interest: Request,
        val showings: List<Request>,
        /** The chatless row first, then each showing — every place this interest just landed. */
        val shown: List<ShownInterest>,
        val status: RateStatus,
        val appeared: List<CounterpartyAppeared>,
    ) : InterestResult

    data class Rejected(val reason: String) : InterestResult
}
```

and at the end of `state`:

```kotlin
        val botWide = counterparties(interest)
        val shown = named(
            listOf(ShownInterest(interest, botWide)) + showings.map { ShownInterest(it, counterparties(it)) },
        )
        return InterestResult.Stated(
            interest = interest,
            showings = showings,
            shown = shown,
            status = rates.status(pair),
            // Symmetric: each side's leftover was already judged against its own number, so
            // everyone found with no chat behind them has just gained a counterparty in return.
            // The people found in a SHOWING hear about it in that chat's announcement instead.
            appeared = botWide.map { CounterpartyAppeared(it.request.userId, it.request.refToken) },
        )
```

with, beside it:

```kotlin
    /**
     * The same person found in two of your groups is one counterparty, not two. First
     * occurrence wins, so the pairing offered is the one in the place listed first.
     */
    private fun named(shown: List<ShownInterest>): List<ShownInterest> {
        val seen = mutableSetOf<Long>()
        return shown.map { s -> s.copy(found = s.found.filter { seen.add(it.request.userId) }) }
    }
```

- [ ] **Step 4: Rewrite the four render functions**

In `Render.kt`:

```kotlin
/** The immediate private reply: who matches, and where this is about to be shown. */
fun renderStated(r: InterestResult.Stated, book: NameBook = NameBook.EMPTY): String {
    val text = StringBuilder("Noted: ${describe(r.interest)} (${r.interest.shortId}).")
    val found = r.shown.flatMap { it.found }
    if (found.isEmpty()) {
        text.append("\nNobody matches yet — you're waiting.")
    } else {
        text.append(if (found.size == 1) "\n1 person matches:" else "\n${found.size} people match:")
        for (c in found) {
            text.append("\n• ").append(mentionOf(c.request, book)).append(" — ").append(describe(c.request))
        }
        text.append('\n').append(AGREE_LINE)
    }
    text.append(
        when (r.showings.size) {
            0 -> "\nI'm not showing this in any group — either we share none that swap this pair, " +
                "or their admins turned that off."
            1 -> "\nI'll show this in 1 group shortly."
            else -> "\nI'll show this in ${r.showings.size} groups shortly."
        },
    )
    return text.toString()
}

/**
 * A done per pairing, each naming the row it was found against, plus one way out of the
 * interest itself. The two tokens on a button are always in the same scope, which is what
 * [LifecycleService.done]'s pairing check requires.
 */
fun statedButtons(r: InterestResult.Stated, book: NameBook = NameBook.EMPTY): List<Button> =
    r.shown.flatMap { s ->
        s.found.map { c -> Button("✅ Done with ${plainName(c.request, book)}", Cb.done(s.request.refToken, c.request.refToken)) }
    } + Button("✖️ Cancel ${r.interest.shortId}", Cb.cancel(r.interest.refToken))

/** Somebody you already told the bot about has just gained a counterparty. */
fun renderAppeared(mine: List<ShownInterest>, book: NameBook = NameBook.EMPTY): String {
    val text = StringBuilder("Someone matches an interest you have with me:")
    for (s in mine) {
        text.append("\n• your ").append(describe(s.request)).append(':')
        for (c in s.found) {
            text.append("\n   ↳ ").append(mentionOf(c.request, book)).append(" — ").append(describe(c.request))
        }
    }
    text.append('\n').append(AGREE_LINE)
    return text.toString()
}

fun appearedButtons(mine: List<ShownInterest>, book: NameBook = NameBook.EMPTY): List<Button> =
    mine.flatMap { s ->
        s.found.map { c -> Button("✅ Done with ${plainName(c.request, book)}", Cb.done(s.request.refToken, c.request.refToken)) }
    }
```

Delete `nameGiveUpButtons` — nothing calls it now.

- [ ] **Step 5: Record the pings**

`AnnouncementBatcher.kt`:

```kotlin
/** One "a counterparty appeared" message the bot owes one person. */
data class Ping(
    val userId: Long,
    val text: String,
    val buttons: List<Button>,
    val refTokens: List<String>,
    val userIds: List<Long>,
)
```

and in `flushAppeared`, after building `mine`:

```kotlin
        val book = nameBookFor(mine.flatMap { s -> s.found.map { it.request } }, names)
        val ping = Ping(
            userId = userId,
            text = renderAppeared(mine, book),
            buttons = appearedButtons(mine, book),
            refTokens = mine.map { it.request.refToken } + mine.flatMap { s -> s.found.map { it.request.refToken } },
            userIds = mine.map { it.request.userId } + mine.flatMap { s -> s.found.map { it.request.userId } },
        )
        sink.deliver(emptyList(), listOf(ping))
```

In `PrivateCommands.telegramSink`, replace the ping loop and its "deliberately NOT recorded" comment — a ping now names somebody, so `/forget` has to be able to redact it (ADR 0005):

```kotlin
    for (p in pings) {
        // Not retried, and deliberately: nothing about a ping is persisted, so a refused send
        // has nothing to re-render from — the batcher drained the token set before this ran.
        val sent = message { p.text }
            .options { parseMode = ParseMode.HTML }
            .inlineKeyboardMarkup { p.buttons.forEach { b -> b.label callback b.data; br() } }
            .sendReturning(p.userId, bot)
            .getOrNull()
        // Recorded because it NAMES somebody now: a /forget from either person has to reach it.
        if (sent != null) Registry.messages.record(p.userId, sent.messageId, p.refTokens, p.userIds, p.text, p.buttons)
    }
```

- [ ] **Step 6: Update the two statement call sites**

In `PrivateCommands.handlePrivatePost` and `Callbacks.restatePrivately`, build a book and record every token the buttons name:

```kotlin
            val everyone = result.shown.flatMap { s -> listOf(s.request) + s.found.map { it.request } }
            val book = nameBookFor(everyone, Registry.names)
            val text = renderStated(result, book)
            val buttons = statedButtons(result, book)
            // ... send as before ...
            sent?.messageId?.let { id ->
                Registry.messages.record(
                    chat.id, id,
                    everyone.map { it.refToken }, everyone.map { it.userId },
                    text, buttons,
                )
            }
```

- [ ] **Step 7: Run the tests**

Run: `./gradlew test`
Expected: PASS. `PrivateCommandTest` cases asserting a ping names nobody, or that `statedButtons` offers a give-up, now assert the opposite — rewrite them rather than deleting them.

- [ ] **Step 8: Commit**

```bash
git add -A src
git commit -m "feat: name counterparties in every private reply and ping"
```

---

### Task 9: Delete the give-up path

Nothing mints a give-up button any more. Take the whole mechanism out.

**Files:**
- Delete: `src/main/kotlin/fxbot/GiveUpService.kt`, `src/main/kotlin/fxbot/NameGiveUpRepository.kt`, `src/test/kotlin/fxbot/GiveUpServiceTest.kt`, `src/test/kotlin/fxbot/NameGiveUpRepositoryTest.kt`
- Modify: `src/main/resources/db/migration/V3__private_interests.sql`, `Tables.kt`, `Callbacks.kt`, `Render.kt`, `InterestService.kt`, `Matcher.kt`, `ForgetService.kt`, `Tasks.kt`, `PrivateCommands.kt`, `Registry.kt`, `Main.kt`
- Test: `SchemaDriftTest.kt`, `ForgetCommandTest.kt`, `TasksTest.kt`, `MatcherTest.kt`, `LifecycleServiceTest.kt`, `InterestServiceTest.kt`, `PrivateCommandTest.kt`

**Interfaces:**
- Consumes: `DoneRefusalRepository`.
- Produces: `findCounterparties` loses its `suppressed` parameter; `ForgetService(requests, log, people, refusals, pending)`; `Housekeeping(requests, settings, rates, log, refusals, pending, clock, onClosed)`; `Registry.giveUps` and `Registry.giveUpService` are gone.

- [ ] **Step 1: Write the failing tests**

`SchemaDriftTest.kt` — drop `NameGiveUps` from the array:

```kotlin
            val tables = arrayOf(
                Requests, ChatSettingsTable, FxRates, SentMessages, SentMessageRefs,
                PersonSettingsTable, PendingAnnouncements, DoneRefusals,
            )
```

`ForgetCommandTest.kt` — replace whichever case asserts give-up rows are erased with:

```kotlin
    "forgetting erases the refusals recorded about this person's requests" {
        val f = ForgetFixture("forget_refusals")
        val mine = f.rest(NO_CHAT_ID, 1L, Side.OFFER)
        val theirs = f.rest(NO_CHAT_ID, 2L, Side.BID)
        f.refusals.record(theirs.refToken, mine.refToken)
        f.forget.plan(1L, NO_CHAT_ID, personal = true)
        f.refusals.count(theirs.refToken, mine.refToken) shouldBe 0
    }
```

`TasksTest.kt` — replace the `onGiveUpDied` case with one asserting the sweep drops refusal rows whose requests closed, and delete the hook from the `Housekeeping` construction in its fixture.

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew test --tests 'fxbot.SchemaDriftTest' --tests 'fxbot.ForgetCommandTest'`
Expected: FAIL — a drift statement for the now-unmirrored `name_give_up` table, and `Unresolved reference: refusals`.

- [ ] **Step 3: Delete the mechanism**

```bash
git rm src/main/kotlin/fxbot/GiveUpService.kt \
       src/main/kotlin/fxbot/NameGiveUpRepository.kt \
       src/test/kotlin/fxbot/GiveUpServiceTest.kt \
       src/test/kotlin/fxbot/NameGiveUpRepositoryTest.kt
```

Delete from `V3__private_interests.sql` the `CREATE TABLE name_give_up` statement, its index, and their comment block. Delete `object NameGiveUps` from `Tables.kt`. Delete `giveUpCallback`, `declineCallback`, `discloseTo`, `disclosureReply`, `giveUpText` from `Callbacks.kt`, and `Cb.GIVE_UP`, `Cb.DECLINE`, `Cb.giveUp`, `Cb.decline` from `Render.kt`. Delete `GIVE_UP_DIED` and `telegramGiveUpDied` from `PrivateCommands.kt`, and `telegramNames`' give-up wording — its doc comment becomes:

```kotlin
/**
 * Looked up when a message is about to name somebody who has no stored handle. A private
 * chat's id IS the person's user id, so `getChat` addressed by user id is their own chat
 * with the bot; somebody the bot cannot reach comes back null and keeps the label they
 * always had.
 */
```

- [ ] **Step 4: Unpick the callers**

`Matcher.findCounterparties` — delete the `suppressed` parameter and its `.filter { !suppressed(subject, it) }` line, and the paragraph of its doc comment describing it.

`InterestService` — delete the `giveUps` constructor parameter, `alreadyPairedInAChat`, and the `suppressed` argument. Its `counterparties` becomes what Task 10 extends:

```kotlin
    /** The counterparties resting for [subject], each side judged at its own tolerance. */
    fun counterparties(subject: Request): List<Counterparty> = findCounterparties(
        subject = subject,
        resting = requests.resting(NO_CHAT_ID),
        rate = rates.status(subject.pair).rate,
        tolerancePct = people.get(subject.userId).tolerancePct,
        peerTolerancePct = { people.get(it.userId).tolerancePct },
    )
```

Add a doc note where the suppression used to be, so the deletion is not read as an oversight:

```kotlin
    // A pairing is no longer suppressed while a showing already pairs the two people. With
    // names everywhere the anonymous route is gone, and the rule that replaced it delivers
    // each person one message where they spoke — a chat that may be muted must never be
    // allowed to stand in for a message that was actually delivered.
```

`ForgetService` — swap the repository and feed it the tokens that were just deleted:

```kotlin
class ForgetService(
    private val requests: RequestRepository,
    private val log: MessageLogRepository,
    private val people: PersonSettingsRepository,
    private val refusals: DoneRefusalRepository,
    private val pending: PendingAnnouncementRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun plan(userId: Long, chatId: Long?, personal: Boolean, messageChatId: Long? = chatId): ForgetPlan {
        val messages = log.messagesForUser(userId, messageChatId)
        val (redact, delete) = messages.partition { log.namesOthers(it.messageId, it.chatId, userId) }
        val removed = requests.deleteFor(userId, chatId)
        // Unconditional, unlike the personal-only rows below: a refusal row is keyed on two
        // request tokens, and the requests these name have just been erased, so the rows are
        // dead whichever shape of forgetting this was.
        refusals.deleteFor(removed)
        if (personal) {
            people.delete(userId)
            pending.deleteFor(userId)
        }
        log.forget(userId, messageChatId)
        return ForgetPlan(removed.size, delete, redact)
    }
}
```

`Housekeeping` — swap the repository, drop the `onGiveUpDied` hook and the `bereaved` plumbing:

```kotlin
        val expired = requests.expireDue(clock.instant())
        val pruned = log.prune(clock.instant().minus(RETENTION))
        val staleRefusals = refusals.dropClosed()
        val stalePending = pending.dropClosed() + pending.dropOlderThan(clock.instant().minus(PENDING_MAX_AGE))
        logger.info("sweep: expired=${expired.size} pruned=$pruned refusals=$staleRefusals pending=$stalePending")
        if (expired.isNotEmpty()) fire("closed") { onClosed(expired) }
        return expired.size
```

`Registry` — delete `giveUpService` and `giveUps` and their doc comment. `Main` — delete both assignments, pass `Registry.refusals` to `ForgetService` and `Housekeeping`, and drop `onGiveUpDied`.

- [ ] **Step 5: Trim the tests that turn on give-up**

Delete `ConsentFixture` and every give-up case from `LifecycleServiceTest.kt`. In `InterestServiceTest`, `MatcherTest`, `PrivateCommandTest` and `AnnouncementBatcherTest`, delete every case that asserts a decline suppresses a pairing, that a pairing already visible in a chat is hidden, or that a name is withheld — those behaviours no longer exist. Every other case stands.

- [ ] **Step 6: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A src
git commit -m "feat: delete the name give-up, its table and its consent"
```

---

### Task 10: Each person told where they spoke

Two gaps remain. A group post that matches a showing tells the group but not the person who stated it privately; and `flushAppeared` can only work out counterparties for a chatless row.

**Files:**
- Modify: `src/main/kotlin/fxbot/RequestService.kt`, `src/main/kotlin/fxbot/Commands.kt`, `src/main/kotlin/fxbot/InterestService.kt`
- Test: `src/test/kotlin/fxbot/RequestServiceTest.kt`, `src/test/kotlin/fxbot/InterestServiceTest.kt`, `src/test/kotlin/fxbot/PrivateCommandTest.kt`

**Interfaces:**
- Consumes: `CounterpartyAppeared`, `AnnouncementBatcher.enqueueAppeared`.
- Produces: `PostResult.Posted(request, found, status, appeared: List<CounterpartyAppeared>)`; `InterestService.counterparties(subject)` answers for a showing as well as a chatless row.

- [ ] **Step 1: Write the failing tests**

`RequestServiceTest.kt`:

```kotlin
    "a group post owes a private word to whoever stated their side privately" {
        val f = ServiceFixture("post_appeared")
        val showing = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7, "i2")
        val typed = f.requests.create(GROUP, 3L, "cat", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        val r = f.svc.post(GROUP, 1L, "bob", Verb.SELL, "1000", "EUR")
        r.shouldBeInstanceOf<PostResult.Posted>()
        r.found.map { it.request.refToken } shouldContainExactlyInAnyOrder listOf(showing.refToken, typed.refToken)
        r.appeared.map { it.refToken } shouldBe listOf(showing.refToken)
    }
```

`InterestServiceTest.kt`:

```kotlin
    "a showing's counterparties are judged at its chat's tolerance, not the person's" {
        val f = InterestFixture("counterparties_showing")
        f.chats.save(ChatSettings(GROUP, EURRUB, 20, 7, fanOut = true))
        val showing = f.requests.create(GROUP, 1L, "bob", Side.OFFER, "EUR", BigDecimal("1000"), EURRUB, 7, "i1")
        val peer = f.requests.create(GROUP, 2L, "ann", Side.BID, "EUR", BigDecimal("1000"), EURRUB, 7)
        f.svc.counterparties(showing).map { it.request.refToken } shouldBe listOf(peer.refToken)
    }
```

- [ ] **Step 2: Run them to make sure they fail**

Run: `./gradlew test --tests 'fxbot.RequestServiceTest' --tests 'fxbot.InterestServiceTest'`
Expected: FAIL — `Unresolved reference: appeared`, and an empty list from `counterparties`.

- [ ] **Step 3: Owe the private word**

`RequestService.kt`:

```kotlin
sealed interface PostResult {
    data class Posted(
        val request: Request,
        val found: List<Counterparty>,
        val status: RateStatus,
        /**
         * Counterparties who came in privately and must be told there. Somebody who typed
         * in this chat is told by the message this post produces, in the chat they typed in;
         * somebody whose showing this matched never typed here and may have it muted.
         */
        val appeared: List<CounterpartyAppeared>,
    ) : PostResult

    data class Rejected(val reason: String) : PostResult
}
```

and at the end of `post`:

```kotlin
        val found = findCounterparties(request, requests.resting(chatId), status.rate, chat.tolerancePct)
        return PostResult.Posted(
            request, found, status,
            found.filter { it.request.spokePrivately() }
                .map { CounterpartyAppeared(it.request.userId, it.request.refToken) },
        )
```

In `Commands.handlePost`, after the message is recorded:

```kotlin
            Registry.batcher.enqueueAppeared(result.appeared)
```

- [ ] **Step 4: Let counterparties answer for a showing**

`InterestService.counterparties`:

```kotlin
    /**
     * The counterparties resting for [subject], judged the way its own scope judges: each
     * person's own size tolerance for a request with no chat behind it, the chat's own for a
     * showing or a request typed there.
     */
    fun counterparties(subject: Request): List<Counterparty> {
        val rate = rates.status(subject.pair).rate
        val resting = requests.resting(subject.chatId)
        return if (subject.chatId == NO_CHAT_ID) {
            findCounterparties(
                subject, resting, rate, people.get(subject.userId).tolerancePct,
                peerTolerancePct = { people.get(it.userId).tolerancePct },
            )
        } else {
            findCounterparties(subject, resting, rate, chats.get(subject.chatId).tolerancePct)
        }
    }
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A src
git commit -m "feat: tell a privately stated counterparty privately when a group post finds them"
```

---

### Task 11: Vocabulary, help, and the two ADRs

**Files:**
- Modify: `CONTEXT.md`, `docs/adr/0007-no-names-working-and-mutual-name-give-up.md`, `src/main/kotlin/fxbot/PrivateCommands.kt`, `src/main/kotlin/fxbot/Commands.kt`, `src/main/kotlin/fxbot/Main.kt`
- Create: `docs/adr/0008-counterparties-are-named-on-sight.md`, `docs/adr/0009-a-done-is-confirmed-by-the-counterparty.md`

**Interfaces:**
- Consumes: everything above.
- Produces: no code interfaces — the help strings and the glossary.

- [ ] **Step 1: Amend CONTEXT.md**

Delete the **No-names basis** and **Name give-up** entries entirely. Amend **Interest** so it no longer names them:

```markdown
**Interest**:
One person's stated willingness, given to the bot once. A request typed in a
chat is an interest with a single showing; an interest stated to the bot
privately is shown in several places at once and is also worked against people
the person shares no chat with.
_Avoid_: parent request, master request, order
```

Amend **Done**, and add **Confirmation** directly after it:

```markdown
**Done**:
The state two counterparties reach when they confirm the exchange actually
happened. Either of them may declare it; it takes effect only when the other
confirms, and it closes both requests. The word is the OTC confirmation term,
and deliberately not *filled* — nothing was executed by anyone but the two
people.
_Avoid_: filled, executed, settled, completed, fulfilled

**Confirmation**:
The counterparty's answer to a declared done. Until it arrives nothing closes
and both requests keep resting.
_Avoid_: approval, acceptance, acknowledgement, verification
```

Amend **Size tolerance**'s last sentence — it says "for no-names working":

```markdown
A chat sets one for its showings; a person sets their own for the requests they
rest with no chat behind them, and it never overrides a chat's.
```

- [ ] **Step 2: Supersede ADR 0007 and write the two that replace it**

At the top of `docs/adr/0007-no-names-working-and-mutual-name-give-up.md`, immediately under the title:

```markdown
> **Superseded** by ADR 0008 and ADR 0009 (2026-09-07), before either half of it
> shipped. Kept because the reasoning it records is what ADR 0008 argues against.
```

Create `docs/adr/0008-counterparties-are-named-on-sight.md`, in the house style of the existing ADRs (a prose statement, then `## Consequences`). It must cover: that a counterparty is named in the message that suggests them, to strangers as readily as to groupmates; that nothing new is stored to do it, because the request payload already seals a `username`; that the handle requirement is dropped because the Bot API guarantees a `tg://user?id=` mention works for anyone who has contacted the bot or pressed one of its buttons, which is everyone the bot names; and that the Forwarded Messages privacy setting is the accepted, undetectable residual — such a person's name renders as plain text and the reader cannot tap through.

Create `docs/adr/0009-a-done-is-confirmed-by-the-counterparty.md` covering: that a declaration alone closes nothing; that both requests keep resting while an ask is outstanding, because a suggestion reserves nothing (ADR 0001) and freezing on an unanswered question would let anyone freeze anyone; that a No closes nothing and is not a statement about the two people; that a second No blocks a third ask from that declarer only, and dies with either request, so no durable record of who refused whom is kept; and that no table records an outstanding ask, because the two ref tokens on the buttons plus live rows re-derive everything a press needs.

- [ ] **Step 3: Fix the help and the command menus**

`PrivateCommands.PRIVATE_HELP_TEXT`:

```kotlin
    /done a1 — you two swapped; I'll ask them to confirm
```

`Commands.HELP_TEXT` — the `/done` line becomes the same wording, and drops the `@someone`.

`LifecycleCommands.done` — the missing-argument hint becomes:

```kotlin
        message { "Which one? Try /done a1 — /status lists them." }.send(chat.id, bot)
```

`Main.GROUP_COMMANDS` and `Main.PRIVATE_COMMANDS` — the `done` description becomes `"You two swapped — I'll ask them"`.

- [ ] **Step 4: Run the whole suite**

Run: `./gradlew test`
Expected: PASS. `MainTest`-style assertions on the command menus, if any, need the new descriptions.

- [ ] **Step 5: Check the retired vocabulary is really gone**

```bash
grep -rni 'no-names\|no names\|give-up\|giveup\|give up' src CONTEXT.md
```
Expected: no hits in `src/` or `CONTEXT.md`. Hits in `docs/adr/0006`, `docs/adr/0007` and the two spec files are expected and correct — those record what was decided at the time.

- [ ] **Step 6: Commit**

```bash
git add -A src CONTEXT.md docs
git commit -m "docs: retire the give-up vocabulary and record the two decisions replacing it"
```

---

## Self-review against the spec

| Spec section | Task |
|---|---|
| Everyone is named — service, repository, table, callbacks, buttons deleted | 9 |
| `LifecycleService.done` loses the `bothOffered` guard | 5 |
| Nothing new is stored; `Render.mention` unchanged | 2 |
| `NameLookup`/`Handle` move to `Render.kt`; `Party`/`GiveUpResult` go | 2, 9 |
| Handle requirement dropped: `introducible`, `NO_ROUTE` deleted | 9 |
| The two surfaces still do not meet | 9 (the suppression deleted is the *other* rule; the surfaces stay apart because `Matcher` filters on `chatId`) |
| Suppression rule replaced by the delivery rule | 9, 10 |
| Every done asks; both keep resting; ask goes where they spoke | 5, 7 |
| On Yes, `closeBothWhole` and the residual, unchanged | 5 |
| No timer, no sweeping of an unanswered ask | 5 (nothing is written, so nothing sweeps) |
| Handle optional: one / several / none | 6 |
| `ActionResult.Ok`'s "Marked done." branch and the `theirs == null` arm go | 5 |
| Two refusals end it, one direction, scoped to the pairing | 4, 5 |
| `V3` edited in place; `name_give_up` → `done_refusal` | 3, 9 |
| No table for an outstanding ask; callback data advisory | 5, 7 |
| `/forget` reaches `done_refusal` | 9 |
| Vocabulary: `CONTEXT.md`, **Confirmation** added | 1, 11 |
| ADR 0008 and 0009; 0007 superseded | 11 |
| Testing — naming, happy path, refused, unanswered, no handle, authorization, delivery, migration, forgetting | 2, 5, 6, 7, 8, 9, 10 |

**Deviation, stated once:** the spec's artwork says a private reply names which group a counterparty is in ("He's in Belgrade Expats"). The bot stores no chat title and this plan does not start storing one, so the reply says how many groups the interest rests in instead. Everything else in the artwork is implemented.
