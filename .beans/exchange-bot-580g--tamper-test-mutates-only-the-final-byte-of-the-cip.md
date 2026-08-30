---
# exchange-bot-580g
title: Tamper test mutates only the final byte of the ciphertext
status: todo
type: bug
priority: low
tags:
    - deferred-minor
    - task-2
created_at: 2026-08-30T14:45:05Z
updated_at: 2026-08-30T14:45:05Z
parent: exchange-bot-utgz
---

That lands in the GCM tag. The key-id prefix, the nonce and the ciphertext body are
untested mutation targets. Source: Task 2 review, Minor 8.
