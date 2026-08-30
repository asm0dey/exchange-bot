---
# exchange-bot-35w8
title: No re-derivation job exists for rotating the MAC keyset
status: todo
type: task
priority: deferred
tags:
    - deferred-minor
    - task-2
created_at: 2026-08-30T14:45:05Z
updated_at: 2026-08-30T14:45:05Z
parent: exchange-bot-utgz
---

Rotating INDEX_KEYSET invalidates every stored chat_ref and user_ref, and because lookups
are string equality rather than verifyMac it would silently match zero rows. Recovery is
possible only because each sealed payload carries the identifiers its refs came from
(ruling R7) — but the one-off job that decrypts each payload, re-derives both refs and
rewrites them does not exist. Write it when a rotation is actually needed, not before.
Source: Task 2 review, Important 2.
