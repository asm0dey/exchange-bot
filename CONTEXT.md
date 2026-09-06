# Exchange Bot

A Telegram bot that acts as a **name-passing broker** for one group chat: it
matches two people who want opposite sides of the same currency exchange,
passes their names to each other, and steps out. It never holds money, quotes a
price, or settles anything — the two counterparties agree the rate between
themselves and deal bilaterally.

The vocabulary below is OTC broking, not exchange trading. That is deliberate:
the bot brokers introductions, so words like *order*, *book*, *fill* and
*execution* are avoided throughout, because each implies a binding instruction
or a venue that executes it. Neither exists here.

## Language

### The interest

**Request**:
One person's stated willingness to exchange an amount of one currency for the
other. It is an expression of interest — formally an *indication of interest* —
never a binding instruction.
_Avoid_: order, ad, listing, position, quote

**Interest**:
One person's stated willingness, given to the bot once. A request typed in a
chat is an interest with a single showing; an interest stated to the bot
privately is shown in several places at once and may also be worked on a
no-names basis.
_Avoid_: parent request, master request, order

**Showing**:
The interest as it exists in one chat — a request in its own right, with its own
short id, matched at that chat's size tolerance.
_Avoid_: copy, clone, duplicate, mirror

**No-names basis**:
Said of an interest worked with no chat behind it. Side and stated amount are
disclosed to a possible counterparty; identity is not, until a name give-up.
_Avoid_: pool, book, market, venue, dark pool

**Name give-up**:
Identities passing between two counterparties once both have agreed to it.
Nothing before it names anyone, and either side may decline by never agreeing.
_Avoid_: reveal, introduction, connect, match

**Side**:
Which way round a request runs. Exactly two values, always relative to the
pair's base currency.
_Avoid_: direction, buy/sell, way

**Offer**:
The side that **gives** the base currency and receives the quote currency.
_Avoid_: ask, sell side

**Bid**:
The side that **receives** the base currency and gives the quote currency.
_Avoid_: buy side

**Counterparty**:
Someone whose request is on the opposite side and leaves each of them a residual
they accept — in the same chat, or on a no-names basis. Being a counterparty
carries no obligation and reserves nothing.
_Avoid_: counterpart, peer, match, partner

**Suggestion**:
The bot naming one or more counterparties in reply to a request. Advisory: it
reserves nothing and the same person may be suggested to several others.
_Avoid_: match, allocation, assignment, pairing

**Resting**:
Said of a request that is live and unmatched. Requests rest; they do not queue,
and they are not ranked against each other by price or time.
_Avoid_: pending, queued, listed, in the book

**Done**:
The state two counterparties reach when they confirm the exchange actually
happened. Either of them may declare it, and it closes both requests. The word
is the OTC confirmation term, and deliberately not *filled* — nothing was
executed by anyone but the two people.
_Avoid_: filled, executed, settled, completed, fulfilled

### Size and rate

**Pair**:
The two currencies a chat exchanges between. Each chat has exactly one.
_Avoid_: market, symbol, currencies

**Base currency**:
The leg of the pair that notionals are expressed in, and the leg that Bid and
Offer are defined against.
_Avoid_: home currency, primary currency

**Quote currency**:
The other leg of the pair.
_Avoid_: secondary currency, counter currency

**Stated amount**:
The amount and currency exactly as the person typed them. It is what the bot
repeats back and what every comparison derives from; it is never rewritten into
the other currency.
_Avoid_: raw amount, original amount, input

**Notional**:
A request's size expressed in the base currency, derived from the stated amount
at the current reference rate. A working figure for comparison only, never
shown as a price.
_Avoid_: normalized amount, converted amount, value, volume

**Residual**:
What is left of one person's size when their counterparty is smaller. Expressed
in their own stated currency and always relative to their own size, so the
smaller of two counterparties has none.
_Avoid_: remainder, leftover, unfilled, balance

**Size tolerance**:
How large a residual someone accepts, as a percentage of their own size. Each
side judges its own, so a smaller counterparty is a counterparty. A chat sets
one for its showings; a person sets their own for no-names working, and it
never overrides a chat's.
_Avoid_: tolerance band, spread, threshold, margin, slippage

**Reference rate**:
A published daily rate used only to derive notionals so two requests can be
compared by size. Never a quote, never advice, and it binds nobody.
_Avoid_: rate, price, exchange rate (both ambiguous with the agreed rate)

**Agreed rate**:
The rate the two counterparties settle on between themselves. Deliberately
outside the bot: never asked for, never stored, never checked.
_Avoid_: final rate, actual rate, execution rate

### Where it happens

**Chat**:
The Telegram group whose members can be introduced to each other. A request
only ever meets requests from its own chat.
_Avoid_: room, group, channel (a Telegram channel is a different thing, and is
refused)

**Chat settings**:
The pair, size tolerance and time in force chosen for one chat by its
administrators.
_Avoid_: config, preferences, options

**Short id**:
The brief label a person types to refer to their own request. Unique only among
a chat's live requests and reused once one closes, so it is a handle for
conversation rather than a durable identifier.
_Avoid_: id, request id, reference

### Ending a request

**Time in force**:
How long a request rests before lapsing. A chat-level setting, expressed as a
number of days from when the request was made.
_Avoid_: TTL, lifetime, timeout, expiry window

**Expiry**:
A request lapsing because its time in force ran out. Nobody's decision, and
nobody is told.
_Avoid_: timeout, staleness, cleanup

**Cancellation**:
A person withdrawing their own request before anything came of it.
_Avoid_: deletion, removal, closing, pulling

**Forgetting**:
A person's demand that the bot erase what it stores about them, together with
the bot cleaning up its own messages naming them. Distinct from cancellation:
cancellation ends an interest, forgetting removes the record of it.
_Avoid_: deletion, unsubscribe, opt-out, GDPR request
