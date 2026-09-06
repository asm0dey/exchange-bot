# Private Interests — Design

**Date:** 2026-09-06
**Status:** Approved design. Not yet implemented.

Today a request only exists in the group chat it was typed in, and a private
chat gets one reply: "add me to a group". This design lets a person state an
interest to the bot privately, has the bot show that interest in every group
they share with it whose pair fits, and works the same interest on a no-names
basis across the whole bot for people who share no group at all. It also lets a
person set their own size tolerance for that no-names working, and redefines
size tolerance so a counterparty smaller than you is a real counterparty rather
than a miss.

## Language

These join the vocabulary in `CONTEXT.md`, in the same OTC broking register.

**Interest**:
One person's stated willingness to exchange, given to the bot once. A request
typed in a group is an interest with a single showing; an interest stated
privately is shown in several places at once.
_Avoid_: parent request, master request, order

**Showing**:
The interest as it exists in one chat — an ordinary Request row, with its own
short id, matched at that chat's size tolerance.
_Avoid_: copy, clone, duplicate, mirror

**No-names basis**:
An interest worked with no chat behind it. Side and stated amount are disclosed
to a possible counterparty; identity is not. Matched bot-wide at the person's
own size tolerance.
_Avoid_: pool, book, market, venue, dark pool

**Name give-up**:
Identities passing between two counterparties once both have agreed to it. The
OTC term for exactly this step, and the operation the README already describes
as name-passing. Nothing before it names anyone.
_Avoid_: reveal, introduction, connect, match

**Residual**:
What is left of your size when a counterparty is smaller than you. Expressed in
your own stated currency, and always relative to your own size.
_Avoid_: remainder, leftover, unfilled, balance

**Size tolerance** is redefined: how large a residual you will accept, as a
percentage of your own size. Chat-level tolerance keeps its meaning and its
admin ownership and governs showings; a person-level tolerance governs only
no-names working. A person-level number never overrides a chat's.

## Matching

### Per-side residual

Each side judges its own residual against its own tolerance:

| | Their size vs yours | Your residual | You accept when |
|---|---|---|---|
| Bigger counterparty | `T > Y` | 0 | always |
| Equal | `T = Y` | 0 | always |
| Smaller counterparty | `T < Y` | `Y − T` | `(Y − T) / Y ≤ your tolerance` |

Sizes are the comparable magnitudes `Matcher` already derives — notionals when a
reference rate exists, stated amounts when both requests are in the same
currency and no rate does.

Worked cases:

| A | B | A's residual | B's residual | Outcome |
|---|---|---|---|---|
| sell 4 EUR @50% | buy 2 EUR @20% | 50% ≤ 50 ✅ | 0 ✅ | counterparties |
| sell 4 EUR @20% | buy 2 EUR @50% | 50% > 20 ❌ | 0 ✅ | not counterparties |
| sell 1000 EUR @20% | buy 999 EUR @20% | 0.1% ≤ 20 ✅ | 0 ✅ | counterparties |

Two people who share a tolerance behave exactly as they do today: for equal
numbers, "each side's own residual" and the current `|a − b| / larger` are the
same test, so every case in `MatcherTest` stands unchanged. The comparison stays
inclusive — a residual exactly equal to the tolerance is accepted, matching the
existing `distance > limit` rejection test.

### No combinations

Counterparties are judged strictly pairwise. The bot never searches for a set of
counterparties that together cover a size — that is subset-sum, and it is not
worth an NP-hard search in a name-passing broker. Two 2-EUR buyers against a
4-EUR seller are simply two suggestions in the list; combining them is the
person's decision, not the bot's arithmetic.

### Residuals after a done

`/done` closes both requests, as it does today, and the bot then offers the
larger side a one-tap button to state the residual as a fresh request. Pressing
it creates a new request with a new short id. The stated amount of the original
is never rewritten — ADR 0003 holds, and the residual is a new thing the person
stated rather than a number the bot invented for them.

The residual is recomputed at press time from the two closed rows, in the
presser's own stated currency, using the reference rate when the two stated
currencies differ. No button is offered when the residual is zero, or when the
currencies differ and no rate is available.

## Stating an interest privately

`inGroupOrExplain` stops being a blanket refusal. Each command gains a private
meaning or keeps the hint:

    /sell 10 EUR for RUB     state an interest
    /buy 10 RUB for EUR      same, other way round
    /tolerance 5             your own size tolerance
    /status                  your interests: where shown, what rests on a no-names basis
    /cancel a1               withdraw the interest, every showing with it
    /done a1                 you swapped, after a name give-up
    /settings                your tolerance
    /pair, /tif              still the group-admin hint

`/tolerance` in a group remains the admin command it is today; only its private
form is new, exactly as `/forget` already means different things in the two
places. `/sell` without `for XXX` is rejected with the example. Bare text with
no leading slash is out of scope.

Posting runs in this order:

1. Parse verb, amount, and both currencies. The no-names request uses a
   canonical pair orientation (the two codes sorted, so always `EUR/RUB`),
   because `Matcher` compares pairs by equality and `buy 10 RUB for EUR` must
   meet `sell 10 EUR for RUB`. The side is derived from the canonical pair by
   the existing `sideFor`.
2. Create the no-names request: sentinel `chatId = 0`, canonical pair, a fresh
   `interest_token`, the default 7-day time in force.
3. Enumerate chats from `chat_settings` (the sealed payload already carries
   `chatId`), keep those whose pair is the same two currencies in either
   orientation, then probe `getChatMember` on each and keep the ones where the
   person is a member. Everything else is skipped.
4. For each surviving chat, create a showing in that chat's own pair
   orientation and time in force, run the existing matcher at that chat's
   tolerance, post the ordinary suggestion message with its buttons, and record
   it in the message log exactly as a typed `/sell` does. A send that fails —
   bot kicked, no posting rights — deletes that showing and counts it as
   skipped.
5. Reply privately: where the interest was shown, how many chats were skipped,
   and the no-names counterparties — side and stated amount only, each with a
   button offering to pass their name.

A `/sell` typed in a group is unchanged: one showing in that chat and nothing
else. Fan-out is a property of stating the interest privately.

## Name give-up

Anyone already resting on a no-names basis is told privately when a new
counterparty appears — side and stated amount, no identity, one button offering
to pass their name. Without that push nobody learns a counterparty arrived and
the no-names side is dead.

Consent is persisted, never carried in the button. Pressing it writes the
presser's row; the bot then asks the other side. When both rows exist, the bot
sends each person the other's `@handle` with Done buttons, and records both
messages in the message log so forgetting can redact them (ADR 0005).

Callback data stays advisory, as `Callbacks.kt` already insists: the presser is
re-derived from `callback_query.from.id` and consent is read from the table, so
a hand-crafted payload claiming the other side agreed achieves nothing. Ref
tokens in the button are random and already treated as non-authorizing.

## Closing

Done, cancel or expiry on any row closes every OPEN row sharing its
`interest_token`, in one transaction, with the same terminal state. Expiry
therefore means the shortest time in force among the participating chats governs
the whole interest. `/reopen` is symmetric: it revives the siblings together
with fresh expiries.

Every message that referenced a closed token is then edited, not merely
stripped: the bot rewrites it to its stored text plus a status line per closed
token — done, withdrawn, or lapsed — and drops the keyboard in the same pass, so
the message survives as readable history instead of a mute block with dead
buttons.

The keyboard stays all-or-nothing, as it is today: a suggestion post naming
several counterparties loses its whole keyboard when any named request closes.
Rebuilding a partial keyboard for the survivors is out of scope.

`ButtonService`'s `FAN_OUT = 10` cap and its reasoning carry over: an edit that
does not land leaves a message stale but harmless, because `LifecycleService`
re-derives state from the database on every press and never trusts what a button
still looks like.

## Storage

Migration `V3` adds:

- `request.interest_token TEXT NULL` — plaintext, like `ref_token`: random, no
  personal data. Every row born of one interest carries the same value; it is
  the sibling link. A sealed payload cannot be queried, so this has to be a
  column.
- `person_settings(user_ref TEXT PRIMARY KEY, payload BINARY, updated_at
  TIMESTAMP)` — sealed exactly like `chat_settings`, AAD is the `user_ref`. One
  field in the payload: `tolerancePct`. Keyed by `user_ref` so forgetting
  deletes it with the predicate the rest of the schema already uses.
- `name_give_up(ref_token TEXT, peer_ref_token TEXT, user_ref TEXT,
  consented_at TIMESTAMP)`, primary key on the token pair. One row per person
  per pairing; both rows present means both consented.
- The sealed `sent_message` payload gains the rendered message text, which it
  does not store today. Nullable, so rows written before V3 fall back to the
  current strip-only path.

No new request table. A no-names request is a `request` row with `chatId = 0` —
Telegram never issues 0 — and `Matcher` already filters on `chatId ==` and
`pair ==`, so no-names never mixes with a chat and mixed pairs inside it work
untouched. Showings are ordinary rows in their chats, indistinguishable from
typed requests except for `interest_token`. Short ids stay unique per
`chat_ref`, so each showing is labelled in its own chat and no-names gets its
own `a…z9` space.

`Housekeeping.refreshRates` enumerates pairs from `chat_settings` today. It must
also enumerate the pairs of requests resting on a no-names basis, or a pair no
chat uses never gets a reference rate and its notionals stay unknown.

## Privacy and forgetting

Before a name give-up, a counterparty learns side, stated amount, and currency —
no handle, no user id, no chat, no hint of which groups are shared.

Membership probing touches only chats already present in `chat_settings`, and
its result is used and discarded. No record of which groups a person belongs to
is written anywhere.

`/forget` in a private chat now erases the person's no-names requests, their
`person_settings` row, their `name_give_up` rows, and redacts the give-up
messages that named someone (ADR 0005). `/forget all` keeps its meaning and
additionally reaches showings in every chat, which `deleteFor(userId, chatId =
null)` already does. `/forget` typed in a group still means that group alone,
and may therefore delete one showing of an interest whose siblings live on —
forgetting removes a record, it does not withdraw an interest.

## Testing

Kotest, in the existing style, with no bot required:

- `Matcher`: per-side residual, including the asymmetric case where the larger
  side's tolerance excludes a pairing the smaller side accepts; the inclusive
  boundary at exactly the tolerance; canonical pair equality so
  `buy 10 RUB for EUR` meets `sell 10 EUR for RUB`; every existing case
  unchanged.
- Fan-out: chats filtered by pair, non-members dropped, a failed send rolling
  its showing back, the chat's own pair orientation applied to the showing's
  side.
- Sibling closes: done, cancel and expiry each closing every row on an
  `interest_token` in one transaction; reopen reviving them; the shortest time
  in force governing.
- Residual restate: the button offered only when the residual is positive,
  absent when currencies differ with no rate, and creating a request with a new
  short id rather than rewriting the original's stated amount.
- Name give-up: one-sided consent disclosing nothing; a forged callback payload
  claiming the peer consented disclosing nothing; both rows present disclosing
  both handles exactly once.
- `PersonSettingsRepository` round-trip and default; `SchemaDriftTest` extended
  to V3.
- `ForgetCommandTest` extended: personal settings, no-names requests and
  give-up rows all gone.

## Out of scope

Personal time in force — a showing uses its chat's, no-names uses the 7-day
default. A personal default pair — `for XXX` is stated every time. Caching of
membership probes — the probe runs per post, and a TTL cache waits for a
deployment that needs it. Bare-text commands with no leading slash. Partial
keyboards for surviving counterparties. Any search for combinations of
counterparties.
