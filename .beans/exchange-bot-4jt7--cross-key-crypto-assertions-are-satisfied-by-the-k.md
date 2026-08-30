---
# exchange-bot-4jt7
title: Cross-key crypto assertions are satisfied by the key-id prefix alone
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-2
created_at: 2026-08-30T14:45:04Z
updated_at: 2026-08-30T14:45:04Z
parent: exchange-bot-utgz
---

Two fresh keysets always differ in their 4-byte key id, so the key-dependence assertions
pass even if the raw HMAC key bytes were identical, and the wrong-key open fails at key-id
lookup before any GCM verification runs. Both properties do hold, but they are proven by
the weaker route. Source: Task 2 review, Minor 4.
