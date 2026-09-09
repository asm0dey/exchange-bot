# A counterparty is named in the message that suggests them

Whoever the bot suggests is named there and then, in the same sentence that
describes their side and their amount, and it makes no difference whether the
two people share a chat. ADR 0007 held that the surface with no chat behind it
had to be worked without names, because it introduces strangers, and that
identities should pass only once both sides had agreed to give them up. That was
a mistake about what the bot is. A suggestion nobody can act on is not a
suggestion: it tells you somebody exists, then makes you negotiate with a broker
for permission to say hello. The whole product is passing a name.

Stating an interest privately is already the act of asking the bot to work it
against people you do not know. Nobody types `/sell 10 EUR for RUB` into a
private chat with a broker in the hope of remaining anonymous to the person who
answers; they type it to be introduced. So the consent ADR 0007 tried to collect
twice, with a round trip in the middle, is the consent already given by stating
the interest at all.

Nothing new is stored to do this. A request payload already seals the author's
`username` when they have one, and `Render.mentionOf` reads it back; the one
thing the payload cannot hold — a display name, which changes — is looked up
live at render time and dropped. Naming counterparties therefore adds no
personal data at rest, which is the concern ADR 0007 was right about and solved
by holding nothing.

## Consequences

The handle requirement is gone. ADR 0007 demanded that at least one of two
strangers carry an `@username` before either could be asked, on the belief that
a `tg://user?id=` link is only reliably actionable between people who share a
chat. That belief was wrong: the Bot API guarantees such a mention resolves for
anyone who has contacted the bot or pressed one of its buttons, and that is
every person the bot ever names — a counterparty got into the database by
typing at the bot or pressing something it sent. So `introducible` and the
no-route refusal it produced are deleted, and someone with no handle is a
counterparty like anybody else.

The accepted residual is Telegram's Forwarded Messages privacy setting. A person
who has restricted it cannot be linked to by user id, and their name renders as
plain text instead: the reader sees who they are and cannot tap through to them.
The bot cannot detect this — the setting is not exposed to it — so it cannot
warn either party, and it does not try. The two of them are in the same group,
or have both asked the bot to find them strangers; a name and a first message in
the chat where the suggestion landed is enough to find each other, and that is
the same fallback anyone has when a handle is missing.

The two surfaces still do not meet. A request typed in a chat is worked only
against that chat, and one resting with no chat behind it only against others
like it, because `Matcher` filters on the chat. That separation survives ADR
0007's reversal for a different reason than 0007 gave: not because consent is
what puts someone on the chatless side, but because a request typed in a group
was stated to that group, and showing it to strangers would publish it somewhere
its author never spoke.
