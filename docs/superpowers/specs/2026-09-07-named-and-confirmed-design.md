# Counterparties are named, and a done is confirmed by the person it names

**Date:** 2026-09-07
**Status:** Approved design. Not yet implemented.

This overrides two things, neither of which has shipped: the name give-up
described in `2026-09-06-private-interests-design.md`, and ADR 0007 which argues
for it. Both exist only on the `private-interests` branch, so this changes
unreleased work rather than migrating live behaviour — which is why it can edit
that branch's own migration in place instead of superseding it.

Two decisions, independent of each other:

1. A counterparty is named the moment they are suggested, to strangers as
   readily as to groupmates. Anonymity, and the mutual consent that lifted it,
   are gone.
2. Nothing closes on one person's word. Every done asks the counterparty, and
   rests until they agree.

The vocabulary is `CONTEXT.md`'s, with the changes named under *Vocabulary*
below.

## Everyone is named

A counterparty is named in the message that suggests them — in a chat, in a
private reply, in `/status`. There is no consent step, no offer to pass a name,
no state in which one person has agreed and the other has not answered.

`GiveUpService`, `NameGiveUpRepository`, `Stance`, the `name_give_up` table, the
`giveup` and `decline` callbacks, `Cb.giveUp`/`Cb.decline`, `giveUpButtons`, the
`copy(username = null)` stripping on unconsented renders, and both of their test
suites go. `LifecycleService.done` loses its third guard — the
`bothOffered` check that stood in for authorization on the sentinel-scoped side —
because the confirmation below replaces it with something stronger.

### Nothing new is stored to do it

`main` already seals a `username` into the request payload and renders from
there. That stays the source. `Render.mention` is unchanged: `@handle` when
there is one, otherwise the display name inside a `tg://user?id=` link.

The live lookup the give-up path introduced survives only as a fallback, for
the one thing the stored row cannot supply — a display name for someone with no
handle. `NameLookup` and `Handle` are declared in `GiveUpService.kt` and outlive
it: they move to `Render.kt`, beside the `mention` they exist to feed. `Party`
and `GiveUpResult` go with the service.

### The handle requirement is dropped entirely

ADR 0007 required at least one `@username` between two people who share no chat,
on the grounds that a `tg://user?id=` link is reliably actionable only between
people who share one. That is not true of this population. The Bot API states:

> Mentions are guaranteed to work if the user has previously contacted the bot
> or sent a callback query, provided they do not have Forwarded Messages privacy
> enabled.

Everyone the bot ever names has done one of those two things — stated an
interest privately, typed in a chat, or pressed one of its buttons. So
`introducible()`, the `NO_ROUTE` refusal, and every branch that turns on whether
somebody has a handle are deleted rather than relocated.

The residual is the Forwarded Messages exception. The bot cannot query that
setting, so it cannot warn anyone: such a person's name renders as plain text
and the reader cannot tap through. Accepted, undetected, and not worked around —
the alternative was refusing to work a legitimate interest on a guess.

### The two surfaces still do not meet

Unchanged from the branch. A request typed in a chat meets that chat's requests
only; a privately stated interest is worked against other privately stated
interests and shown in each shared chat whose pair fits and whose fan-out is on.

The rule survives; its justification is replaced. It used to rest on consent —
you are worked among strangers because you asked to be. It now rests on
visibility: a chat's offers belong to that chat, and a request typed in one was
never offered to anybody outside it. The consent reading is not merely retired,
it is strengthened — being worked among strangers now hands them your name, so
the act of stating it privately carries more weight, not less.

## The bot answers where you spoke to it

The branch suppressed a bot-wide pairing while a live showing already paired the
same two people, on the grounds that the anonymous route added nothing once they
could see each other by name in a chat. With names everywhere that argument is
gone, and what remained of it — do not introduce the same two people twice — is
not worth what it costs: a chat can be muted, the bot cannot see that it has
been, and trading a delivered private message for a chat message that may never
be read loses swaps silently.

So the suppression rule is deleted and replaced by a delivery rule:

**Each person is told in the place they spoke to the bot.** A request typed in a
chat is answered in that chat. An interest stated privately is answered
privately, wherever the counterparty was found. Two people who came in by
different routes each get one message, in their own place.

This subsumes the case it replaces. Two people who both stated privately and
share a chat are each told once, privately — not twice, and not in a chat either
of them may have muted. A chat still carries the message for whoever typed
there, and that message necessarily names their counterparty, which is what
Part one already permits.

Nothing is stored for this: the row's `chatId` already says where its author
spoke, and the sentinel says they spoke privately.

## Closing

### Every done asks

`LifecycleService.done` stops closing anything on its own. It resolves the
pairing, applies the guards it already applies, and then asks the counterparty:

> @anna says you two swapped 100 EUR. Did you?  [✅ Yes, we did] [✖️ No, we didn't]

Nothing closes until Yes. Both requests keep resting and keep being suggested to
other people while the ask is outstanding — a suggestion reserves nothing (ADR
0001), and freezing a request on an unanswered question would let anyone freeze
anyone.

The ask goes wherever the counterparty spoke, by the rule above.

On Yes, the existing path runs unchanged: `closeBothWhole` closes every OPEN row
on both interests in one transaction, the message that carried them is edited
with its status line, and the larger side is offered its residual to restate.

There is no timer, and an unanswered ask needs no sweeping. The person who
declared it keeps the escape they always had — `/cancel` closes their own
interest, which is theirs to do unconditionally. If the counterparty's request
closes or lapses first, the ask simply stops working, and the declarer learns it
from the status line the branch already writes onto every message that carried a
closed token. No proactive notice, and therefore no record of an outstanding ask.

### The handle becomes optional

`/done a1` is enough:

- **one counterparty** — unambiguous, so it is asked;
- **several** — the bot replies with the list and a button each, and asks which.
  Nothing is asked of anybody and nothing closes;
- **none** — nothing to confirm. The bot says so and points at `/cancel` for
  someone who only wants the request gone.

`/done a1 @boris` still works, for when the message carrying the buttons has
scrolled away. `✅ Done with @boris` is unchanged and remains the easy path.

This removes the last route that could close a request without asking anyone.
On `main` a done naming nobody closes only the caller's own row; under this it
does not exist, because a done means a swap and a swap has a counterparty.
`ActionResult.Ok`'s "Marked done." branch, and the `theirs == null` arm of
`done`, go with it.

### What this fixes, beyond the obvious

`LifecycleService.done` records a known-accepted residual in its own comment: a
chat member can pair their own token with an uninvolved same-chat opposite-side
request and close it out from under its owner, mitigated only by the closure
naming both people publicly and the wronged party being able to `/reopen`. The
confirmation removes the residual rather than mitigating it — the close never
happens, because its victim is the one being asked. The publicity requirement on
the done text is kept anyway; it costs nothing and the text should name both
people regardless.

### Two refusals end it

A No closes nothing and is not a statement about the two people — someone
denying a done they did not make must not lose a real counterparty for it. So a
No leaves the pairing intact and both requests resting.

It is counted, though. The bot records a refusal against the pairing, one
direction only: the declarer's token against the refuser's. On the second
refusal, further asks from that declarer about that pairing are refused before
the counterparty is sent anything, and the declarer is told why.

- **Two, not one.** A first No is usually an honest mix-up — the wrong short id,
  the wrong person, a misremembered swap. A second is a pattern.
- **One direction only.** The block sits on whoever kept asking. If the two
  genuinely swap later, the refuser declaring it themselves still works and the
  other side is asked as normal. A symmetric block would punish the person being
  pestered.
- **Scoped to the pairing, and it dies with either request.** A durable record of
  who refused whom is exactly the relationship data this bot otherwise refuses to
  keep. The cost is that a determined asker can reset the count by cancelling and
  restating — which costs them a fresh request, and they are already bounded by
  the five-interest cap and one fan-out a minute. Accepted.

## Storage

`V3__private_interests.sql` is edited in place. It has never run outside a
development machine, so adding a `V4` whose only work is dropping what `V3` just
created would leave a permanent record of a decision no deployment ever saw.

- `name_give_up` is replaced, in the same shape, by
  `done_refusal(ref_token TEXT, peer_ref_token TEXT, refusals INT, refused_at
  TIMESTAMP)`, primary key on the token pair. `ref_token` is the declarer's,
  `peer_ref_token` the refuser's — the asymmetry of the key is what makes the
  block one-directional, with no extra column to express it. Both are random and
  carry no personal data. Housekeeping drops rows whose requests have closed,
  exactly as it dropped give-ups.
- `interest_token`, `person_settings` and `pending_announcement` are unchanged.
- **No table for an outstanding ask.** The Yes/No buttons carry the two ref
  tokens and nothing else. Everything a press needs is re-derived from live
  state: the presser from `callback_query.from.id`, ownership and openness from
  the rows, the pairing structurally. A press is honoured only when the presser
  owns the counterparty's token, and the declarer whose refusal count is written
  is the owner of the other one — read from the row, never from the payload.
  Callback data stays advisory, as `Callbacks.kt` already insists: a hand-crafted
  payload claiming a Yes from somebody else achieves nothing, and one naming
  somebody else's request as the presser's own writes nothing at all.

## Privacy and forgetting

Before this change a counterparty learned side, stated amount and currency. They
now also learn who. That is the whole of the privacy delta, and it is the point.

`/forget` privately erases the person's privately stated interests, their
`person_settings` row, their `done_refusal` rows, their pending announcements,
and redacts messages that named somebody (ADR 0005). `/forget all` additionally
reaches showings in every chat. `/forget` typed in a chat still means that chat
alone. Unchanged, except that `name_give_up` becomes `done_refusal` in the list.

Membership probing is unchanged: it touches only chats already in
`chat_settings`, and its result is used and discarded.

## Vocabulary

`CONTEXT.md` loses **No-names basis** and **Name give-up**. Nothing replaces
them — with every counterparty named there is no second basis to distinguish,
and no consent to describe.

**Done** gains its confirmation: *Either of them may declare it; it takes effect
only when the other confirms, and it closes both requests.*

**Confirmation** is added: *The counterparty's answer to a declared done. Until
it arrives nothing closes and both requests keep resting. Avoid: approval,
acceptance, acknowledgement, verification.*

ADR 0007 is superseded by two:

- **ADR 0008 — Counterparties are named on sight.** Why anonymity was dropped,
  what the Bot API guarantees about mentions, and what the Forwarded Messages
  exception costs.
- **ADR 0009 — A done takes effect only when the counterparty confirms it.** Why
  the declaration is not enough, why nothing is frozen while an ask is
  outstanding, and why a refusal is counted but not remembered.

## Testing

Kotest, in the existing style, no bot required.

- **Naming:** a counterparty named in a chat suggestion, in a private reply and
  in `/status`; a person with no `@username` rendered as a `tg://user?id=` link
  over their display name; the stored username preferred over a live lookup, and
  the live lookup used only when there is none.
- **Done, happy path:** an ask sent to the counterparty and nothing closed; Yes
  closing every row on both `interest_token`s in one transaction; the residual
  offered to the larger side afterwards, exactly as today.
- **Done, refused:** No closing nothing and leaving both resting; the pairing
  still suggested afterwards; a second No blocking a third ask; the third ask
  refused before the counterparty is sent anything; the block not applying in the
  other direction; the rows gone once either request closes.
- **Done, unanswered:** both requests still OPEN and still matchable; the
  declarer's own `/cancel` closing their interest and leaving the counterparty's
  resting; a Yes pressed after the counterparty's request closed refused, naming
  the closed side.
- **Done, no handle typed:** `/done a1` asking the sole counterparty; `/done a1`
  with several replying with the list and asking nothing of anybody; `/done a1`
  with none refusing and naming `/cancel`; `/done a1 @boris` still resolving.
- **Authorization:** a forged Yes from someone who is not the counterparty
  closing nothing; a harvested pair of tokens producing no ask; the same-chat
  force-close that `LifecycleService.done` records as an accepted residual now
  closing nothing.
- **Delivery:** a chat-typed request answered in its chat; a privately stated one
  answered privately; a cross-route pairing producing one message each in the
  right place; two privately stated interests sharing a chat producing one
  private message each and no chat message for the pairing.
- **Migration:** `SchemaDriftTest` against the edited `V3`; `done_refusal`
  round-trip and its one-directional key.
- **Forgetting:** `ForgetCommandTest` extended to `done_refusal`.
- Every existing `MatcherTest`, `LifecycleServiceTest` and `PrivateCommandTest`
  case that does not turn on give-up stands unchanged.

## Out of scope

Proactive notice when an unanswered ask's counterparty closes their request —
the status line on the edited message already carries it. Any durable record of
who refused whom. Any rate limit on asks beyond the two-refusal rule. Relaying
messages between two people who cannot mention each other; the Forwarded
Messages case is accepted, not solved. Personal time in force, a personal default
pair, caching of membership probes, bare-text commands, and any search for
combinations of counterparties — all still out, as on the branch.
