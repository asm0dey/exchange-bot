# A done is a question to the counterparty, not a closure

Declaring a done closes nothing. `/done a1` names the counterparty back to the
declarer, tells them nothing has closed, and sends that counterparty a question
that names the declarer and the amount: did you two swap? Both requests keep
resting until the answer arrives. Before this, one person could close another
person's request by typing a short id — a stranger's request, on the strength of
nothing, with the only remedy a Reopen they had to notice and press.

Both requests keep resting while the question is outstanding, rather than being
frozen until it is answered. A suggestion reserves nothing (ADR 0001), and an
unanswered question is a weaker thing than a suggestion: if it froze anything,
anyone could freeze anyone by asking, and the person asked would be punished for
being slow to read their phone. So the counterparty stays discoverable, and if
somebody else does the swap first the ordinary "isn't waiting anymore" answer
covers it.

A No closes nothing and takes nothing back. It is not a verdict on the two
people and it is not reported as one: the declarer is told the person did not
confirm, both requests carry on, and the pairing is available to be suggested
again tomorrow. Most first refusals are an honest mix-up — the wrong short id,
the wrong person, a misremembered week.

## Consequences

No *dedicated* record says a question is outstanding: no table of its own, no
state on the request, no timer to expire it. That is what the design spec
forbids, and it is all it forbids. The ask itself is recorded — in the message
log the bot already keeps for every message that names somebody (ADR 0005).
`sendAsk` writes the question there against both ref tokens with its exact
button list, and a press consults it: `confirm` and `refuse` both ask
`AskLookup.wasAsked` before they look at the pairing at all. What sweeps it is
the sweep that log already had, the 90-day `prune`; nothing new runs on a timer.

The two buttons carry the declarer's ref token and the counterparty's, and a
press re-derives the rest from those two tokens plus the live rows — who the
presser is, whether both requests are still open, whether they are still a
pairing at all. An unanswered question therefore dies quietly when either
request lapses or is cancelled, because the press that would have used it finds
nothing to act on.

A second No blocks a third ask, and nothing more. `DoneRefusalRepository`
counts refusals keyed by (declarer's ref token, refuser's ref token), one
direction only, so the block lands on whoever kept asking and never on the
person being pestered; the rows die with either request and with `/forget`, so
no durable record of who refused whom survives. But be precise about what that
counter is worth. It gates asks. It does not gate a Yes (see below). Being
keyed on a pair of ref tokens, it says nothing about a declarer working through
many different people, and it resets the moment they cancel and restate — which
costs them a fresh request and is otherwise free, through the ordinary
interface. The counter is a courtesy brake on one pairing, not a bound on
enumeration. What actually deters probing is that every ask is **loud**: it
delivers, to the named person, a question naming the asker and the amount. There
is no quiet way to try this.

The force-close residual is closed. The reasoning that introduced this change
claimed asking the counterparty removed the old hazard "because its victim is
the one being asked", which was true of `done` and false of `confirm`: a Yes
does the closing, and `confirm` could not tell a genuine answer from a
volunteered one. Somebody who owned an opposite-side request in the same chat
could pair it with a harvested ref token and close that request without its
owner having declared anything.

Two things shut that route. A press is now honoured only when the bot really put
that exact button in front of the presser: `confirm` and `refuse` both ask
`AskLookup.wasAsked`, which finds the pressed callback data in the buttons of a
message logged against the presser's own request, and `sendAsk` is the only
thing that ever logs one. And the Done button no longer hands the reader the
other person's ref token at all — `Cb.done` names the counterparty's row by its
short id, which every status line already prints beside their name, so there is
nothing to harvest from the message a stranger can read. The old
mitigations still stand behind both: the outcome names both people, and the
person closed out gets a notice carrying their own Reopen.

Read "nothing to harvest" narrowly: it is true of the Done button, not of every
button on this branch. `Cb.restate` still carries the other person's ref token
in `b` — `decisionButtons` and `noticeButtons` both build one — so a reader of
those messages does hold a token that is not theirs. It is not a way back in:
that token names a request the Yes has already marked DONE, and `wasAsked`
blocks the one route that could force a close with it. But nobody should lean
on the stronger reading.

It is bought with a real cost, and the cost is deliberate. The ask lives in the
message log and nowhere else, so what deletes from that log can invalidate an
ask nobody has answered yet. Only the 90-day prune deletes a logged message.
`/forget` does not: `MessageLogRepository.forget` drops the `sent_message_ref`
rows carrying that person's own user ref, and the `sent_message` row survives
with its text and its stored buttons intact.

So `/forget` invalidates the ask from one side only. `wasAsked` asks whether the
question was offered against the ref token of the person ASKED, so when THEY
forget, the ref row it reads through goes with them and their Yes is refused as
one nobody asked them for. When the DECLARER forgets, only the declarer's ref
row goes; the row keyed on the counterparty's token stays, and the ask record
still stands. The Yes is still refused, but earlier and for another reason: the
declarer's requests were erased with them, `byRefToken` finds nothing, and the
answer is "That request is gone."

Either way nothing closes, and either way it is a real route by which an honest
counterparty's Yes stops working. A forgotten question must not stay
actionable — that is the behaviour we want — and the declarer can simply ask
again.
