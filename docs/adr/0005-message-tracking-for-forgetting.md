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
messages within 48 hours, but editing has no such limit, so redacting one
person's line from a message that names several reaches far further back than
deleting it ever could.

The same record is what lets stale buttons be stripped when a request closes,
rather than waiting for someone to press one and be told no.

Forgetting remains partial by nature, and the spec says so plainly: it removes
what the bot stores and cleans up what the bot can still reach. It cannot
un-say what was said, and it cannot touch anyone else's messages.
