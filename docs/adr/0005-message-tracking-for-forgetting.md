# The bot records which of its own messages named whom

Forgetting has to reach the bot's own chat messages, not just its rows — a
deleted database row is little comfort when the bot's reply still names someone
next to what they wanted to trade. So the bot keeps a small record of which
messages it sent, which requests they carry buttons for, and which people they
name.

## Consequences

That record is itself a list of who was active where, so it is written with the
same keyed identifiers as everything else and pruned after 90 days. The horizon
is set by editing, not deletion: Telegram only lets a bot delete its own
messages within 48 hours, but editing has no such limit, so a message far older
than that can still have the person's name taken out of it.

What redaction actually does is replace the message body with a neutral
placeholder, keeping the message and its buttons. It does **not** preserve the
other names the message mentioned — the bot never stored the rendered text, only
which requests and people each message named, so there is nothing to reconstruct
a partial message from. An earlier draft of this decision claimed otherwise; that
was wrong, and the claim mattered, because "everyone else's names survive" is not
a reason this design can offer. The reason to edit rather than delete is the
48-hour window, and that reason stands on its own.

The same record is what lets stale buttons be stripped when a request closes —
on both the command and the button path — rather than waiting for someone to
press one and be told no. A press on a button the sweep missed is still refused
safely; it just does not redraw the message it was attached to.

Forgetting remains partial by nature, and the spec says so plainly: it removes
what the bot stores and cleans up what the bot can still reach. It cannot
un-say what was said, and it cannot touch anyone else's messages.
