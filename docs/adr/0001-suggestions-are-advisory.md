# Suggestions are advisory, and reserve nothing

When the bot names counterparties it reserves nothing: both requests keep
resting, the same person can be suggested to several others, and only an
explicit done closes anything. We chose this over locking two requests together
because this is a noticeboard for people who already know each other, not an
exchange — a lock would mean one unresponsive person silently blocks another's
discovery until a timeout we would then have to design.

## Consequences

There is no `MATCHED` state, so a request rests until someone says otherwise.
Nothing needs to be persisted about a suggestion, which is why matching is a
plain query run when someone asks rather than a background job writing to a
suggestions table. Do not add one: without reservation there is nothing to
remember, and re-listing the same counterparties is useful repetition at the
scale of one chat.

The accepted cost is double-booking. Two people may both be told about the same
counterparty, and they sort it out between themselves — which is what they were
going to do about the rate anyway.
