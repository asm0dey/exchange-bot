# Exchange Bot

A Telegram bot that helps people in one group chat find each other when they
want to exchange currency directly. It introduces people and records that they
agreed; it never holds money, sets a price, or settles anything.

## Language

### The trade

**Request**:
One person's stated willingness to hand over an amount of one currency in
exchange for the other. It is an expression of intent, not a commitment.
_Avoid_: order, offer, ad, listing, position

**Side**:
Which of the pair's two currencies the requester hands over. Determined by the
verb and the currency together — wanting RUB and selling EUR are the same side.
_Avoid_: direction, buy/sell, way

**Give currency**:
The currency of a request's side — what the person parts with.
_Avoid_: from-currency, source currency, sell currency

**Counterpart**:
Another person in the same chat whose request is on the opposite side and close
enough in size. Being someone's counterpart carries no obligation and does not
reserve either request.
_Avoid_: peer, match, partner, opposite number

**Suggestion**:
The bot naming one or more counterparts in reply to a request. A suggestion is
advisory: it reserves nothing, expires with nothing, and the same counterpart
may be suggested to several people.
_Avoid_: match, assignment, allocation, pairing

**Fulfilment**:
The record that two people say their exchange actually happened. Either
participant may declare it, and it closes both requests.
_Avoid_: settlement, completion, execution, closing

**Participant**:
One of the two people named in a fulfilment.
_Avoid_: party, side (side means the currency leg)

**Waitlist**:
Every open request that currently has no counterpart. It is a description of a
state, not a queue — nothing is ordered, reserved, or served in turn.
_Avoid_: queue, book, order book, backlog

### Money and size

**Pair**:
The two currencies a chat exchanges between. Each chat has exactly one.
_Avoid_: market, symbol, currencies

**Base currency**:
The leg of the pair that magnitudes are expressed in when requests are compared.
_Avoid_: home currency, primary currency

**Quote currency**:
The other leg of the pair.
_Avoid_: secondary currency, counter currency

**Stated amount**:
The amount and currency exactly as the person typed them. It is what the bot
repeats back and what it compares from — it is never rewritten into the other
currency.
_Avoid_: raw amount, original amount, input

**Normalized amount**:
A stated amount expressed in the base currency using the reference rate, solely
so two requests can be compared. It is a working figure, never shown as a price.
_Avoid_: converted amount, value, equivalent

**Tolerance band**:
How far apart two normalized amounts may be and still count as counterparts,
as a percentage of the larger. A chat-level setting.
_Avoid_: spread, threshold, margin, slippage

**Reference rate**:
A published daily rate the bot uses only to compare the size of two requests.
It is never a quote, never advice, and never binds the participants.
_Avoid_: rate, price, exchange rate (both are ambiguous with the agreed rate)

**Agreed rate**:
The rate the two participants settle on between themselves. Deliberately
outside the bot: never asked for, never stored, never checked.
_Avoid_: final rate, actual rate

### Where it happens

**Chat**:
The Telegram group whose members can be introduced to each other. A request
only ever meets requests from its own chat.
_Avoid_: room, group, channel (a Telegram channel is a different thing, and is
refused)

**Chat settings**:
The pair, tolerance band and request lifetime chosen for one chat by its
administrators.
_Avoid_: config, preferences, options

**Short id**:
The brief label a person types to refer to their own request. It is unique only
among a chat's live requests and is reused once a request closes, so it is a
handle for conversation rather than a durable identifier.
_Avoid_: id, request id, reference

### Ending a request

**Expiry**:
A request lapsing because its lifetime ran out. It is nobody's decision and
nobody is told.
_Avoid_: timeout, staleness, cleanup

**Cancellation**:
A person withdrawing their own request before anything came of it.
_Avoid_: deletion, removal, closing

**Forgetting**:
A person's demand that the bot erase what it stores about them, together with
the bot cleaning up its own messages naming them. Distinct from cancellation:
cancellation ends an intent, forgetting removes the record of it.
_Avoid_: deletion, unsubscribe, opt-out, GDPR request
