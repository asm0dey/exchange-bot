# Size tolerance is each side's own residual, and counterparties are only ever pairwise

Size tolerance was how far apart two notionals could be as a percentage of the
larger, applied once to a pairing. It is now how large a residual each person
accepts relative to their own size, judged separately for each of them. Someone
smaller than you leaves you a residual of the difference and takes none of their
own, so they accept always and you accept when the leftover is within your
number. Two people sharing a tolerance are unaffected: for equal numbers the old
and new tests are the same arithmetic.

The old rule discarded a genuine counterparty. Someone selling 4 with a
tolerance of 50 was refused a buyer of 2, whose 2 they could have done in full,
because the pairing was judged by a single number that the smaller side had no
stake in.

## Consequences

Tolerance now means something one person owns rather than a property of a
pairing, which is what makes a person-level tolerance coherent alongside a
chat-level one.

The bot deliberately does not look for sets of counterparties that together
cover a size. Doing so is subset-sum, and a name-passing broker has no business
running an NP-hard search to decide whom to introduce. Two buyers of 2 against a
seller of 4 are two suggestions in a list; whether to take both is the person's
judgement, and taking one leaves a residual they can restate in a single press.

A done therefore closes both requests even when one side has size left over,
because the alternative — the bot reducing a stated amount and leaving the
request resting — would have it publishing a number nobody typed, against
ADR 0003. The restated residual is a new request the person stated.
