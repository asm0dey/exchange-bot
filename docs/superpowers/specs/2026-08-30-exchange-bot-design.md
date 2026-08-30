# Exchange Bot — Design

**Date:** 2026-08-30
**Status:** Approved design, grilled across six rounds. Not yet implemented.

A Telegram bot that connects people in the same chat who want to exchange
currency with each other. Someone posts what they are giving away; the bot
replies with compatible open requests from that same chat, naming the
counterpartyies and offering a button to close the deal. Nobody is matched across
chats.

## Scope

The bot is a noticeboard with arithmetic, not a marketplace. It does not hold
funds, quote prices, take a fee, or record settlement. It suggests who to talk
to; the two people agree the actual rate between themselves.

Groups and supergroups only. Channels are rejected with a single message —
posts there come from a channel identity rather than a person, so there is no
user to match, mention, or authorize. Private chats serve `/forget all` and
otherwise reply once with "add me to a group".

Bot language is English only.

## Core Model

### Request

A request has a **side**, and there are only two: **Offer** gives the base
currency, **Bid** receives it. Which one a command means depends on the verb
and the currency together, never on either alone:

| Command           | Wants | Gives | Side   |
|-------------------|-------|-------|--------|
| `/sell 1000 EUR`  | RUB   | EUR   | Offer  |
| `/buy 95000 RUB`  | RUB   | EUR   | Offer  |
| `/sell 95000 RUB` | EUR   | RUB   | Bid    |
| `/buy 1000 EUR`   | EUR   | RUB   | Bid    |

Four ways to say two things. Two requests are counterpartyies when their sides
differ — so `/sell` can match `/sell`, `/buy` can match `/buy`, and the verb on
its own tells you nothing.

Derivation at parse time is one line: the side is **Offer** when the verb is
`sell` and the stated currency is the base, or the verb is `buy` and the stated
currency is the quote; otherwise **Bid**. Equivalently — you are offering
exactly when you hand over the base currency.

Each request carries three identifiers, for three different jobs:

| Identifier  | Visibility      | Recycled | Purpose                         |
|-------------|-----------------|----------|---------------------------------|
| `row_id`    | internal only   | never    | surrogate key, joins            |
| `short_id`  | shown in chat   | yes      | typed commands (`/cancel a1`)   |
| `ref_token` | **never shown** | never    | callback payloads, AEAD context |

`short_id` is 2–3 characters, base32 of a per-chat counter, unique only among
that chat's non-terminal requests. Recycling keeps it short enough to type.

`ref_token` is 128 bits of `SecureRandom`, base64url-encoded to 22 characters.
It never appears in any message, which keeps callback payloads unguessable — a
modified client cannot enumerate sequential ids to probe which requests exist.

Fields: `chat_id`, `user_id`, `username`, `side`, `stated_currency`,
`stated_amount`, `pair`, `state`, `created_at`, `expires_at`.

**Amounts are stored exactly as typed, and a notional is derived only at match
time.** `stated_currency` and `stated_amount` preserve the literal input.
Converting at creation would freeze a `/buy 1000 EUR` into a fixed RUB figure
that drifts away from the stated intent as the rate moves; deriving the
notional on demand means every comparison uses the current rate. A trade's size
is the same magnitude whichever leg quotes it, so the notional is well defined
from either side.

The pair is stamped on the row at creation. An admin changing the chat's pair
therefore orphans nothing: existing requests keep their own pair, stop matching
newly created ones, and expire on their time in force.

There is no cap on how many requests one person may hold open. The reply list
is capped at 5 and `/status` at 20, which bounds the visible effect.

### States

```
OPEN ──/cancel──> CANCELLED
 │  ──/done────> DONE     (both sides, in one transaction)
 │  ──sweep────> EXPIRED
 └<─ /reopen ── DONE | CANCELLED   (clock restarts)
```

There is no `MATCHED` state. Pairing is advisory: a suggested request stays
`OPEN` and can be suggested to other people too.

`/reopen` takes no argument. It revives the caller's **most recently closed**
request in that chat, because `short_id` recycling makes an id-addressed
`/reopen` ambiguous — the id may already belong to somebody else's live
request. It exists for exactly one case: undoing a mistaken `/done`.

### ChatSettings

`chat_id`, `base_ccy`, `quote_ccy`, `tolerance_pct` (default 20),
`tif_days` (default 7). The row is created lazily on the chat's first command
with defaults EUR/RUB.

## Matching

### Rule

Two requests are compatible when all hold:

- same chat
- same pair
- both `OPEN`
- different `side` (one Bid, one Offer)
- different `user_id` (no self-match)
- notionals within the size tolerance:
  `|n_a − n_b| / max(n_a, n_b) ≤ tolerance_pct / 100`

A notional is the **stated** amount expressed in the pair's base currency: an
amount already in base is used as-is; an amount in quote is divided by the
current reference rate.

Worked example, at a rate of 99.98 and a 20% band:

```
/sell 999 EUR   -> Offer, notional = 999 EUR
/buy  1000 EUR  -> Bid,   notional = 1000 EUR
                   |1000 - 999| / 1000 = 0.1%    -> match

/buy  100 RUB   -> Offer, notional = 100 / 99.98 = 1.0002 EUR
/buy  1 EUR     -> Bid,   notional = 1 EUR
                   0.02% apart, sides differ     -> match

/buy  20 RUB    -> Offer, notional = 0.2 EUR
/sell 20 RUB    -> Bid,   notional = 0.2 EUR
                   identical notional, sides differ -> match

/sell 1000 EUR  -> Offer
/buy  95000 RUB -> Offer
                   SAME side                     -> no match
```

The last case is the one worth remembering: identical verbs can be
counterpartyies, and opposite verbs can be the same side.

There is no partial fill and no remainder. A request matches whole or not at
all.

All money is `BigDecimal`, with `MathContext.DECIMAL64` for the rate division.
It is serialized as a **string** in the encrypted payload — a JSON number would
round-trip through a double and undo the point.

### Placement

Matching is a query-on-command: no background matcher, no stored suggestion
table. On `/sell`, `/buy`, and `/status` the bot fetches that chat's `OPEN`
rows and runs:

```
findCounterpartyies(request, openRows, rate, tolerancePct): List<Match>
```

sorted by relative distance, capped at 5. The function is pure — no DB, no
network, no Telegram — and carries the bulk of the test suite. SQL does no
comparison beyond fetching the chat's open set; at chat scale, filtering in
Kotlin is both faster to write and far easier to test than a clever query.

Consequence accepted deliberately: the same counterpartyies are re-listed each
time someone asks. In a pool the size of one chat that is useful repetition,
not spam.

### Reference rate

Cross-denomination matching (1000 EUR against 95000 RUB) needs a rate.

Source: `open.er-api.com/v6/latest/{base}` — free, no API key, updates daily,
and it still carries RUB. **Verified 2026-08-30:** returns `"RUB":99.979239`
with EUR as base, and accepts RUB as a base. ECB — and therefore
frankfurter.app — dropped RUB in 2022 and cannot be used here.

Cached in `fx_rate(base, quote, rate, fetched_at)`, refreshed once a day.

Degradation, in order:

1. Fresh cached rate — normal operation.
2. Feed unreachable, cached rate present — use it. If it is older than 7 days,
   append `rate from <date>, may be stale` to the reply.
3. No cached rate at all — reply that cross-currency matching is unavailable
   for now. Requests of every kind are still registered, and same-denomination
   matching still runs, because that path needs no rate.

### Currency validation

Two gates. `java.util.Currency.getInstance(code)` first — ISO 4217, stdlib,
offline, no dependency. Then, at `/pair` time only, the rate feed: a code that
is ISO-valid but absent from the feed is rejected there, so an admin cannot
configure a pair the bot will never be able to price.

## Commands

```
/sell <amount> <ccy>     open a request: giving <amount> of <ccy>
/buy  <amount> <ccy>     same, stated from the wanting end
/cancel <id>             own request -> CANCELLED
/done <id> @who          both requests -> DONE, announced publicly
/reopen                  revive your most recently closed request, restart its clock
/status [mine]           open requests in this chat
/settings                read-only, anyone
/forget                  hard-delete all your data in this chat
/forget all              private chat only: hard-delete across every chat
/help                    command list
/pair <base> <quote>     admin only
/tolerance <pct>         admin only
/tif <days>              admin only   (time in force)
```

Every command is scoped by the `chat_id` on the incoming message. That single
fact enforces "only connect people from one chat" — no cross-chat query exists
anywhere in the codebase, with the sole deliberate exception of `/forget all`.

`/buy 1000 EUR` in an EUR/RUB chat means *wanting* 1000 EUR, so it stores
`side = BID` while keeping `stated_amount = 1000`, `stated_currency = EUR`.
Because no conversion happens at creation, `/buy` needs no cached rate to be
accepted; it simply cannot be matched across denominations until one exists.

Listings and confirmations always render the stated form, with the notional
appended in parentheses when the two differ — so the bot never echoes a
request back in words its author did not use.

Rejections, each a single line naming the fix: unknown currency, currency not
in the chat's pair, non-positive amount, unparseable amount, unknown id, id
belonging to someone else.

### Counterparty resolution

`/done` resolves its counterparty from Telegram message entities, never from
display-name text, so a typo cannot close the wrong person's request. Accepted
forms, in order:

1. a `mention` entity (`@alice`), resolved **only against users holding an open
   request in this chat** — which is the only population `/done` can validly
   name, so no user directory table is needed
2. a `text_mention` entity, which carries a `user_id` directly and is how a
   counterparty without a `@username` is named
3. `/done <id>` sent as a reply to the counterparty's message

Anything else is rejected with a line explaining those three forms. A named
counterparty with no open request closes only the caller's own request, and
says so.

## Replies and Buttons

The bot replies publicly in the group, as a reply to the request message,
listing up to 5 compatible requests closest first, each with a Done button:

```
@bob: /sell 1000 EUR
  bot: 2 counterpartyies:
       • @alice — buy 900 EUR
       • @carol — sell 95,000 RUB (≈950.19 EUR)
       No fit? You're on the waitlist.
       [✅ Done with @alice] [✅ Done with @carol] [✖️ Cancel request]
```

The public `@mention` is itself the notification to the waiting side, so no
separate ping mechanism exists. Users without a `@username` render as an inline
`tg://user?id=` mention. Everything keys on `user_id`; usernames are
display-only, refreshed whenever the bot sees a message from that user.

Buttons appear on the request reply only — not on `/status` rows, not on
`/settings`. The button is where the decision is, because the bot has just
listed who the counterpartyies are. A Done confirmation additionally carries a
`↩️ Reopen` button.

Number formatting: `BigDecimal` with trailing zeros stripped and thousands
grouped (`1,000 EUR`, `95,000 RUB`); notionals in parentheses at 2dp
(`≈950.19 EUR`); dates as `30 Aug`.

`/status` lists the 20 most recent open requests, closest-to-expiry first, with
a `+N more — use /status mine` footer. Telegram caps a message at 4096
characters and nothing caps requests per user, so an uncapped listing would
eventually fail to send outright.

### Callback handling

`callback_data` is limited to 64 bytes (**verified** against the Bot API docs)
and, more importantly, **is not trustworthy**: a modified client can send an
arbitrary payload. The button is a UI suggestion, never an authorization.

Payload format — two `ref_token`s, about 47 bytes:

```
d:<ref_token>:<ref_token>     done
c:<ref_token>                 cancel
r:<ref_token>                 reopen
```

`ref_token` rather than `short_id` because short ids are recycled, so a button
pressed days later could otherwise act on a stranger's request.

Authorization is re-derived server-side from `callback_query.from.id` on every
press:

- **Done** — permitted if the presser owns **either** referenced request.
  Either counterparty may confirm the trade happened.
- **Cancel**, **Reopen** — owner only.

A press by anyone else gets an `answerCallbackQuery` alert visible only to
them. Buttons are visible to the whole group, so this path will be exercised.

### Stale buttons

Two mechanisms, belt and braces:

**Proactive.** When a request goes terminal, the bot edits the messages
carrying buttons for it, stripping those buttons. Fan-out is capped at the 10
most recent such messages, so one `/cancel` cannot turn into a rate-limit
stall.

**On press.** A press referencing a request that is no longer `OPEN` is
rejected with an `answerCallbackQuery` alert ("@alice's request is no longer
open") and the message is edited in place to re-run matching and show the
current list. The button that failed is replaced by ones that work, so stale
messages self-heal instead of accumulating. This also covers anything the
proactive pass missed.

## Permissions

`/pair`, `/tolerance` and `/tif` call `getChatMember` and require status
`creator` or `administrator`. If that API call fails the command is denied with
the reason — it never fails open.

## Encryption at Rest

Two layers, both keyed from the environment, both failing loudly at startup.
There is no code path that silently opens an unencrypted database.

### Layer 1 — H2 file cipher

```
jdbc:h2:file:./data/exchange;CIPHER=AES;MODE=PostgreSQL
Hikari password = "$DB_FILE_KEY $DB_USER_PW"   (H2 syntax: file pw, space, user pw)
```

Covers the whole file including indexes and backups. No application code.
Flyway connects through the same DataSource, so migrations run inside the
cipher.

Known limit: H2's file cipher provides confidentiality but not integrity — it
has no MAC. Layer 2 supplies integrity for the sensitive fields.

### Layer 2 — Google Tink

`com.google.crypto.tink:tink` (1.18.0, June 2025) — pure Java, no native
library, no Dockerfile changes.

Two keysets, so the confidential and searchable paths never share key material:

- **AEAD keyset** (`AES256_GCM`) encrypts row payloads:
  `Aead.encrypt(plaintext, associatedData)`.
- **MAC keyset** (`HMAC_SHA256`) computes the searchable refs.

Both are Tink JSON keysets produced by `tinkey create-keyset`, supplied as
environment variables and parsed with `TinkJsonProtoKeysetFormat.parseKeyset`.

Tink's keyset model carries key ids and rotation state, which is why there is
no `key_version` column and no bespoke rotation tool: rotating the AEAD key is
`tinkey rotate-keyset` plus a redeploy retaining the old key for decryption,
with no re-encryption pass. Rotating the **MAC** key is not free the same way —
every `chat_ref` and `user_ref` must be recomputed, which requires decrypting
each payload to recover the underlying ids. That remains possible as a one-off
job; it is simply not automatic.

*Considered and rejected:* Cossack Labs Themis. Its Secure Cell Seal mode is
the same primitive (AES-256-GCM, packed nonce, `context` as AAD) with an
equally good API, but `java-themis` is a JNI binding whose JAR does not bundle
the native library — libthemis must be installed via apt/yum/brew, only x86_64
is documented, there is no Alpine or Docker guidance, and the JVM binding's
last release is 0.15.2 from September 2023. Themis earns its native dependency
when the same ciphertext must be read by Swift, Go, or Python clients; this bot
has one JVM process and no second language.

### Schema

```
request(
  row_id      BIGINT IDENTITY PK
  ref_token   TEXT UNIQUE NOT NULL       -- 128-bit random, base64url, never shown
  chat_ref    TEXT NOT NULL               -- Tink MAC(chat_id), searchable
  user_ref    TEXT NOT NULL               -- Tink MAC(user_id), searchable
  short_id    TEXT NOT NULL               -- plaintext, meaningless without its chat
  state       TEXT NOT NULL               -- plaintext, not sensitive
  created_at  TIMESTAMP  NOT NULL         -- plaintext, sweep needs it
  expires_at  TIMESTAMP  NOT NULL         -- plaintext, sweep needs it
  payload     BYTEA NOT NULL              -- Tink AEAD
)
indexes: (chat_ref, state), (user_ref), unique(ref_token)

chat_settings(chat_ref PK, payload, updated_at)
fx_rate(base, quote, rate, fetched_at)          -- plaintext, public data
sent_message(chat_ref, message_id, sent_at, PK(chat_ref, message_id))
sent_message_ref(chat_ref, message_id, ref_token, user_ref)
```

`payload` holds `{chat_id, user_id, username, side, stated_currency,
stated_amount, base, quote}` as JSON. The chat id is sealed rather than left out
so that rotating the MAC keyset can re-derive every `chat_ref`; without it the
index would be unrecoverable and the rotation story below would be false.

**Associated data is `ref_token`.** It is unique, never recycled, and
independent of the chat, so a ciphertext cannot be relocated to another row and
a chat migration does not invalidate it. (`chat_settings` uses `chat_ref` as
its AAD, so migration re-encrypts that single row.)

`short_id` uniqueness among a chat's non-terminal requests cannot be a partial
unique index — H2 has none — so it is enforced in application code inside the
insert transaction.

Query cost is unaffected, because matching already works the way this requires:
SQL selects by `chat_ref` and `state`, and every comparison happens in Kotlin
over decrypted rows.

### What this does not buy

The refs are deterministic. Anyone holding both the database file and the MAC
keyset can count per-chat and per-user activity and can confirm a *guessed*
chat or user id. That is the inherent price of searchable encryption. Without
the MAC keyset, which never leaves the process, those attacks fail.

Range queries on amount are impossible under this scheme. The design never
needed them.

Neither layer protects data while the process is running: the keys are in
process memory and rows are decrypted there.

### Environment

```
BOT_TOKEN        telegram bot token
DB_FILE_KEY      H2 file password
DB_USER_PW       H2 user password
DATA_KEYSET      Tink AEAD keyset, JSON  (tinkey create-keyset --key-template AES256_GCM)
INDEX_KEYSET     Tink MAC keyset, JSON   (tinkey create-keyset --key-template HMAC_SHA256_256BITTAG)
```

Startup validates that all five are present and that both keysets parse;
otherwise the process exits non-zero naming the missing or malformed variable.
Keys are never logged and no command echoes them. `.env` is gitignored;
`.env.example` is committed with empty values. Docker receives them through
`env_file` or secrets, never baked into an image.

## Data Deletion

`/forget` in a group hard-`DELETE`s every row belonging to that user in that
chat, terminal ones included — Expiry only flips state, which leaves the payload
in the file.

`/forget all`, accepted only in a private chat with the bot, deletes that
user's rows across every chat. It is a single `DELETE WHERE user_ref = ?`,
because `user_ref` is chat-independent. Restricting it to a DM keeps a group
command from silently reaching into other groups.

Both then clean up the bot's own messages that named the user, best-effort:

- Messages where that person was one of several named: **edited**, replacing the
  body with a neutral placeholder and keeping the buttons. The other names in the
  text do not survive — the bot never stored the rendered text, so there is
  nothing to rebuild a partial message from. Editing is still the right call here
  because it works on messages far older than 48 hours, which deletion cannot
  touch.
- Messages entirely about them: **deleted** where possible.

**Verified Bot API limits (2026-08-30):** `deleteMessage` — *"A message can
only be deleted if it was sent less than 48 hours ago."* `editMessageText` has
no such window for a bot's own messages; the 48-hour clause there applies only
to business messages the bot did not send. So redaction reaches back much
further than deletion does, and batch `deleteMessages` covers the ≤48h case.

Nobody is notified — a notification would broadcast the very fact the person
asked to erase. Dead buttons pointing at deleted rows fall into the
stale-button rejection path.

**Stated limitation:** `/forget` removes stored data and cleans up what the bot
can still reach. It does not un-say what was said in the chat, and it cannot
touch other people's messages.

### Message tracking

`sent_message` plus the `sent_message_ref` join table exist to answer two
queries: *which messages named this user* (for `/forget`) and *which messages
carry buttons for this request* (for proactive stripping).

Retention: 90 days, pruned daily. Deletion only works inside 48h, but
redaction-by-edit works across the whole window, so retention follows the
longer horizon. Bounded growth matters here because the table holds `user_ref`s.

## Chat Migration

When a group is upgraded to a supergroup, Telegram issues a **new** `chat_id`
and sends `migrate_to_chat_id` once. Every row keys on `MAC(chat_id)`, so
without handling this, every open request and the chat's settings become
unreachable at the least predictable moment.

The handler rewrites `chat_ref` on that chat's `request`, `sent_message` and
`sent_message_ref` rows in one transaction, resealing each payload that carries
the chat id. The AAD is the ref token and does not change, so every token stays
valid across the migration; only `chat_settings`, whose AAD *is* `chat_ref`, is
resealed under new associated data.

## Concurrency

One process, but Telegram updates are handled concurrently. Request creation,
`/done`, and every callback run inside a single transaction whose state
transition is guarded by `WHERE state = 'OPEN'`. A double `/done` — or the same
button pressed by both counterparties at once — therefore closes once, and the
loser reports "already fulfilled".

## Scheduled Work

Two db-scheduler tasks, both **parameterless**:

- **Expiry sweep** — daily, `UPDATE ... WHERE state='OPEN' AND expires_at < now`,
  plus the 90-day `sent_message` prune. No message is sent; the change shows up
  in `/status`.
- **Rate refresh** — daily, enumerates the pairs in use itself.

Parameterless matters for more than tidiness: db-scheduler's `scheduled_tasks`
table stores `task_data` as a plaintext BLOB. Tasks that carried a `chat_id` or
a pair would leak around Layer 2. With no parameters, `task_data` is always
empty and there is nothing to encrypt.

## Stack

Modelled on the neighbouring `conference-notifier-bot`, minus its native-image
layer:

- Kotlin/JVM, JDK 21 toolchain
- `eu.vendeli:telegram-bot` with the `ktnip` KSP processor
- H2 file database, HikariCP
- **Flyway** for migrations, with Java-based migrations available for
  decrypt-transform-re-encrypt steps that DDL cannot express
- db-scheduler for the expiry sweep and the daily rate refresh
- Ktor client (CIO) for the rate feed
- Google Tink for column encryption
- kotlinx-serialization, kotlinx-coroutines, slf4j-simple
- kotest for tests, `ktor-client-mock` for the rate client
- Dockerfile on a JRE base

GraalVM native-image is deliberately excluded. In the neighbouring project it
cost reachability-metadata regeneration and `--initialize-at-build-time` tuning
on every telegram-bot bump, for a benefit this bot does not need.

## Testing

Pure functions carry the suite:

- **Matcher** — tolerance boundaries exactly at the band edge, both
  denominations, cross-denomination through the rate, self-match exclusion,
  ordering and the cap at 5. All four command forms against all four, asserting
  the side table: `/sell 999 EUR` ↔ `/buy 1000 EUR`, `/buy 100 RUB` ↔
  `/buy 1 EUR`, `/buy 20 RUB` ↔ `/sell 20 RUB`, and `/sell 1000 EUR` **not**
  matching `/buy 95000 RUB`.
- **Deferred conversion** — a `/buy` request normalizes differently after the
  rate changes, and always tracks its stated amount rather than a frozen one.
- **Command parser** — every rejection case above, plus `/buy` accepted with no
  cached rate.
- **Short ids** — recycling after a request reaches a terminal state, and the
  in-transaction uniqueness guard.
- **Callback authorization** — forged `callback_data`, a third party pressing
  Done, either counterparty pressing Done, a token for a terminal request.
- **Crypto** — round trip, wrong key, tampered ciphertext, wrong AAD, and that
  a `ref_token` cannot decrypt another row's payload.
- **Repository** — in-memory H2 with fixed test keysets; asserts that a raw
  `SELECT payload` never contains a username in the clear.
- **Rate client** — `ktor-client-mock`: happy path, HTTP failure with a warm
  cache, HTTP failure with a cold cache, stale-cache annotation.
- **Migration handler** — `chat_ref` rewrite leaves payloads decryptable.
- **`/forget`** — rows gone, multi-subject message redacted rather than
  deleted, single-subject message deleted.

The Telegram transport is not mocked end to end. Handlers are thin and delegate
to the tested functions above.

## Decision Log

| #  | Decision |
|----|----------|
| 1  | Google Tink for column crypto; Themis rejected over its JNI native dependency |
| 2  | `/reopen` takes no id (short ids recycle); inline buttons added |
| 3  | `@mention` resolved only against open-request holders — no user directory |
| 4  | `BigDecimal`, serialized as a string |
| 5  | `java.util.Currency` first, rate feed as a second gate at `/pair` |
| 6  | Handle `migrate_to_chat_id`; rewrite `chat_ref` in one transaction |
| 7  | No per-user request cap |
| 8  | English only — no i18n layer, no `/lang` |
| 9  | Two Tink keysets (AEAD + MAC) supplied as environment variables |
| 10 | Scheduled tasks parameterless, so `task_data` never holds sensitive values |
| 11 | Flyway, with Java migrations for crypto-aware steps |
| 12 | Buttons on the request reply only |
| 13 | `/status` capped at 20 with a `+N more` footer |
| 14 | `/forget` per-chat; `/forget all` in a private chat only |
| 15 | Synthetic `ref_token` in callbacks, never shown in chat |
| 16 | Done pressable by either counterparty; stale presses rejected and re-rendered |
| 17 | Deletion notifies nobody; best-effort message cleanup |
| 18 | Channels rejected |
| 19 | `sent_message` + `sent_message_ref` join table |
| 20 | Proactive button stripping, fan-out capped at 10, lazy backstop |
| 21 | Redact multi-subject messages, delete single-subject ones |
| 22 | 90-day message-tracking retention, pruned daily |
| 23 | Store amounts as stated; derive the notional at match time, never at creation |
| 24 | OTC broking vocabulary in the model (Bid/Offer, notional, counterparty, done, resting, time in force); plain English in user-facing strings; *order*, *book*, *fill* rejected as implying semantics this bot does not offer |

## Deliberately Excluded

- Partial fills and remainders
- An order book, rate quoting, or price-time priority
- Exclusive matching and a `MATCHED` state
- Cross-chat matching (except `/forget all`)
- A persisted suggestion table
- A background matcher job
- DM-based match notification
- Per-user request caps and command rate limiting
- A user directory table
- Internationalization
- A bespoke key rotation tool
- GraalVM native image
