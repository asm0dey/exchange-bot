---
# exchange-bot-7rrb
title: 'Task 2: Crypto'
status: completed
type: task
priority: normal
created_at: 2026-08-30T14:41:03Z
updated_at: 2026-08-30T14:41:25Z
parent: exchange-bot-utgz
---

Tink AEAD + MAC, seal/open/ref, newRefToken, KeysetGen.
Commits 13d8633, fab77d1 (fix round 1). Review found the deprecated getPrimitive overload
and that the MAC keyset could never be rotated; both addressed. 6 minors deferred.
