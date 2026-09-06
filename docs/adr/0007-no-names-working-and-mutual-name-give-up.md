# Interests stated privately are worked on a no-names basis, and identities pass only by mutual give-up

An interest stated to the bot privately is shown in the chats the person shares
with it, and also worked bot-wide against people who share no chat with them at
all. That second surface introduces strangers, which the chat surface never
does: everyone in a group can already read each other's names.

So nothing on it names anyone. A counterparty is described by side and stated
amount only. Either person may offer to pass their name; the other is asked; the
handles pass only once both have. Either may decline, which suppresses that
pairing for both of them until the requests close.

The obvious alternative — naming counterparties immediately, as the chat surface
does — would mean that merely resting an interest discloses your handle to any
stranger who states the opposite side. The other alternative, naming whoever
posted first to whoever posted second, discloses one person without ever asking
them.

## Consequences

The two surfaces do not meet. A request typed in a chat is never worked
bot-wide, because its author never asked to be: consent is what puts someone on
the no-names side, and it is given by stating the interest privately.

Names are looked up live when a give-up completes, never stored for it. A
give-up happens days after the interest was stated, so storing a display name
would mean holding personal data at rest for the sole purpose of a disclosure
that may never happen.

A give-up needs a route, not just a name. A `tg://user?id=` link is reliably
actionable only between people who share a chat, which these two by definition
do not, so at least one of them must have an `@username` before either is asked
to consent — checked before anyone agrees to anything, rather than after both
did.

A decline is recorded against the pairing of requests and dies with them. A
durable record of two people who declined each other would be exactly the kind
of relationship data this bot otherwise refuses to keep.
