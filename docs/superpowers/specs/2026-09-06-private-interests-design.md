# Private Interests — Design

**Date:** 2026-09-06
**Status:** Approved design, grilled across six rounds. Not yet implemented.

Today a request exists only in the group chat it was typed in, and a private
chat gets one reply: "add me to a group". This design lets a person state an
interest to the bot privately, has the bot show that interest in every group
they share with it whose pair fits, and works the same interest on a no-names
basis across the whole bot for people who share no chat at all. It gives each
person their own size tolerance for that no-names working, and redefines size
tolerance so that a counterparty smaller than you is a counterparty rather than
a miss.

The vocabulary — Interest, Showing, No-names basis, Name give-up, Residual, and
the redefined Size tolerance — lives in `CONTEXT.md` and is not repeated here.

## Matching

### Per-side residual

Each side judges its own residual against its own tolerance:

| | Their size vs yours | Your residual | You accept when |
|---|---|---|---|
| Bigger counterparty | `T > Y` | 0 | always |
| Equal | `T = Y` | 0 | always |
| Smaller counterparty | `T < Y` | `Y − T` | `(Y − T) / Y ≤ your tolerance` |

Sizes are the comparable magnitudes `Matcher` already derives: notionals when a
reference rate exists, stated amounts when both requests are in the same
currency and no rate does.

| A | B | A's residual | B's residual | Outcome |
|---|---|---|---|---|
| sell 4 EUR @50% | buy 2 EUR @20% | 50% ≤ 50 ✅ | 0 ✅ | counterparties |
| sell 4 EUR @20% | buy 2 EUR @50% | 50% > 20 ❌ | 0 ✅ | not counterparties |
| sell 1000 EUR @20% | buy 999 EUR @20% | 0.1% ≤ 20 ✅ | 0 ✅ | counterparties |

Two people sharing a tolerance behave exactly as they do today: for equal
numbers, "each side's own residual" and the current `|a − b| / larger` are the
same test, so every case in `MatcherTest` stands. The comparison stays
inclusive — a residual exactly equal to the tolerance is accepted, matching the
existing `distance > limit` rejection.

Showings are matched at their chat's tolerance, which is what every request in
that chat already uses. No-names requests are matched at each person's own
tolerance, defaulting to 20% and bounded 1–100 like the chat command.
`AdminService.setTolerance`'s reply ("Amounts now match when they're within
$pct% of each other") no longer describes what the setting does and is reworded
around the residual.

### No combinations

Counterparties are judged strictly pairwise. The bot never searches for a set of
counterparties that together cover a size — that is subset-sum, and an NP-hard
search has no place in a name-passing broker. Two 2-EUR buyers against a 4-EUR
seller are two suggestions in a list; combining them is the person's decision.

### The two surfaces never meet

A request typed in a chat meets only that chat's showings. A no-names request
meets only other no-names requests. Someone who has never spoken to the bot
privately is therefore invisible on the no-names side, which is the point:
you are worked bot-wide because you asked to be.

### Suppressed pairings

A no-names pairing is suppressed while a live showing already pairs those two
people in some chat — they can already see each other by name there, so the
anonymous route adds nothing. The check is evaluated fresh whenever
counterparties are computed, so the pairing reappears the moment no live showing
pairs them. Nothing is stored.

A pairing one side has declined (below) is also suppressed, for both of them,
for as long as the two requests rest.

### Residuals after a done

`/done` closes both requests, and the bot offers the larger side a one-tap
button to state the residual as a fresh interest. This happens wherever a done
leaves a residual, including for a request typed in a group: the residual is a
consequence of the matching rule, not of where the interest was stated.

Pressing it creates a new interest, which fans out like any other and pays the
same rate limit — joining the current batch rather than being refused. The
stated amount of the original is never rewritten: ADR 0003 holds, and the
residual is a new thing the person stated.

The residual is recomputed at press time from the two closed rows, in the
presser's own stated currency, using the reference rate when the stated
currencies differ. No button is offered when the residual is zero, or when the
currencies differ and no rate is available.

## Stating an interest privately

`inGroupOrExplain` stops being a blanket refusal. Each command gains a private
meaning or keeps the hint:

    /sell 10 EUR for RUB     state an interest
    /buy 10 RUB for EUR      same, other way round
    /tolerance 5             your own size tolerance, 1-100
    /status                  your interests and where each still rests
    /cancel a1               withdraw the interest, every showing with it
    /done a1 @anna           you swapped, after a name give-up
    /settings                your tolerance
    /pair, /tif              still the group-admin hint

`/tolerance` in a group remains the admin command it is today, exactly as
`/forget` already means different things in the two places. `/sell` without
`for XXX` is rejected with the example.

`/done` privately takes the group's shape, and the Done button on the give-up
message is always the reliable path: a peer with no `@username` cannot be
addressed by the typed form at all, and no second identifier scheme is invented
to make them typeable.

Private `/status` lists each interest once, with where it currently rests.
Chats whose showing has lapsed are simply absent — the question it answers is
where someone can still find you. A chat's own `/status` is unchanged and does
not mark showings: provenance explains a message nobody typed, not a standing
list.

### Posting an interest

1. Parse verb, amount, and both currencies. The no-names request uses a
   canonical pair orientation (the codes sorted, so always `EUR/RUB`), because
   `Matcher` compares pairs by equality and `buy 10 RUB for EUR` must meet
   `sell 10 EUR for RUB`. The side follows from the canonical pair through the
   existing `sideFor`.
2. Check the pair can be priced: the rate cache first, and only for a pair no
   chat already uses, one live `RateClient.fetch`. Refuse when the feed answers
   without the pair; accept when the feed could not be reached, since
   `RateClient` returns `null` for both and refusing a legitimate pair during an
   outage is the harder failure to explain.
3. Create the no-names request — sentinel `chatId = 0`, canonical pair, a fresh
   `interest_token`, the 7-day default time in force — and every showing, in the
   chats found by step 4. All of them rest immediately; only the announcement
   waits for the batch.
4. Enumerate chats from `chat_settings` (the sealed payload carries `chatId`),
   keep those whose pair is the same two currencies in either orientation and
   whose fan-out is on, then probe `getChatMember` and keep the chats where the
   person is a member.
5. Reply privately and at once: the counterparties found for this interest, and
   where it is about to be shown. The reply never waits for the batch.

A `/sell` typed in a group is unchanged: one showing in that chat, no fan-out,
no rate limit.

### Batching and limits

One fan-out per minute per person, and at most five privately stated interests
resting at once; the sixth is refused, naming the cap and suggesting `/cancel`.
Silently cancelling something a person deliberately stated is the one outcome
they cannot undo by knowing the rule. Requests typed in a chat are uncapped —
the cap exists to bound fan-out.

The first private statement starts a 60-second window. Everything stated before
it fires is announced as one message per chat, then the window resets, so a
showing is never more than a minute behind the person who stated it. A sliding
window would starve a busy person indefinitely.

Each batched message is composed at flush time from live state, carries every
interest it covers, and is recorded in the message log against every ref token
it names — which `MessageLogRepository.record` already supports. Its text marks
that the bot is showing these on someone's behalf, so a message nobody typed
explains itself.

"A counterparty appeared" pings on the no-names side batch on the same window,
one message per recipient: somebody else's typing speed must not decide how many
messages you get.

Pending announcements are persisted, so a restart inside the window does not
leave showings resting in chats that were never told. At startup, and at each
flush, an announcement is re-rendered from live state — closed requests dropped,
counterparties recomputed — never replayed as composed text. An announcement
whose showings have all closed is dropped, and so is one older than an hour: an
hour-late "someone just stated this" is noise, and the showing keeps working
silently regardless.

### Fan-out, per chat

`/fanout on|off` is an admin command, stored in the chat's settings, default on,
and shown by `/settings`. Off means no showing is created in that chat at all —
not merely a suppressed announcement, since a silent-but-matchable showing is
exactly what an admin turning it off would object to. Showings created while it
was on live out their time in force.

## Name give-up

Anyone resting on a no-names basis is told when a new counterparty appears —
side and stated amount, no identity — with a button offering to pass their name.

Before either side is offered anything, at least one of the two must have an
`@username`. A display name plus a `tg://user?id=` link is reliably actionable
only between people who share a chat, which these two by definition do not, so
without a handle a give-up would hand over two names neither person can act on.
Checking it first fails before anyone consents rather than after both did.

Names are never stored for this. At give-up time the bot looks each person up
live, so what it passes is current, and forgetting has nothing extra to erase.

Consent is persisted, never carried in the button. Pressing the button writes
the presser's row; the bot then asks the other side. When both rows exist, the
bot sends each person the other's handle or name with Done buttons, and records
both messages in the message log so forgetting can redact them (ADR 0005).

Declining is recorded too. A decline covers that pairing of requests,
symmetrically — neither side is offered the other again — and dies with either
request. It is deliberately not a durable record of two people who don't want
each other: someone who declines the same person in a later interest will not be
surprised to be offered them again.

An offer whose peer request closes before it is answered dies with it, and the
person waiting is told, naming nobody.

Callback data stays advisory, as `Callbacks.kt` already insists: the presser is
re-derived from `callback_query.from.id`, and consent is read from the table, so
a hand-crafted payload claiming the other side agreed achieves nothing. Ref
tokens in a button are random and already treated as non-authorizing.

## Closing

Done and cancel on any row close every OPEN row sharing its `interest_token`, in
one transaction, with the same terminal state — they are decisions about the
whole interest.

Expiry is not. A time in force running out is one chat's housekeeping policy, so
it closes only the showing that lapsed; the rest of the interest lives on. A
chat with `/tif 1` therefore drops out on day one without shortening anything
else. `/reopen` revives the siblings it closed, with fresh expiries.

Every message that carried a closed token is edited, not merely stripped: the
bot rewrites it to its stored text plus a status line per closed token — done,
withdrawn, or lapsed — and rebuilds the keyboard from live state in the same
pass, keeping the rows whose requests are still open. All-or-nothing stripping
was tolerable when a message carried one request; a batched message carries
several, and closing one interest must not kill the buttons of the others.

`ButtonService`'s `FAN_OUT = 10` cap and its reasoning carry over: an edit that
does not land leaves a message stale but harmless, because `LifecycleService`
re-derives state from the database on every press and never trusts what a button
looks like.

## Storage

Migration `V3` adds:

- `request.interest_token TEXT NULL` — plaintext, like `ref_token`: random, no
  personal data. Every row born of one interest carries the same value; it is
  the sibling link. A sealed payload cannot be queried, so this must be a
  column. Rows predating V3 keep `NULL` and are interests of one showing.
- `person_settings(user_ref TEXT PRIMARY KEY, payload BINARY, updated_at
  TIMESTAMP)` — sealed like `chat_settings`, AAD is the `user_ref`, one field in
  the payload: `tolerancePct`. Keyed by `user_ref` so forgetting deletes it with
  the predicate the schema already uses.
- `name_give_up(ref_token TEXT, peer_ref_token TEXT, user_ref TEXT, stance TEXT,
  decided_at TIMESTAMP)`, primary key on the token pair. `stance` is offered or
  declined. Two offered rows mean both consented; one declined row suppresses
  the pairing for both. Rows die with their requests.
- `pending_announcement(chat_ref TEXT, interest_token TEXT, user_ref TEXT,
  created_at TIMESTAMP)` — what to announce, never how. Re-rendered at flush.
- The sealed `sent_message` payload gains the rendered message text, which it
  does not store today. Nullable, so rows written before V3 fall back to the
  current strip-only path.
- The sealed `chat_settings` payload gains the fan-out flag, defaulting to on
  when absent, so existing chats need no backfill.

No new request table. A no-names request is a `request` row with `chatId = 0` —
Telegram never issues 0 — and `Matcher` already filters on `chatId ==` and
`pair ==`, so no-names never mixes with a chat and mixed pairs inside it work
untouched. Showings are ordinary rows in their chats, distinguished only by
`interest_token`. Short ids stay unique per `chat_ref`, so each showing is
labelled in its own chat and no-names gets its own `a…z9` space.

`Housekeeping.refreshRates` enumerates pairs from `chat_settings` today. It must
also enumerate the pairs resting on a no-names basis, or a pair no chat uses
never gets a reference rate. The feed is per base currency
(`open.er-api.com/v6/latest/{base}` returns every quote for it), so the cost is
one GET per distinct base per day, not per pair.

Housekeeping also drops `name_give_up` and `pending_announcement` rows whose
requests have closed, and pending announcements older than an hour.

## Privacy and forgetting

Before a name give-up, a counterparty learns side, stated amount, and currency —
no handle, no name, no user id, no chat, no hint of which chats are shared.

Membership probing touches only chats already present in `chat_settings`, and
its result is used and discarded. No record of which chats a person belongs to
is written anywhere. No name is stored for the give-up path.

`/forget` privately erases the person's no-names requests, their
`person_settings` row, their `name_give_up` rows, their pending announcements,
and redacts give-up messages that named someone (ADR 0005). `/forget all` keeps
its meaning and additionally reaches showings in every chat, which
`deleteFor(userId, chatId = null)` already does. `/forget` typed in a group
still means that group alone, and may delete one showing of an interest whose
siblings live on: forgetting removes a record, it does not withdraw an interest.

## Testing

Kotest, in the existing style, no bot required:

- `Matcher`: per-side residual, including the case where the larger side's
  tolerance excludes a pairing the smaller side accepts; the inclusive boundary
  at exactly the tolerance; canonical pair equality so `buy 10 RUB for EUR`
  meets `sell 10 EUR for RUB`; suppression while a live showing pairs the two,
  and its reappearance when that showing closes; every existing case unchanged.
- Fan-out: chats filtered by pair and by the fan-out flag, non-members dropped,
  the chat's own pair orientation applied to the showing's side, showings
  resting before their announcement.
- Batching: statements inside one window producing one announcement per chat;
  the window resetting rather than sliding; the private reply never waiting;
  pings collapsed per recipient; the sixth interest refused; a restate joining
  the current batch.
- Pending announcements: re-rendered from live state, dropped when all showings
  closed, dropped when older than an hour.
- Lifecycle: done and cancel closing every row on an `interest_token` in one
  transaction; expiry closing only the lapsed showing; reopen reviving what it
  closed; a batched message's keyboard rebuilt to keep still-open rows.
- Residual restate: offered only when the residual is positive, absent when
  currencies differ with no rate, creating a request with a new short id rather
  than rewriting the original's stated amount, and offered for group-typed
  requests too.
- Name give-up: refused when neither party has a handle; one-sided consent
  disclosing nothing; a forged callback claiming the peer consented disclosing
  nothing; both rows disclosing once; a decline suppressing the pairing for both
  and dying with the requests; a pending offer whose peer request closes telling
  the waiting person and naming nobody.
- Pair pricing: refused when the feed answers without the pair, accepted when
  the feed is unreachable, no call at all for a pair a chat already uses.
- `PersonSettingsRepository` round-trip, default and 1–100 bounds;
  `SchemaDriftTest` extended to V3.
- `ForgetCommandTest` extended: personal settings, no-names requests, give-up
  rows and pending announcements all gone.

## Out of scope

Personal time in force — a showing uses its chat's, no-names uses the 7-day
default. A personal default pair: `for XXX` is stated every time. Caching of
membership probes; the probe runs per fan-out. Bare-text commands with no
leading slash. Any search for combinations of counterparties. A durable record
of who declined whom. Relaying messages between two handle-less people.
