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

Nothing records that a question is outstanding: no table, no state on the
request, nothing to sweep and no timer to expire. The two buttons carry the
declarer's ref token and the counterparty's, and a press re-derives everything
from those two tokens plus the live rows — who the presser is, whether both
requests are still open, whether they are still a pairing at all. An
unanswered question therefore dies quietly when either request lapses or is
cancelled, because the press that would have used it finds nothing to act on.

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
other person's ref token at all — `Cb.done` names the counterparty by user id,
so there is nothing to harvest from the message a stranger can read. The old
mitigations still stand behind both: the outcome names both people, and the
person closed out gets a notice carrying their own Reopen.

It is bought with a real cost, and the cost is deliberate. The ask lives in the
message log and nowhere else, so anything that deletes logged messages
invalidates an ask nobody has answered yet: a `/forget` from either side, and
the 90-day prune. The Yes or No that follows is refused as one nobody was asked
for. A forgotten question must not stay actionable — that is the behaviour we
want — but it is also a way for an honest counterparty's Yes to stop working,
and the declarer can simply ask again.
