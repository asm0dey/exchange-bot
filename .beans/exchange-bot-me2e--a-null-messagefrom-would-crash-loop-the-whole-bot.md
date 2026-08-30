---
# exchange-bot-me2e
title: A null message.from would crash-loop the whole bot
status: todo
type: bug
priority: high
tags:
    - systemic
    - task-15
created_at: 2026-08-30T19:50:51Z
updated_at: 2026-08-30T19:50:51Z
parent: exchange-bot-utgz
---

MessageUpdate does message.from!! at construction, inside UpdateSerializer.deserialize —
so the NPE fires during response decoding, before any handler runs and before lastUpdateId
advances. TgUpdateHandler.classify routes it to Fatal, ending the polling loop; Main's restart
loop then re-polls the same offset and hits the same poisoned update again, forever. Not
scoped to one chat: the whole bot stops.
Pre-existing and systemic — every @CommandHandler dispatches off the same construction path,
so it applies to every /sell today, not just migration notices. No cheap mitigation exists:
the throw is inside the library's own deserialization, before any handler-level catch. A real
fix means forking the library or replacing the polling layer.
Whether Telegram can actually deliver a message with from == null is unverified against the
live API; the implementer's reassurance came from external documentation.
Source: Task 15 implementer concern, mechanics confirmed by review (ruling R57).
