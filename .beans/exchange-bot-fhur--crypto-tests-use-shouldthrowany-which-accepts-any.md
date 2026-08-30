---
# exchange-bot-fhur
title: Crypto tests use shouldThrowAny, which accepts any exception
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

The AAD-mismatch, tampered-ciphertext and wrong-key tests would pass on an unrelated
NullPointerException just as readily as on a real authentication failure.
shouldThrow<GeneralSecurityException> would pin the contract. Plan-mandated: the brief
specified shouldThrowAny verbatim. Source: Task 2 review, Minor 3.
