# Exchange Bot — Design

**Date:** 2026-08-30
**Status:** Approved design, not yet implemented

A Telegram bot that connects people in the same chat who want to exchange
currency with each other. Someone posts what they are giving away; the bot
replies with compatible open requests from that same chat, naming the
counterparts. Nobody is matched across chats.

## Scope

The bot is a noticeboard with arithmetic, not a marketplace. It does not hold
funds, quote prices, take a fee, or record settlement. It suggests who to talk
to; the two people agree the actual rate between themselves.

## Core Model

### Request

A request states what a person is **giving away**. Direction is therefore
implied by the currency, not by the verb:

- `/sell 1000 EUR` — giving 1000 EUR, wanting RUB
- `/sell 95000 RUB` — giving 95000 RUB, wanting EUR

Those two are counterparts. `/buy` is sugar for the same thing stated from the
other end (see Commands).

Fields: `chat_id`, `user_id`, `username`, `side_currency`, `amount`,
`stated_currency`, `stated_amount`, `pair`, `state`, `created_at`,
`expires_at`, plus a short display id.

The pair is stamped on the row at creation. An admin changing the chat's pair
therefore orphans nothing: existing requests keep their own pair, stop matching
newly created ones, and expire on their TTL.

### States

```
OPEN ──/cancel──> CANCELLED
 │  ──/done────> FULFILLED     (both sides, in one transaction)
 │  ──sweep────> EXPIRED
 └<─ /reopen ── FULFILLED | CANCELLED   (fresh TTL)
```

There is no `MATCHED` state. Pairing is advisory: a suggested request stays
OPEN and can be suggested to other people too.

### Short ids

2–3 characters, base32 of a per-chat counter. Unique only among that chat's
non-terminal requests, so ids are recycled and stay short. Used as
`/cancel a1`, `/done a1 @alice`.

### ChatSettings

`chat_id`, `base_ccy`, `quote_ccy`, `tolerance_pct` (default 20),
`ttl_days` (default 7). The row is created lazily on the chat's first command
with defaults EUR/RUB.

## Matching

### Rule

Two requests are compatible when all hold:

- same chat
- same pair
- both `OPEN`
- different `side_currency` (opposite sides of the trade)
- different `user_id` (no self-match)
- normalized amounts within the band:
  `|norm_a − norm_b| / max(norm_a, norm_b) ≤ tolerance_pct / 100`

Normalization converts to the pair's base currency: an amount already in base
is used as-is; an amount in quote is divided by the reference rate.

There is no partial fill and no remainder. A request matches whole or not at
all.

### Placement

Matching is a query-on-command, not a background job and not a stored
suggestion table. On `/sell`, `/buy`, and `/status` the bot fetches that chat's
OPEN rows and runs:

```
findCounterparts(request, openRows, rate, tolerancePct): List<Match>
```

sorted by relative distance, capped at 5 results. The function is pure — no
DB, no network, no Telegram — and carries the bulk of the test suite. SQL does
no comparison beyond fetching the chat's open set; at chat scale, filtering in
Kotlin is both faster to write and far easier to test than a clever query.

Consequence accepted deliberately: the same counterparts are re-listed each
time someone asks. In a pool the size of one chat that is useful repetition,
not spam.

### Reference rate

Cross-denomination matching (1000 EUR against 95000 RUB) needs a rate.

Source: `open.er-api.com/v6/latest/{base}` — free, no API key, and it still
carries RUB. ECB, and therefore frankfurter.app, dropped RUB in 2022 and cannot
be used here.

Cached in `fx_rate(base, quote, rate, fetched_at)`, refreshed once a day by a
db-scheduler task for every pair currently in use.

Degradation, in order:

1. Fresh cached rate — normal operation.
2. Feed unreachable, cached rate present — use it. If it is older than 7 days,
   append `rate from <date>, may be stale` to the reply.
3. No cached rate at all — reply that cross-currency matching is unavailable
   for now. The request is still registered, and same-denomination matching
   still runs, because that path needs no rate.

## Commands

```
/sell <amount> <ccy>     open a request: giving <amount> of <ccy>
/buy  <amount> <ccy>     same, stated from the wanting end
/cancel <id>             own request -> CANCELLED
/done <id> @peer         both requests -> FULFILLED, announced publicly
/reopen <id>             terminal -> OPEN with a fresh TTL
/status                  this chat's open requests, own ones flagged
/settings                read-only, anyone
/pair <base> <quote>     admin only
/tolerance <pct>         admin only
/ttl <days>              admin only
```

Every command is scoped by the `chat_id` on the incoming message. That single
fact enforces "only connect people from one chat" — no cross-chat query exists
anywhere in the codebase. In a private chat the bot replies once with "add me
to a group" and does nothing else.

`/buy 1000 EUR` in an EUR/RUB chat means *wanting* 1000 EUR, so it is stored as
`side_currency = RUB` with the amount converted at the reference rate. When no
rate is cached, `/buy` stated in the currency the user is not giving is
rejected with a nudge to restate it as what they are giving.

Because that conversion would otherwise make the bot echo a request back in
words its author never used, the row also carries `stated_amount` and
`stated_currency` — the literal form the person typed. Matching uses the
normalized `side_currency`/`amount`; every listing and confirmation displays
the stated form, with the converted figure appended in parentheses when the two
differ.

Rejections, each a single line naming the fix: unknown currency, currency not
in the chat's pair, non-positive amount, unparseable amount, unknown id, id
belonging to someone else.

`/done` with a peer who has no open request in the chat closes only the
caller's own request and says so.

Peers are resolved from Telegram message entities, never from display-name
text, so a typo cannot close the wrong person's request. Three accepted forms,
in order:

1. a `mention` entity (`@alice`) — resolved against the chat's known users
2. a `text_mention` entity — carries a `user_id` directly, which is how a
   peer *without* a `@username` is named
3. `/done <id>` sent as a reply to the peer's message — the peer is the
   replied-to message's sender

Anything else is rejected with a line explaining those three forms.

## Replies

The bot replies publicly in the group, as a reply to the request message,
listing up to 5 compatible requests closest first:

```
@bob: /sell 1000 EUR
  bot: 2 counterparts:
       • @alice — buy 900 EUR
       • @carol — sell 95000 RUB (≈1000 EUR)
       No fit? You're on the waitlist.
```

The public `@mention` is itself the notification to the waiting side, so no
separate ping mechanism exists.

Users without a `@username` are rendered as an inline `tg://user?id=` mention.
Everything keys on `user_id`; usernames are display-only and refreshed on every
message seen from that user.

## Permissions

`/pair`, `/tolerance` and `/ttl` call `getChatMember` and require status
`creator` or `administrator`. If that API call fails the command is denied with
the reason — it never fails open.

## Encryption at Rest

Two layers, both keyed from the environment, both failing loudly at startup.
There is no code path that silently opens an unencrypted database.

### Layer 1 — H2 file cipher

```
jdbc:h2:file:./data/exchange;CIPHER=AES
Hikari password = "$DB_FILE_KEY $DB_USER_PW"   (H2 syntax: file pw, space, user pw)
```

Covers the whole file including indexes and backups. No application code.

Known limit: H2's file cipher provides confidentiality but not integrity — it
has no MAC. Layer 2 supplies integrity for the sensitive fields.

### Layer 2 — column encryption

JDK standard library only: `AES/GCM/NoPadding`, `HmacSHA256`, `SecureRandom`.
No new dependency.

One 32-byte master key from the environment is split by HKDF into `k_enc`
(confidentiality) and `k_idx` (searchable refs), so the two paths never share
key material.

```
request(
  row_id      BIGINT PK
  chat_ref    CHAR(44)     HMAC-SHA256(k_idx, chat_id)     -- searchable
  user_ref    CHAR(44)     HMAC-SHA256(k_idx, user_id)     -- searchable
  short_id    VARCHAR(4)                                   -- plaintext
  state       VARCHAR                                      -- plaintext
  created_at  TIMESTAMP                                    -- plaintext
  expires_at  TIMESTAMP                                    -- plaintext
  payload     VARBINARY    nonce || AES-256-GCM ciphertext
  key_version SMALLINT
)
```

`payload` holds `{user_id, username, side_currency, amount, stated_amount,
stated_currency, base, quote}` as
JSON. AAD is `chat_ref || short_id`, binding a ciphertext to its row so a blob
cannot be relocated to another row.

`short_id`, `state` and the timestamps stay plaintext: the first is meaningless
without its chat, and the sweep must filter on the others.

`chat_settings` gets the same treatment (`chat_ref` plus payload). `fx_rate`
stays plaintext — it is public data.

Query cost is zero, because matching already works the way this requires: SQL
selects by `chat_ref` and `state` only, and every comparison happens in Kotlin
over decrypted rows.

`key_version` ships as a column now. A rotation tool does not — it gets written
when a key actually needs rotating.

### What this does not buy

The refs are deterministic. Anyone holding both the database file and `k_idx`
can count per-chat and per-user activity and can confirm a *guessed* chat or
user id. That is the inherent price of searchable encryption. Without `k_idx`,
which never leaves the process, those attacks fail.

Range queries on amount are impossible under this scheme. The design never
needed them.

Neither layer protects data while the process is running: the keys are in
process memory and rows are decrypted there.

### Key handling

```
BOT_TOKEN      telegram bot token
DB_FILE_KEY    H2 file password
DB_USER_PW     H2 user password
DATA_KEY       base64, must decode to exactly 32 bytes   (openssl rand -base64 32)
```

Startup validates that all four are present and that `DATA_KEY` decodes to
exactly 32 bytes; otherwise the process exits non-zero naming the missing or
malformed variable. Keys are never logged and no command echoes them. `.env` is
gitignored; `.env.example` is committed with empty values. Docker receives them
through `env_file` or secrets, never baked into an image.

## Concurrency

One process, but Telegram updates are handled concurrently. Request creation
and `/done` run inside a single transaction whose state transition is guarded
by `WHERE state = 'OPEN'`. A double `/done` therefore closes once, and the
second attempt reports "already fulfilled".

## Expiry

A daily db-scheduler task flips `OPEN` rows past `expires_at` to `EXPIRED`.
No message is sent; the change shows up in `/status`. TTL is per chat,
default 7 days.

## Stack

Modelled on the neighbouring `conference-notifier-bot`, minus its native-image
layer:

- Kotlin/JVM, JDK 21 toolchain
- `eu.vendeli:telegram-bot` with the `ktnip` KSP processor
- H2 file database, HikariCP
- db-scheduler for the TTL sweep and the daily rate refresh
- Ktor client (CIO) for the rate feed
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
  same-side exclusion, ordering and the cap at 5.
- **Command parser** — every rejection case above, plus `/buy` conversion.
- **Short ids** — recycling after a request reaches a terminal state.
- **Crypto** — round trip, wrong key, tampered ciphertext, wrong AAD.
- **Repository** — in-memory H2 with a fixed test key; asserts that a raw
  `SELECT payload` never contains a username in the clear.
- **Rate client** — `ktor-client-mock`: happy path, HTTP failure with a warm
  cache, HTTP failure with a cold cache, stale-cache annotation.

The Telegram transport is not mocked end to end. Handlers are thin and delegate
to the tested functions above.

## Deliberately Excluded

- Partial fills and remainders
- An order book, rate quoting, or price-time priority
- Exclusive pairing and a `MATCHED` state
- Cross-chat matching
- A persisted suggestion table
- A background matcher job
- DM-based notification
- A key rotation tool
- GraalVM native image
